package kart.exec;

import kart.codec.Bytes;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory TreeMap backend with unsigned byte[] ordering (T0.8).
 */
public final class MemoryBackend implements KvBackend {

  private final ConcurrentHashMap<String, NavigableMap<byte[], Map<String, byte[]>>> tables =
      new ConcurrentHashMap<String, NavigableMap<byte[], Map<String, byte[]>>>();

  private NavigableMap<byte[], Map<String, byte[]>> table(String name) {
    NavigableMap<byte[], Map<String, byte[]>> t = tables.get(name);
    if (t == null) {
      t = new TreeMap<byte[], Map<String, byte[]>>(Bytes.UNSIGNED_COMPARATOR);
      NavigableMap<byte[], Map<String, byte[]>> prev = tables.putIfAbsent(name, t);
      if (prev != null) {
        t = prev;
      }
    }
    return t;
  }

  @Override
  public synchronized void put(String tableName, byte[] row, Map<String, byte[]> columns) {
    NavigableMap<byte[], Map<String, byte[]>> t = table(tableName);
    Map<String, byte[]> existing = t.get(row);
    if (existing == null) {
      existing = new HashMap<String, byte[]>();
      t.put(copy(row), existing);
    }
    for (Map.Entry<String, byte[]> e : columns.entrySet()) {
      existing.put(e.getKey(), copy(e.getValue()));
    }
  }

  @Override
  public synchronized Map<String, byte[]> get(String tableName, byte[] row) {
    Map<String, byte[]> m = table(tableName).get(row);
    if (m == null) {
      return Collections.emptyMap();
    }
    return copyMap(m);
  }

  @Override
  public synchronized List<Row> get(String tableName, List<byte[]> rows) {
    List<Row> out = new ArrayList<Row>(rows.size());
    for (byte[] r : rows) {
      Map<String, byte[]> cols = get(tableName, r);
      if (!cols.isEmpty()) {
        out.add(new Row(copy(r), cols));
      }
    }
    return out;
  }

  @Override
  public synchronized List<Row> scan(String tableName, byte[] start, byte[] stop, List<String> columns) {
    NavigableMap<byte[], Map<String, byte[]>> t = table(tableName);
    NavigableMap<byte[], Map<String, byte[]>> sub;
    if (start == null && stop == null) {
      sub = t;
    } else if (start == null) {
      sub = t.headMap(stop, false);
    } else if (stop == null) {
      sub = t.tailMap(start, true);
    } else {
      sub = t.subMap(start, true, stop, false);
    }
    List<Row> out = new ArrayList<Row>();
    for (Map.Entry<byte[], Map<String, byte[]>> e : sub.entrySet()) {
      Map<String, byte[]> cols = e.getValue();
      if (columns != null && !columns.isEmpty()) {
        Map<String, byte[]> filtered = new HashMap<String, byte[]>();
        for (String c : columns) {
          if (cols.containsKey(c)) {
            filtered.put(c, copy(cols.get(c)));
          }
        }
        out.add(new Row(copy(e.getKey()), filtered));
      } else {
        out.add(new Row(copy(e.getKey()), copyMap(cols)));
      }
    }
    return out;
  }

  @Override
  public NavigableMap<byte[], Map<String, byte[]>> tableView(String tableName) {
    return table(tableName);
  }

  @Override
  public void close() {
    tables.clear();
  }

  private static byte[] copy(byte[] b) {
    if (b == null) {
      return null;
    }
    byte[] c = new byte[b.length];
    System.arraycopy(b, 0, c, 0, b.length);
    return c;
  }

  private static Map<String, byte[]> copyMap(Map<String, byte[]> m) {
    Map<String, byte[]> out = new HashMap<String, byte[]>();
    for (Map.Entry<String, byte[]> e : m.entrySet()) {
      out.put(e.getKey(), copy(e.getValue()));
    }
    return out;
  }
}
