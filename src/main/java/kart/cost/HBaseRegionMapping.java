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
        return null;
      }
      return rl.getServerName().getServerName();
    } catch (IOException e) {
      missing = true;
      return null;
    }
  }

  @Override
  public boolean missingRegionMap() {
    return missing;
  }
}
