package kart.exec;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;

/**
 * Minimal KV backend matching HBase Scan/Get semantics used by KART.
 * Scan is half-open [start, stop).
 */
public interface KvBackend extends AutoCloseable {

  void put(String table, byte[] row, Map<String, byte[]> columns) throws IOException;

  Map<String, byte[]> get(String table, byte[] row) throws IOException;

  List<Row> get(String table, List<byte[]> rows) throws IOException;

  /**
   * Scan [start, stop). stop may be null meaning unbounded (to end of table).
   * Materializes all rows — avoid for huge tables; prefer {@link #scanConsume}.
   */
  List<Row> scan(String table, byte[] start, byte[] stop, List<String> columns) throws IOException;

  /**
   * Streaming scan; does not retain rows after the consumer returns.
   * Default falls back to {@link #scan} (OK for small tables).
   */
  default void scanConsume(String table, byte[] start, byte[] stop, List<String> columns,
                           RowConsumer consumer) throws IOException {
    scanConsume(table, start, stop, columns, 1000, consumer);
  }

  /**
   * Streaming scan with HBase Scan caching hint (ignored by memory backends).
   */
  default void scanConsume(String table, byte[] start, byte[] stop, List<String> columns,
                           int caching, RowConsumer consumer) throws IOException {
    for (Row row : scan(table, start, stop, columns)) {
      consumer.accept(row);
    }
  }

  NavigableMap<byte[], Map<String, byte[]>> tableView(String table);

  @Override
  void close() throws IOException;

  interface RowConsumer {
    void accept(Row row) throws IOException;
  }

  final class Row {
    public final byte[] key;
    public final Map<String, byte[]> columns;

    public Row(byte[] key, Map<String, byte[]> columns) {
      this.key = key;
      this.columns = columns == null ? Collections.<String, byte[]>emptyMap() : columns;
    }
  }
}
