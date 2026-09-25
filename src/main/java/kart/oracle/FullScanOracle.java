package kart.oracle;

import kart.data.CanonicalPoint;
import kart.data.Chunk;
import kart.data.Trajectory;
import kart.exec.Dtw;
import kart.exec.TrajectorySimilarity;
import kart.ir.BoundIr;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Full-scan oracle over in-memory trajectories (T0.10).
 */
public final class FullScanOracle {

  public static final class ScoredId {
    public final long tid;
    public final String trajectoryId;
    public final double distance;

    public ScoredId(long tid, String trajectoryId, double distance) {
      this.tid = tid;
      this.trajectoryId = trajectoryId;
      this.distance = distance;
    }
  }

  public static final class Answer {
    public final List<String> trajectoryIds;
    public final List<ScoredId> topK;

    private Answer(List<String> trajectoryIds, List<ScoredId> topK) {
      this.trajectoryIds = trajectoryIds;
      this.topK = topK;
    }

    public static Answer ids(List<String> ids) {
      return new Answer(ids, null);
    }

    public static Answer topK(List<ScoredId> scored) {
      List<String> ids = new ArrayList<String>();
      for (ScoredId s : scored) {
        ids.add(s.trajectoryId);
      }
      return new Answer(ids, scored);
    }
  }

  private final List<Trajectory> trajectories;

  public FullScanOracle(List<Trajectory> trajectories) {
    this.trajectories = trajectories;
  }

  /** Oracle over IMPLEMENTATION_PLAN §18 fixture trajectories (R/A/B/C). */
  public static FullScanOracle forFixture() {
    return new FullScanOracle(kart.snapshot.FixtureBuilder.trajectories());
  }

  public Answer evaluate(BoundIr ir) {
    Set<Long> matched = new HashSet<Long>();
    for (Trajectory t : trajectories) {
      if (matchesPredicates(t, ir) && hasMatchingPoint(t, ir)) {
        matched.add(t.tid);
      }
    }

    if (ir.result != null && "TOP_K".equals(ir.result.mode)) {
      if (ir.similarity == null) {
        throw new IllegalArgumentException("TOP_K requires similarity");
      }
      long refTid = ir.similarity.reference_tid;
      Trajectory ref = findByTid(refTid);
      if (ref == null) {
        throw new IllegalArgumentException("reference tid not found: " + refTid);
      }
      if (ir.similarity.exclude_reference) {
        matched.remove(refTid);
      }
      List<ScoredId> scored = new ArrayList<ScoredId>();
      Dtw.Point[] refPts = toDtw(ref);
      if (ir.similarity.metric == null || ir.similarity.metric.isEmpty()) {
        throw new IllegalArgumentException(
            "similarity.metric required (DTW|FRECHET|HAUSDORFF); not defaulted");
      }
      String metric = ir.similarity.metric;
      if (!TrajectorySimilarity.isSupported(metric)) {
        throw new IllegalArgumentException("unsupported similarity metric=" + metric);
      }
      for (Long tid : matched) {
        Trajectory t = findByTid(tid);
        Dtw.Result d = TrajectorySimilarity.distance(metric, toDtw(t), refPts);
        scored.add(new ScoredId(t.tid, t.trajectoryId, d.distance));
      }
      Collections.sort(scored, new Comparator<ScoredId>() {
        @Override
        public int compare(ScoredId a, ScoredId b) {
          int c = Double.compare(a.distance, b.distance);
          if (c != 0) {
            return c;
          }
          return Long.compare(a.tid, b.tid);
        }
      });
      int k = ir.result.k == null ? scored.size() : ir.result.k;
      if (k < scored.size()) {
        scored = new ArrayList<ScoredId>(scored.subList(0, k));
      }
      return Answer.topK(scored);
    }

    List<String> ids = new ArrayList<String>();
    List<Long> tids = new ArrayList<Long>(matched);
    Collections.sort(tids);
    for (Long tid : tids) {
      ids.add(findByTid(tid).trajectoryId);
    }
    return Answer.ids(ids);
  }

  private boolean matchesPredicates(Trajectory t, BoundIr ir) {
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

  private boolean hasMatchingPoint(Trajectory t, BoundIr ir) {
    boolean needTemporal = ir.temporal != null;
    boolean needSpatial = ir.spatial != null;
    if (!needTemporal && !needSpatial) {
      // no ST predicate: attribute-only or empty — attribute handled above; empty means all
      return true;
    }
    for (CanonicalPoint p : t.points()) {
      boolean okT = !needTemporal
          || (p.timestampMs >= ir.temporal.start_ms && p.timestampMs < ir.temporal.end_ms);
      boolean okS = !needSpatial
          || (p.xM >= ir.spatial.min_x && p.xM <= ir.spatial.max_x
          && p.yM >= ir.spatial.min_y && p.yM <= ir.spatial.max_y);
      if (okT && okS) {
        return true;
      }
    }
    return false;
  }

  private Trajectory findByTid(long tid) {
    for (Trajectory t : trajectories) {
      if (t.tid == tid) {
        return t;
      }
    }
    return null;
  }

  private static Dtw.Point[] toDtw(Trajectory t) {
    Dtw.Point[] pts = new Dtw.Point[t.size()];
    int i = 0;
    for (CanonicalPoint p : t.points()) {
      pts[i++] = new Dtw.Point(p.xM, p.yM);
    }
    return pts;
  }
}
