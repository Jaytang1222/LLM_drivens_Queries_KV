package kart.exec;

import kart.codec.RowKeyCodec;
import kart.compile.LayoutContext;
import kart.compile.PhysicalPlan;
import kart.compile.QueryCompiler;
import kart.ir.BoundIr;
import kart.plan.PlanBuilder;
import kart.plan.PlanEnvelope;
import kart.cost.CostCard;
import kart.cost.CostFeatures;
import kart.cost.CostFeaturesExtractor;
import kart.cost.CostModel;
import kart.cost.PlanSelector;
import kart.query.QueryEngine;
import kart.query.QueryIrFixtureTest;
import kart.snapshot.FixtureBuilder;
import kart.snapshot.SnapshotBuilder;
import kart.validation.PlanValidator;
import kart.validation.SafePlanHandle;
import kart.validation.ValidationReport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T2.6 failure paths: RESOURCE_EXHAUSTED and DATA_INTEGRITY_ERROR with empty result lists.
 */
class CoordinatorFailureTest {

  @TempDir
  Path tmp;

  @Test
  void maxCandidateChunksOneYieldsResourceExhausted() throws Exception {
    BoundIr ir = QueryIrFixtureTest.fixtureQuery();
    MemoryBackend kv = SnapshotBuilder.buildFixtureInMemory();
    try {
      LayoutContext layout = LayoutContext.from(FixtureBuilder.fixtureManifest());
      ExecLimits limits = ExecLimits.defaults();
      limits.maxCandidateChunks = 1;

      QueryEngine engine = new QueryEngine(kv, layout, limits);
      QueryEngine.RunResult rr = engine.run(ir, tmp.resolve("runs"));

      assertNotNull(rr.result);
      assertEquals("RESOURCE_EXHAUSTED", rr.result.status, rr.result.error);
      assertTrue(rr.result.trajectoryIds == null || rr.result.trajectoryIds.isEmpty(),
          "no partial trajectory ids on failure");
      assertTrue(rr.result.topK == null || rr.result.topK.isEmpty(),
          "no partial topK on failure");
    } finally {
      kv.close();
    }
  }

  @Test
  void deletedRawChunkYieldsDataIntegrityErrorOnTopK() throws Exception {
    BoundIr ir = QueryIrFixtureTest.fixtureQuery();
    MemoryBackend kv = SnapshotBuilder.buildFixtureInMemory();
    try {
      LayoutContext layout = LayoutContext.from(FixtureBuilder.fixtureManifest());
      // Delete trajectory A's only raw chunk (tid=1, chunk=0) before FETCH/BATCH_GET.
      int shard = RowKeyCodec.shardOf(1L, layout.shardCount) & 0xFF;
      byte[] rawKey = RowKeyCodec.encodeRaw(shard, 1L, 0);
      assertNotNull(kv.tableView(layout.tableRaw).remove(rawKey), "expected raw row for A");

      QueryEngine engine = new QueryEngine(kv, layout);
      QueryEngine.RunResult rr = engine.run(ir, tmp.resolve("runs"));

      assertEquals("DATA_INTEGRITY_ERROR", rr.result.status, rr.result.error);
      assertTrue(rr.result.trajectoryIds == null || rr.result.trajectoryIds.isEmpty());
      assertTrue(rr.result.topK == null || rr.result.topK.isEmpty());
    } finally {
      kv.close();
    }
  }

  @Test
  void deletedRawChunkYieldsDataIntegrityErrorOnIdQuery() throws Exception {
    BoundIr ir = idQuery();
    MemoryBackend kv = SnapshotBuilder.buildFixtureInMemory();
    try {
      LayoutContext layout = LayoutContext.from(FixtureBuilder.fixtureManifest());
      int shard = RowKeyCodec.shardOf(1L, layout.shardCount) & 0xFF;
      byte[] rawKey = RowKeyCodec.encodeRaw(shard, 1L, 0);
      assertNotNull(kv.tableView(layout.tableRaw).remove(rawKey));

      List<PlanEnvelope> candidates = PlanBuilder.buildCandidates(ir);
      QueryCompiler compiler = new QueryCompiler(layout);
      PlanValidator validator = new PlanValidator(layout);
      java.util.ArrayList<SafePlanHandle> safe = new java.util.ArrayList<SafePlanHandle>();
      for (PlanEnvelope env : candidates) {
        PhysicalPlan phys = compiler.compile(env, ir);
        ValidationReport report = new ValidationReport();
        Optional<SafePlanHandle> h = validator.validate(env, ir, phys, report);
        if (h.isPresent()) {
          safe.add(h.get());
        }
      }
      CostModel model = new CostModel(null);
      CostFeaturesExtractor extractor = new CostFeaturesExtractor(layout, null);
      java.util.ArrayList<PlanSelector.Scored> scored =
          new java.util.ArrayList<PlanSelector.Scored>();
      for (SafePlanHandle h : safe) {
        CostFeatures f = extractor.extractFinal(h.physicalPlan(), h.plan(), ir);
        CostCard card = model.estimateFinal(f);
        scored.add(new PlanSelector.Scored(h, card));
      }
      SafePlanHandle selected = PlanSelector.select(scored);
      assertNotNull(selected);

      Coordinator coord = new Coordinator(kv, ir, layout, ExecLimits.defaults());
      QueryResult result = coord.execute(selected);

      assertEquals("DATA_INTEGRITY_ERROR", result.status, result.error);
      assertTrue(result.trajectoryIds == null || result.trajectoryIds.isEmpty());
      assertTrue(result.topK == null || result.topK.isEmpty());
    } finally {
      kv.close();
    }
  }

  private static BoundIr idQuery() {
    BoundIr ir = new BoundIr();
    ir.ir_version = "1.0";
    ir.query_id = "q_fixture_ids";
    ir.source = new BoundIr.Source();
    ir.source.dataset_id = "fixture_v1";
    ir.source.entity = "trajectory";
    ir.temporal = new BoundIr.Temporal();
    ir.temporal.start_ms = FixtureBuilder.T0;
    ir.temporal.end_ms = FixtureBuilder.T0 + 10 * 60 * 1000L;
    ir.spatial = new BoundIr.Spatial();
    ir.spatial.min_x = 4;
    ir.spatial.min_y = 4;
    ir.spatial.max_x = 8;
    ir.spatial.max_y = 8;
    ir.result = new BoundIr.Result();
    ir.result.mode = "TRAJECTORY_IDS";
    ir.snapshot = new BoundIr.Snapshot();
    ir.snapshot.manifest_id = FixtureBuilder.MANIFEST_ID;
    ir.snapshot.semantics_version = "point_dtw_v1";
    return ir;
  }
}
