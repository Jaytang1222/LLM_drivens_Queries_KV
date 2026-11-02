package kart.oracle;

import kart.data.CanonicalPoint;
import kart.data.Trajectory;
import kart.exec.Dtw;
import kart.exec.TrajectorySimilarity;
import kart.ir.BoundIr;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.TreeMap;

/**
 * Constant-memory FullScan oracle: stream trajectories once refs are loaded.
 * ID answers keep only matching ids; TOP_K keeps a bounded size-k heap.
 */
public final class StreamingFullScanOracle {

  private StreamingFullScanOracle() {}

  public static final class Session {
    private final List<QueryState> states;

    public Session(List<BoundIr> queries, Map<Long, Trajectory> refsByTid) {
      if (queries == null) {
        throw new IllegalArgumentException("queries required");
      }
      this.states = new ArrayList<QueryState>(queries.size());
      for (BoundIr ir : queries) {
        states.add(QueryState.create(ir, refsByTid));
      }
    }

    public void accept(Trajectory t) {
      if (t == null) {
        return;
      }
      for (QueryState s : states) {
        s.accept(t);
      }
    }

    public FullScanOracle.Answer answer(int index) {
      return states.get(index).finish();
    }

    public int size() {
      return states.size();
    }
  }

  /** Collect reference_tid values needed by TOP_K queries. */
  public static java.util.Set<Long> requiredRefTids(List<BoundIr> queries) {
    java.util.LinkedHashSet<Long> out = new java.util.LinkedHashSet<Long>();
    if (queries == null) {
      return out;
    }
    for (BoundIr ir : queries) {
      if (ir != null && ir.result != null && "TOP_K".equals(ir.result.mode)
          && ir.similarity != null) {
        out.add(Long.valueOf(ir.similarity.reference_tid));
      }
    }
    return out;
  }

  static boolean matches(Trajectory t, BoundIr ir) {
    return matchesPredicates(t, ir) && hasMatchingPoint(t, ir);
  }

  private static boolean matchesPredicates(Trajectory t, BoundIr ir) {
    if (ir.predicates == null) {
      return true;
    }
    for (BoundIr.Predicate p : ir.predicates) {
      if ("vehicle_id".equals(p.field) && "EQ".equals(p.op)) {
        if (!t.vehicleId.equals(p.value)) {
          return false;
        }
      } else {
        throw new UnsupportedOperationException("predicate not supported in oracle: " + p.field);
      }
    }
    return true;
  }

  private static boolean hasMatchingPoint(Trajectory t, BoundIr ir) {
    List<BoundIr.Temporal> temps = BoundIr.temporalsOf(ir);
    List<BoundIr.Spatial> spaces = BoundIr.spatialsOf(ir);
    boolean needTemporal = !temps.isEmpty();
    boolean needSpatial = !spaces.isEmpty();
    if (!needTemporal && !needSpatial) {
      return true;
    }
    for (CanonicalPoint p : t.points()) {
      boolean okT = !needTemporal || inAnyTemporal(temps, p.timestampMs);
      boolean okS = !needSpatial || inAnySpatial(spaces, p.xM, p.yM);
      if (okT && okS) {
        return true;
      }
    }
    return false;
  }

  private static boolean inAnyTemporal(List<BoundIr.Temporal> temps, long t) {
    for (BoundIr.Temporal w : temps) {
      if (w != null && t >= w.start_ms && t < w.end_ms) {
        return true;
      }
    }
    return false;
  }

  private static boolean inAnySpatial(List<BoundIr.Spatial> spaces, double x, double y) {
    for (BoundIr.Spatial s : spaces) {
      if (s != null
          && x >= s.min_x && x <= s.max_x
          && y >= s.min_y && y <= s.max_y) {
        return true;
      }
    }
    return false;
  }

  private static Dtw.Point[] toDtw(Trajectory t) {
    Dtw.Point[] pts = new Dtw.Point[t.size()];
    int i = 0;
    for (CanonicalPoint p : t.points()) {
      pts[i++] = new Dtw.Point(p.xM, p.yM);
    }
    return pts;
  }

  private abstract static class QueryState {
    final BoundIr ir;

    QueryState(BoundIr ir) {
      this.ir = ir;
    }

    abstract void accept(Trajectory t);

    abstract FullScanOracle.Answer finish();

    static QueryState create(BoundIr ir, Map<Long, Trajectory> refsByTid) {
      if (ir.result != null && "TOP_K".equals(ir.result.mode)) {
        return new TopKState(ir, refsByTid);
      }
      return new IdState(ir);
    }
  }

  private static final class IdState extends QueryState {
    private final TreeMap<Long, String> matched = new TreeMap<Long, String>();

    IdState(BoundIr ir) {
      super(ir);
    }

    @Override
    void accept(Trajectory t) {
      if (matches(t, ir)) {
        matched.put(Long.valueOf(t.tid), t.trajectoryId);
      }
    }

    @Override
    FullScanOracle.Answer finish() {
      return FullScanOracle.Answer.ids(new ArrayList<String>(matched.values()));
    }
  }

  private static final class TopKState extends QueryState {
    private final long refTid;
    private final boolean excludeRef;
    private final int k;
    private final String metric;
    private final Dtw.Point[] refPts;
    /** Max-heap by (distance desc, tid desc): head is worst of current top-k. */
    private final PriorityQueue<FullScanOracle.ScoredId> heap;

    TopKState(BoundIr ir, Map<Long, Trajectory> refsByTid) {
      super(ir);
      if (ir.similarity == null) {
        throw new IllegalArgumentException("TOP_K requires similarity");
      }
      this.refTid = ir.similarity.reference_tid;
      this.excludeRef = ir.similarity.exclude_reference;
      if (ir.similarity.metric == null || ir.similarity.metric.isEmpty()) {
        throw new IllegalArgumentException(
            "similarity.metric required (DTW|FRECHET|HAUSDORFF); not defaulted");
      }
      this.metric = ir.similarity.metric;
      if (!TrajectorySimilarity.isSupported(metric)) {
        throw new IllegalArgumentException("unsupported similarity metric=" + metric);
      }
      Trajectory ref = refsByTid == null ? null : refsByTid.get(Long.valueOf(refTid));
      if (ref == null) {
        throw new IllegalArgumentException("reference tid not found: " + refTid);
      }
      this.refPts = toDtw(ref);
      Integer kk = ir.result == null ? null : ir.result.k;
      this.k = kk == null ? Integer.MAX_VALUE : kk.intValue();
      this.heap = new PriorityQueue<FullScanOracle.ScoredId>(11,
          new Comparator<FullScanOracle.ScoredId>() {
            @Override
            public int compare(FullScanOracle.ScoredId a, FullScanOracle.ScoredId b) {
              int c = Double.compare(b.distance, a.distance);
              if (c != 0) {
                return c;
              }
              return Long.compare(b.tid, a.tid);
            }
          });
    }

    @Override
    void accept(Trajectory t) {
      if (excludeRef && t.tid == refTid) {
        return;
      }
      if (!matches(t, ir)) {
        return;
      }
      Dtw.Result d = TrajectorySimilarity.distance(metric, toDtw(t), refPts);
      FullScanOracle.ScoredId scored = new FullScanOracle.ScoredId(t.tid, t.trajectoryId, d.distance);
      if (k == Integer.MAX_VALUE) {
        heap.offer(scored);
        return;
      }
      if (heap.size() < k) {
        heap.offer(scored);
        return;
      }
      FullScanOracle.ScoredId worst = heap.peek();
      int cmp = Double.compare(scored.distance, worst.distance);
      if (cmp < 0 || (cmp == 0 && scored.tid < worst.tid)) {
        heap.poll();
        heap.offer(scored);
      }
    }

    @Override
    FullScanOracle.Answer finish() {
      List<FullScanOracle.ScoredId> scored = new ArrayList<FullScanOracle.ScoredId>(heap);
      Collections.sort(scored, new Comparator<FullScanOracle.ScoredId>() {
        @Override
        public int compare(FullScanOracle.ScoredId a, FullScanOracle.ScoredId b) {
          int c = Double.compare(a.distance, b.distance);
          if (c != 0) {
            return c;
          }
          return Long.compare(a.tid, b.tid);
        }
      });
      return FullScanOracle.Answer.topK(scored);
    }
  }

  /** Convenience: build tid→ref map from a small list (tests). */
  public static Map<Long, Trajectory> indexByTid(List<Trajectory> trajs) {
    Map<Long, Trajectory> out = new HashMap<Long, Trajectory>();
    if (trajs == null) {
      return out;
    }
    for (Trajectory t : trajs) {
      out.put(Long.valueOf(t.tid), t);
    }
    return out;
  }
}
