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
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SORT_MERGE multi-index plans and Top-K FRECHET/HAUSDORFF vs FullScanOracle (FR-7.3 depth).
 */
public final class SortMergeAndTopKMetricTest {

  private static final long BUCKET_MS = 600_000L;
  private static final Rect DOMAIN = new Rect(0, 0, 100, 100);

  @Test
  void sortMergeTzMatchesOracleIds() throws Exception {
    List<Trajectory> trajs = twoTrajs();
    BoundIr ir = idsQuery();
    ir.temporal = temporal(FixtureBuilder.T0, FixtureBuilder.T0 + 120_000L);
    ir.spatial = spatial(0, 0, 20, 20);

    IndexBuilders.LayoutParams lp = layoutParams();
    LayoutContext layout = LayoutContext.from(lp);
    MemoryBackend kv = buildKv(trajs, lp);
    try {
      PlanEnvelope env = PlanBuilder.buildForAccess(ir, true, true, false, "SORT_MERGE", null);
      assertTrue(env.plan_id.contains("SORT_MERGE"), env.plan_id);
      assertDiffOne(trajs, ir, layout, kv, env);
    } finally {
      kv.close();
    }
  }

  @Test
  void topKFrechetMatchesOracle() throws Exception {
    assertTopKMetric("FRECHET");
  }

  @Test
  void topKHausdorffMatchesOracle() throws Exception {
    assertTopKMetric("HAUSDORFF");
  }

  private static void assertTopKMetric(String metric) throws Exception {
    List<Trajectory> trajs = FixtureBuilder.trajectories();
    BoundIr ir = FixtureQueriesLike(metric);
    IndexBuilders.LayoutParams lp = FixtureBuilder.layoutParams();
    LayoutContext layout = LayoutContext.from(lp);
    MemoryBackend kv = SnapshotBuilderMem();
    try {
      FullScanOracle.Answer oracle = new FullScanOracle(trajs).evaluate(ir);
      QueryCompiler compiler = new QueryCompiler(layout);
      PlanValidator validator = new PlanValidator(layout);
      int safe = 0;
      for (PlanEnvelope env : PlanBuilder.buildCandidates(ir)) {
        PhysicalPlan phys = compiler.compile(env, ir);
        ValidationReport report = new ValidationReport();
        Optional<SafePlanHandle> handle = validator.validate(env, ir, phys, report);
        if (!handle.isPresent()) {
          continue;
        }
        safe++;
        Coordinator coord = new Coordinator(kv, ir, layout, ExecLimits.defaults());
        QueryResult result = coord.execute(handle.get());
        assertEquals("OK", result.status, env.plan_id + ": " + result.error);
        assertEquals(oracle.trajectoryIds, result.trajectoryIds, "plan " + env.plan_id);
      }
      assertTrue(safe > 0);
    } finally {
      kv.close();
    }
  }

  private static MemoryBackend SnapshotBuilderMem() throws Exception {
    return kart.snapshot.SnapshotBuilder.buildFixtureInMemory();
  }

  private static BoundIr FixtureQueriesLike(String metric) {
    BoundIr ir = kart.snapshot.FixtureQueries.fixtureTopK();
    ir.query_id = "q_topk_" + metric.toLowerCase();
    ir.similarity.metric = metric;
    return ir;
  }

  private static void assertDiffOne(List<Trajectory> trajs, BoundIr ir, LayoutContext layout,
                                    MemoryBackend kv, PlanEnvelope env) throws Exception {
    FullScanOracle.Answer oracle = new FullScanOracle(trajs).evaluate(ir);
    Set<String> expected = new HashSet<String>(oracle.trajectoryIds);
    QueryCompiler compiler = new QueryCompiler(layout);
    PlanValidator validator = new PlanValidator(layout);
    PhysicalPlan phys = compiler.compile(env, ir);
    ValidationReport report = new ValidationReport();
    Optional<SafePlanHandle> handle = validator.validate(env, ir, phys, report);
    assertTrue(handle.isPresent(), report.toString());
    Coordinator coord = new Coordinator(kv, ir, layout, ExecLimits.defaults());
    QueryResult result = coord.execute(handle.get());
    assertEquals("OK", result.status, result.error);
    assertEquals(expected, new HashSet<String>(result.trajectoryIds));
  }

  private static MemoryBackend buildKv(List<Trajectory> trajs, IndexBuilders.LayoutParams lp)
      throws Exception {
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

  private static List<Trajectory> twoTrajs() {
    List<Trajectory> trajs = new ArrayList<Trajectory>();
    trajs.add(new Trajectory("V1", "T1", 1L, pts(
        pt("V1", "T1", 1L, 0, FixtureBuilder.T0, 10, 10),
        pt("V1", "T1", 1L, 1, FixtureBuilder.T0 + 60_000L, 11, 11))));
    trajs.add(new Trajectory("V2", "T2", 2L, pts(
        pt("V2", "T2", 2L, 0, FixtureBuilder.T0, 50, 50),
        pt("V2", "T2", 2L, 1, FixtureBuilder.T0 + 60_000L, 51, 51))));
    return trajs;
  }

  private static BoundIr idsQuery() {
    BoundIr ir = new BoundIr();
    ir.ir_version = "1.0";
    ir.query_id = "q_sort_merge";
    ir.source = new BoundIr.Source();
    ir.source.dataset_id = "diff";
    ir.source.entity = "trajectory";
    ir.result = new BoundIr.Result();
    ir.result.mode = "TRAJECTORY_IDS";
    ir.snapshot = new BoundIr.Snapshot();
    ir.snapshot.manifest_id = "diff_manifest";
    ir.snapshot.semantics_version = "point_similarity_v2";
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
