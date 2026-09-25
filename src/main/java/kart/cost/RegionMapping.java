package kart.cost;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * Maps (table, startRow) → RegionServer id for ScheduleEstimate / RS-affinity execution.
 */
public interface RegionMapping {

  /** RegionServer key for affinity; null if unknown. */
  String locate(String table, byte[] startRow);

  /** True when locator is unavailable / incomplete. */
  boolean missingRegionMap();

  /** Fallback: shard-byte affinity when HBase locator is absent. */
  final class ShardFallback implements RegionMapping {
    @Override
    public String locate(String table, byte[] startRow) {
      if (startRow == null || startRow.length == 0) {
        return "unknown";
      }
      return "shard:" + (startRow[0] & 0xFF);
    }

    @Override
    public boolean missingRegionMap() {
      return true;
    }
  }
}
