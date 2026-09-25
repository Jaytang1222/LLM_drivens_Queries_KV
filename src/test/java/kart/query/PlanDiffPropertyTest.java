package kart.query;

import kart.compile.LayoutContext;
import kart.compile.PhysicalPlan;
import kart.compile.QueryCompiler;
import kart.data.CanonicalPoint;
import kart.data.Chunker;
import kart.data.Trajectory;
import kart.exec.Coordinator;
import kart.exec.ExecLimits;
import kart.exec.MemoryBackend;
import kart.exec.QueryResult;
import kart.geo.Rect;
import kart.ir.BoundIr;
import kart.oracle.FullScanOracle;
import kart.plan.PlanBuilder;
import kart.plan.PlanEnvelope;
import kart.snapshot.FixtureBuilder;
import kart.snapshot.IndexBuilders;
import kart.validation.PlanValidator;
import kart.validation.SafePlanHandle;
import kart.validation.ValidationReport;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FR-7.3 differential testing: every SafePlan from PlanBuilder+compile+validate
 * must match FullScanOracle on TRAJECTORY_IDS queries.
 *
 * <p>tries=1000 for FR-7.3 (~5× of 200 which ran ~92s wall clock on this host).
 */
class PlanDiffPropertyTest {

  private static final long BUCKET_MS = 600_000L;
  private static final Rect DOMAIN = new Rect(0, 0, 100, 100);

  // -------------------------------------------------------------- property

  @Property(tries = 1000)
  void safePlansMatchOracle(@ForAll Random rnd) throws Exception {
    List<Trajectory> trajs = randomTrajectories(rnd);
    BoundIr ir = randomBoundIr(rnd);
    assertDiffEqual(trajs, ir);
  }

  // --------------------------------------------------------- FR-7.3 examples

  @Example
  void emptyResult() throws Exception {
    List<Trajectory> trajs = twoTrajsNearOrigin();
    BoundIr ir = idsQuery();
    ir.temporal = temporal(FixtureBuilder.T0, FixtureBuilder.T0 + 60_000L);
    ir.spatial = spatial(90, 90, 95, 95);
    assertDiffEqual(trajs, ir);
  }

  @Example
  void crossBucketTime() throws Exception {
    long tLo = FixtureBuilder.T0 + BUCKET_MS - 60_000L;
    long tHi = FixtureBuilder.T0 + BUCKET_MS + 60_000L;
    List<Trajectory> trajs = new ArrayList<Trajectory>();
    trajs.add(traj("V1", "T1", 1L, pts(
        pt("V1", "T1", 1L, 0, tLo, 10, 10),
        pt("V1", "T1", 1L, 1, tHi, 11, 11))));
    trajs.add(traj("V2", "T2", 2L, pts(
        pt("V2", "T2", 2L, 0, FixtureBuilder.T0, 50, 50),
        pt("V2", "T2", 2L, 1, FixtureBuilder.T0 + 30_000L, 51, 51))));
    BoundIr ir = idsQuery();
    ir.temporal = temporal(tLo, tHi + 1);
    assertDiffEqual(trajs, ir);
  }

  @Example
  void crossCellSpatial() throws Exception {
    List<Trajectory> trajs = new ArrayList<Trajectory>();
    trajs.add(traj("V1", "T1", 1L, pts(
        pt("V1", "T1", 1L, 0, FixtureBuilder.T0, 5, 5),
        pt("V1", "T1", 1L, 1, FixtureBuilder.T0 + 60_000L, 80, 80))));
    trajs.add(traj("V2", "T2", 2L, pts(
        pt("V2", "T2", 2L, 0, FixtureBuilder.T0, 40, 40),
        pt("V2", "T2", 2L, 1, FixtureBuilder.T0 + 60_000L, 41, 41))));
    BoundIr ir = idsQuery();
    ir.spatial = spatial(0, 0, 100, 100);
    assertDiffEqual(trajs, ir);
  }

  @Example
  void multiShardDifferentTidShards() throws Exception {
    List<Trajectory> trajs = new ArrayList<Trajectory>();
    trajs.add(traj("V1", "T1", 1L, pts(
        pt("V1", "T1", 1L, 0, FixtureBuilder.T0, 10, 10),
        pt("V1", "T1", 1L, 1, FixtureBuilder.T0 + 60_000L, 11, 11))));
    trajs.add(traj("V2", "T2", 2L, pts(
        pt("V2", "T2", 2L, 0, FixtureBuilder.T0, 12, 12),
        pt("V2", "T2", 2L, 1, FixtureBuilder.T0 + 60_000L, 13, 13))));
    trajs.add(traj("V3", "T3", 3L, pts(
        pt("V3", "T3", 3L, 0, FixtureBuilder.T0, 14, 14),
        pt("V3", "T3", 3L, 1, FixtureBuilder.T0 + 60_000L, 15, 15))));
    BoundIr ir = idsQuery();
    ir.temporal = temporal(FixtureBuilder.T0, FixtureBuilder.T0 + 120_000L);
    ir.spatial = spatial(0, 0, 20, 20);
    assertDiffEqual(trajs, ir);
  }

  @Example
  void boundaryInclusiveSpatial() throws Exception {
    List<Trajectory> trajs = new ArrayList<Trajectory>();
    trajs.add(traj("V1", "T1", 1L, pts(
        pt("V1", "T1", 1L, 0, FixtureBuilder.T0, 10.0, 10.0),
        pt("V1", "T1", 1L, 1, FixtureBuilder.T0 + 60_000L, 20.0, 20.0))));
    trajs.add(traj("V2", "T2", 2L, pts(
        pt("V2", "T2", 2L, 0, FixtureBuilder.T0, 50.0, 50.0),
        pt("V2", "T2", 2L, 1, FixtureBuilder.T0 + 60_000L, 51.0, 51.0))));
    BoundIr ir = idsQuery();
    ir.spatial = spatial(10.0, 10.0, 10.0, 10.0);
    assertDiffEqual(trajs, ir);
  }

  // --------------------------------------------------------------- generators

  private static List<Trajectory> randomTrajectories(Random rnd) {
    int n = 2 + rnd.nextInt(3); // 2..4
    List<Trajectory> list = new ArrayList<Trajectory>();
    for (int i = 0; i < n; i++) {
      long tid = i + 1L;
      String id = "T" + tid;
      String veh = "V" + tid;
      int nPts = 2 + rnd.nextInt(4); // 2..5
      List<CanonicalPoint> points = new ArrayList<CanonicalPoint>();
      for (int s = 0; s < nPts; s++) {
        long t = FixtureBuilder.T0
            + (long) rnd.nextInt(3) * BUCKET_MS
            + rnd.nextInt((int) BUCKET_MS);
        double x = rnd.nextDouble() * 100.0;
        double y = rnd.nextDouble() * 100.0;
        points.add(new CanonicalPoint(veh, id, tid, s, t, x, y));
      }
      list.add(new Trajectory(veh, id, tid, points));
    }
    return list;
  }

  /** mode: 0=time-only, 1=spatial-only, 2=ST */
  private static BoundIr randomBoundIr(Random rnd) {
    BoundIr ir = idsQuery();
    int mode = rnd.nextInt(3);
    if (mode == 0 || mode == 2) {
      long start = FixtureBuilder.T0
          + (long) rnd.nextInt(2) * BUCKET_MS
          + rnd.nextInt(300_000);
      long span = 60_000L + rnd.nextInt((int) BUCKET_MS);
      ir.temporal = temporal(start, start + span);
    }
    if (mode == 1 || mode == 2) {
      double x0 = rnd.nextDouble() * 90.0;
      double y0 = rnd.nextDouble() * 90.0;
      double w = 1.0 + rnd.nextDouble() * 20.0;
      double h = 1.0 + rnd.nextDouble() * 20.0;
      ir.spatial = spatial(x0, y0, Math.min(100.0, x0 + w), Math.min(100.0, y0 + h));
    }
    return ir;
  }

  // --------------------------------------------------------------- core assert

  static void assertDiffEqual(List<Trajectory> trajs, BoundIr ir) throws Exception {
    IndexBuilders.LayoutParams lp = layoutParams();
    LayoutContext layout = LayoutContext.from(lp);
    MemoryBackend kv = buildKv(trajs, lp);
    try {
      FullScanOracle.Answer oracle = new FullScanOracle(trajs).evaluate(ir);
      Set<String> expected = new HashSet<String>(oracle.trajectoryIds);

      QueryCompiler compiler = new QueryCompiler(layout);
      PlanValidator validator = new PlanValidator(layout);
      List<PlanEnvelope> candidates = PlanBuilder.buildCandidates(ir);
      int safeCount = 0;
      for (PlanEnvelope env : candidates) {
        PhysicalPlan phys = compiler.compile(env, ir);
        ValidationReport report = new ValidationReport();
        Optional<SafePlanHandle> handle = validator.validate(env, ir, phys, report);
        if (!handle.isPresent()) {
          continue;
        }
        safeCount++;
        Coordinator coord = new Coordinator(kv, ir, layout, ExecLimits.defaults());
        QueryResult result = coord.execute(handle.get());
        assertEquals("OK", result.status, env.plan_id + ": " + result.error);
        Set<String> got = new HashSet<String>(result.trajectoryIds);
        assertEquals(expected, got, "plan " + env.plan_id + " vs oracle");
      }
      assertTrue(safeCount > 0, "expected at least one safe plan");
    } finally {
      kv.close();
    }
  }

  private static MemoryBackend buildKv(List<Trajectory> trajs, IndexBuilders.LayoutParams lp)
      throws IOException {
    MemoryBackend kv = new MemoryBackend();
    IndexBuilders builders = new IndexBuilders(lp);
    Chunker chunker = new Chunker(256);
    for (Trajectory t : trajs) {
      builders.writeTrajectory(kv, t, chunker.chunk(t));
    }
    return kv;
  }

  private static IndexBuilders.LayoutParams layoutParams() {
    IndexBuilders.LayoutParams lp = new IndexBuilders.LayoutParams();
    lp.epochMs = FixtureBuilder.T0;
    lp.domain = DOMAIN;
    lp.shardCount = 4;
    lp.zorderLevel = 8;
    lp.bucketMs = BUCKET_MS;
    return lp;
  }

  // ----------------------------------------------------------------- fixtures

  private static List<Trajectory> twoTrajsNearOrigin() {
    List<Trajectory> trajs = new ArrayList<Trajectory>();
    trajs.add(traj("V1", "T1", 1L, pts(
        pt("V1", "T1", 1L, 0, FixtureBuilder.T0, 1, 1),
        pt("V1", "T1", 1L, 1, FixtureBuilder.T0 + 60_000L, 2, 2))));
    trajs.add(traj("V2", "T2", 2L, pts(
        pt("V2", "T2", 2L, 0, FixtureBuilder.T0, 3, 3),
        pt("V2", "T2", 2L, 1, FixtureBuilder.T0 + 60_000L, 4, 4))));
    return trajs;
  }

  private static BoundIr idsQuery() {
    BoundIr ir = new BoundIr();
    ir.ir_version = "1.0";
    ir.query_id = "q_diff";
    ir.source = new BoundIr.Source();
    ir.source.dataset_id = "diff";
    ir.source.entity = "trajectory";
    ir.result = new BoundIr.Result();
    ir.result.mode = "TRAJECTORY_IDS";
    ir.snapshot = new BoundIr.Snapshot();
    ir.snapshot.manifest_id = "diff_manifest";
    ir.snapshot.semantics_version = "point_dtw_v1";
    return ir;
  }

  private static BoundIr.Temporal temporal(long start, long end) {
    BoundIr.Temporal t = new BoundIr.Temporal();
    t.start_ms = start;
    t.end_ms = end;
    return t;
  }

  private static BoundIr.Spatial spatial(double minX, double minY, double maxX, double maxY) {
    BoundIr.Spatial s = new BoundIr.Spatial();
    s.min_x = minX;
    s.min_y = minY;
    s.max_x = maxX;
    s.max_y = maxY;
    return s;
  }

  private static Trajectory traj(String veh, String id, long tid, List<CanonicalPoint> points) {
    return new Trajectory(veh, id, tid, points);
  }

  private static CanonicalPoint pt(String veh, String id, long tid, int seq,
                                   long t, double x, double y) {
    return new CanonicalPoint(veh, id, tid, seq, t, x, y);
  }

  private static List<CanonicalPoint> pts(CanonicalPoint... ps) {
    List<CanonicalPoint> list = new ArrayList<CanonicalPoint>();
    for (CanonicalPoint p : ps) {
      list.add(p);
    }
    return list;
  }
}
