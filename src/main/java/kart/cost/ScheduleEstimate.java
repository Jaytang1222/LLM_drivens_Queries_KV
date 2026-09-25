package kart.cost;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * Deterministic list scheduling with RegionServer affinity and limited concurrency
 * (IMPLEMENTATION_PLAN §13.4 ScheduleEstimate).
 */
public final class ScheduleEstimate {

  public static final class WorkItem {
    public final String rsKey;
    public final double workMs;

    public WorkItem(String rsKey, double workMs) {
      this.rsKey = rsKey == null || rsKey.isEmpty() ? "unknown" : rsKey;
      this.workMs = Math.max(0.0, workMs);
    }
  }

  public static final class Result {
    public final double wallClockMs;
    public final boolean usedRegionAffinity;
    public final boolean degradedMissingMap;

    public Result(double wallClockMs, boolean usedRegionAffinity, boolean degradedMissingMap) {
      this.wallClockMs = wallClockMs;
      this.usedRegionAffinity = usedRegionAffinity;
      this.degradedMissingMap = degradedMissingMap;
    }
  }

  private ScheduleEstimate() {}

  /**
   * @param concurrencyGlobal max workers overall
   * @param concurrencyPerRs max concurrent tasks per RegionServer
   */
  public static Result estimate(List<WorkItem> items, int concurrencyGlobal, int concurrencyPerRs,
                                boolean missingRegionMap) {
    if (items == null || items.isEmpty()) {
      return new Result(0.0, !missingRegionMap, missingRegionMap);
    }
    int global = Math.max(1, concurrencyGlobal);
    int perRs = Math.max(1, concurrencyPerRs);

    // Group by RS; within each RS, schedule with perRs slots (list scheduling longest-first).
    Map<String, List<Double>> byRs = new HashMap<String, List<Double>>();
    for (WorkItem w : items) {
      List<Double> list = byRs.get(w.rsKey);
      if (list == null) {
        list = new ArrayList<Double>();
        byRs.put(w.rsKey, list);
      }
      list.add(Double.valueOf(w.workMs));
    }

    // Each RS produces a wall time under perRs concurrency.
    List<Double> rsWalls = new ArrayList<Double>();
    for (List<Double> works : byRs.values()) {
      Collections.sort(works, new Comparator<Double>() {
        @Override
        public int compare(Double a, Double b) {
          return Double.compare(b.doubleValue(), a.doubleValue());
        }
      });
      rsWalls.add(Double.valueOf(listSchedule(works, perRs)));
    }

    // Across RS pools, limited by global concurrency: treat each RS wall as a "task"
    // of that duration running on a global pool of size min(global, #RS).
    Collections.sort(rsWalls, new Comparator<Double>() {
      @Override
      public int compare(Double a, Double b) {
        return Double.compare(b.doubleValue(), a.doubleValue());
      }
    });
    int slots = Math.min(global, Math.max(1, rsWalls.size()));
    double wall = listSchedule(rsWalls, slots);
    return new Result(wall, !missingRegionMap, missingRegionMap);
  }

  /** Classic list scheduling: assign next longest job to the least-loaded slot. */
  static double listSchedule(List<Double> worksDesc, int slots) {
    if (worksDesc == null || worksDesc.isEmpty()) {
      return 0.0;
    }
    int s = Math.max(1, slots);
    PriorityQueue<Double> loads = new PriorityQueue<Double>();
    for (int i = 0; i < s; i++) {
      loads.add(Double.valueOf(0.0));
    }
    for (Double w : worksDesc) {
      double cur = loads.poll().doubleValue();
      loads.add(Double.valueOf(cur + w.doubleValue()));
    }
    double max = 0.0;
    for (Double d : loads) {
      max = Math.max(max, d.doubleValue());
    }
    return max;
  }
}
