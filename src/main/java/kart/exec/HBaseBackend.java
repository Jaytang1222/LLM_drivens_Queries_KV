package kart.exec;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.Admin;
import org.apache.hadoop.hbase.client.ColumnFamilyDescriptorBuilder;
import org.apache.hadoop.hbase.client.Connection;
import org.apache.hadoop.hbase.client.ConnectionFactory;
import org.apache.hadoop.hbase.client.Get;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.ResultScanner;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.client.Table;
import org.apache.hadoop.hbase.client.TableDescriptorBuilder;
import org.apache.hadoop.hbase.util.Bytes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * HBase-backed KvBackend. Connection is process-scoped singleton via factory.
 */
public final class HBaseBackend implements KvBackend {

  private final Connection connection;

  public HBaseBackend(Connection connection) {
    this.connection = connection;
  }

  public static Connection open(Path siteXml) throws IOException {
    Configuration conf = HBaseConfiguration.create();
    if (siteXml != null && Files.isRegularFile(siteXml)) {
      conf.addResource(new org.apache.hadoop.fs.Path(siteXml.toUri().toString()));
    }
    return ConnectionFactory.createConnection(conf);
  }

  public Connection connection() {
    return connection;
  }

  public void ensureTables(int shardCount, Map<String, String> tableNames) throws IOException {
    byte[][] splits = splitKeys(shardCount);
    try (Admin admin = connection.getAdmin()) {
      for (String name : tableNames.values()) {
        TableName tn = TableName.valueOf(name);
        if (admin.tableExists(tn)) {
          continue;
        }
        TableDescriptorBuilder b = TableDescriptorBuilder.newBuilder(tn);
        b.setColumnFamily(ColumnFamilyDescriptorBuilder.of("d"));
        admin.createTable(b.build(), splits);
      }
    }
  }

  static byte[][] splitKeys(int shardCount) {
    // Regions for shard prefixes 0x00..0x(S-1): S-1 split keys 0x01, 0x02, ...
    if (shardCount <= 1) {
      return new byte[0][];
    }
    byte[][] splits = new byte[shardCount - 1][];
    for (int i = 1; i < shardCount; i++) {
      splits[i - 1] = new byte[]{(byte) i};
    }
    return splits;
  }

  @Override
  public void put(String table, byte[] row, Map<String, byte[]> columns) throws IOException {
    try (Table t = connection.getTable(TableName.valueOf(table))) {
      Put put = new Put(row);
      for (Map.Entry<String, byte[]> e : columns.entrySet()) {
        String col = e.getKey();
        // expect "d:qualifier"
        int colon = col.indexOf(':');
        byte[] family = Bytes.toBytes(colon < 0 ? "d" : col.substring(0, colon));
        byte[] qual = Bytes.toBytes(colon < 0 ? col : col.substring(colon + 1));
        put.addColumn(family, qual, e.getValue());
      }
      t.put(put);
    }
  }

  public void putBatch(String table, List<PutSpec> puts) throws IOException {
    if (puts.isEmpty()) {
      return;
    }
    try (Table t = connection.getTable(TableName.valueOf(table))) {
      List<Put> batch = new ArrayList<Put>(puts.size());
      for (PutSpec s : puts) {
        Put put = new Put(s.row);
        for (Map.Entry<String, byte[]> e : s.columns.entrySet()) {
          String col = e.getKey();
          int colon = col.indexOf(':');
          byte[] family = Bytes.toBytes(colon < 0 ? "d" : col.substring(0, colon));
          byte[] qual = Bytes.toBytes(colon < 0 ? col : col.substring(colon + 1));
          put.addColumn(family, qual, e.getValue());
        }
        batch.add(put);
      }
      t.put(batch);
    }
  }

  @Override
  public Map<String, byte[]> get(String table, byte[] row) throws IOException {
    try (Table t = connection.getTable(TableName.valueOf(table))) {
      Result r = t.get(new Get(row));
      return resultToMap(r);
    }
  }

  @Override
  public List<Row> get(String table, List<byte[]> rows) throws IOException {
    if (rows.isEmpty()) {
      return Collections.emptyList();
    }
    try (Table t = connection.getTable(TableName.valueOf(table))) {
      List<Get> gets = new ArrayList<Get>(rows.size());
      for (byte[] r : rows) {
        gets.add(new Get(r));
      }
      Result[] results = t.get(gets);
      List<Row> out = new ArrayList<Row>();
      for (Result r : results) {
        if (r == null || r.isEmpty()) {
          continue;
        }
        out.add(new Row(r.getRow(), resultToMap(r)));
      }
      return out;
    }
  }

  @Override
  public List<Row> scan(String table, byte[] start, byte[] stop, List<String> columns) throws IOException {
    final List<Row> out = new ArrayList<Row>();
    scanConsume(table, start, stop, columns, new RowConsumer() {
      @Override
      public void accept(Row row) {
        out.add(row);
      }
    });
    return out;
  }

  @Override
  public void scanConsume(String tableName, byte[] start, byte[] stop, List<String> columns,
                          RowConsumer consumer) throws IOException {
    scanConsume(tableName, start, stop, columns, 1000, consumer);
  }

  @Override
  public void scanConsume(String tableName, byte[] start, byte[] stop, List<String> columns,
                          int caching, RowConsumer consumer) throws IOException {
    try (Table t = connection.getTable(TableName.valueOf(tableName))) {
      Scan scan = new Scan();
      if (start != null) {
        scan.withStartRow(start, true);
      }
      if (stop != null) {
        scan.withStopRow(stop, false);
      }
      int cache = caching > 0 ? caching : 1000;
      boolean keysOnly = columns != null && columns.isEmpty();
      if (keysOnly) {
        scan.setCacheBlocks(false);
        scan.setCaching(Math.max(cache, 2000));
        scan.addFamily(Bytes.toBytes("d"));
      } else {
        scan.setCaching(cache);
        if (columns != null) {
          for (String col : columns) {
            int colon = col.indexOf(':');
            byte[] family = Bytes.toBytes(colon < 0 ? "d" : col.substring(0, colon));
            byte[] qual = Bytes.toBytes(colon < 0 ? col : col.substring(colon + 1));
            scan.addColumn(family, qual);
          }
        }
      }
      try (ResultScanner rs = t.getScanner(scan)) {
        for (Result r : rs) {
          if (keysOnly) {
            consumer.accept(new Row(r.getRow(), Collections.<String, byte[]>emptyMap()));
          } else {
            consumer.accept(new Row(r.getRow(), resultToMap(r)));
          }
        }
      }
    }
  }

  @Override
  public NavigableMap<byte[], Map<String, byte[]>> tableView(String table) {
    throw new UnsupportedOperationException("tableView not supported on HBaseBackend");
  }

  @Override
  public void close() throws IOException {
    // caller owns Connection lifecycle
  }

  public void closeConnection() throws IOException {
    connection.close();
  }

  private static Map<String, byte[]> resultToMap(Result r) {
    if (r == null || r.isEmpty()) {
      return Collections.emptyMap();
    }
    Map<String, byte[]> out = new HashMap<String, byte[]>();
    NavigableMap<byte[], NavigableMap<byte[], byte[]>> noVersion = r.getNoVersionMap();
    for (Map.Entry<byte[], NavigableMap<byte[], byte[]>> fam : noVersion.entrySet()) {
      String family = Bytes.toString(fam.getKey());
      for (Map.Entry<byte[], byte[]> q : fam.getValue().entrySet()) {
        out.put(family + ":" + Bytes.toString(q.getKey()), q.getValue());
      }
    }
    return out;
  }

  public static final class PutSpec {
    public final byte[] row;
    public final Map<String, byte[]> columns;

    public PutSpec(byte[] row, Map<String, byte[]> columns) {
      this.row = row;
      this.columns = columns;
    }
  }
}
