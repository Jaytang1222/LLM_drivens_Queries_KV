package kart.cost;

import org.apache.hadoop.hbase.HRegionLocation;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.Connection;
import org.apache.hadoop.hbase.client.RegionLocator;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Live HBase RegionLocator-backed mapping.
 */
public final class HBaseRegionMapping implements RegionMapping {

  private final Connection connection;
  private final ConcurrentHashMap<String, RegionLocator> locators =
      new ConcurrentHashMap<String, RegionLocator>();
  /** Process-lifetime locate cache: table|startHex → server name ("" = miss). */
  private final ConcurrentHashMap<String, String> locateResultCache =
      new ConcurrentHashMap<String, String>();
  private volatile boolean missing;

  public HBaseRegionMapping(Connection connection) {
    this.connection = connection;
    this.missing = connection == null;
  }

  @Override
  public String locate(String table, byte[] startRow) {
    if (connection == null || table == null || startRow == null) {
      missing = true;
      return null;
    }
    String cacheKey = table + "|" + bytesToHex(startRow);
    String cached = locateResultCache.get(cacheKey);
    if (cached != null) {
      return cached.isEmpty() ? null : cached;
    }
    try {
      RegionLocator loc = locators.get(table);
      if (loc == null) {
        loc = connection.getRegionLocator(TableName.valueOf(table));
        RegionLocator prev = locators.putIfAbsent(table, loc);
        if (prev != null) {
          loc = prev;
        }
      }
      HRegionLocation rl = loc.getRegionLocation(startRow, false);
      if (rl == null || rl.getServerName() == null) {
        missing = true;
        locateResultCache.putIfAbsent(cacheKey, "");
        return null;
      }
      String server = rl.getServerName().getServerName();
      locateResultCache.putIfAbsent(cacheKey, server == null ? "" : server);
      return server;
    } catch (IOException e) {
      missing = true;
      locateResultCache.putIfAbsent(cacheKey, "");
      return null;
    }
  }

  private static String bytesToHex(byte[] bytes) {
    if (bytes == null || bytes.length == 0) {
      return "";
    }
    char[] hex = "0123456789abcdef".toCharArray();
    char[] out = new char[bytes.length * 2];
    for (int i = 0; i < bytes.length; i++) {
      int v = bytes[i] & 0xFF;
      out[i * 2] = hex[v >>> 4];
      out[i * 2 + 1] = hex[v & 0x0F];
    }
    return new String(out);
  }

  @Override
  public boolean missingRegionMap() {
    return missing;
  }
}
