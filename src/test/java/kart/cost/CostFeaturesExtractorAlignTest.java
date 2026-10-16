package kart.cost;

import kart.compile.LayoutContext;
import kart.compile.PhysicalPlan;
import kart.compile.QueryCompiler;
import kart.compile.ScanTask;
import kart.codec.CodeRange;
import kart.catalog.StatsSnapshot;
import kart.ir.BoundIr;
import kart.plan.PlanBuilder;
import kart.plan.PlanEnvelope;
import kart.query.QueryIrFixtureTest;
import kart.snapshot.FixtureBuilder;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public final class CostFeaturesExtractorAlignTest {

  @Test
  void reconstructDeductsFetchedCandidateChunks() {
    LayoutContext layout = LayoutContext.from(FixtureBuilder.fixtureManifest());
    CostFeaturesExtractor ex = new CostFeaturesExtractor(layout, null);
    BoundIr ir = QueryIrFixtureTest.fixtureQuery();
    // TopK / similarity needs reconstruct.
    ir.result = new BoundIr.Result();
    ir.result.mode = "TOP_K";
    ir.result.k = 5;
    ir.similarity = new BoundIr.Similarity();
    ir.similarity.metric = "DTW";
    ir.similarity.reference_tid = 1L;

    List<PlanEnvelope> cands = PlanBuilder.buildCandidates(ir);
    PlanEnvelope plan = null;
    for (PlanEnvelope e : cands) {
      if (e.plan_id != null && !e.plan_id.startsWith("P_FULL")) {
        plan = e;
        break;
      }
    }
    if (plan == null) {
      plan = cands.get(0);
    }
    PhysicalPlan phys = new QueryCompiler(layout).compile(plan, ir);
    CostFeatures f = ex.extractFinal(phys, plan, ir);
    assertTrue(f.needsReconstruct, "expected reconstruct for TOP_K");
    assertTrue(f.estReconChunks > 0);
    long cached = Math.min(f.estReconChunks, Math.max(0L, f.estCandidateChunks));
    long expectUncached = Math.max(0L, f.estReconChunks - cached);
    assertEquals((long) Math.ceil(expectUncached * f.avgChunkBytes),
        f.estUncachedReconBytes,
        "uncached bytes must reflect FETCH overlap");
    if (f.estCandidateChunks >= f.estReconChunks && f.estReconChunks > 0) {
      assertEquals(0L, f.estUncachedReconBytes);
      CostCard card = new CostModel(null).estimateFinal(f);
      // meta + rho only when uncached=0
      assertTrue(card.l_hat_reconstruct > 0);
    }
  }

  @Test
  void advancedIndexRowsNotProxyOfTimeOrZ() {
    LayoutContext layout = LayoutContext.from(FixtureBuilder.fixtureManifest());
    kart.catalog.StatsSnapshot stats = new kart.catalog.StatsSnapshot();
    stats.time_bucket_posting_counts.put("1", 1000L);
    stats.zorder_cell_posting_counts.put("1", 2000L);
    stats.xz_code_posting_counts.put("42", 11L);
    stats.tshape_code_posting_counts.put("43", 13L);
    stats.xzt_code_posting_counts.put("44", 17L);
    CostFeaturesExtractor ex = new CostFeaturesExtractor(layout, stats);
    BoundIr ir = QueryIrFixtureTest.fixtureQuery();

    PlanEnvelope pTime = null;
    PlanEnvelope pXzt = null;
    PlanEnvelope pZ = null;
    PlanEnvelope pXz = null;
    for (PlanEnvelope e : PlanBuilder.buildCandidates(ir)) {
      CostFeaturesExtractor.AccessShape s = CostFeaturesExtractor.accessShape(e);
      if (pTime == null && s.hasTime && !s.usedXzt && !s.intersect) {
        pTime = e;
      }
      if (pXzt == null && s.usedXzt && !s.intersect) {
        pXzt = e;
      }
      if (pZ == null && s.hasZ && !s.usedXz && !s.usedTshape && !s.intersect) {
        pZ = e;
      }
      if (pXz == null && s.usedXz && !s.intersect) {
        pXz = e;
      }
    }
    assertTrue(pXzt != null && pTime != null, "need TIME and XZT plans");
    CostFeatures fTime = ex.extractFinal(new QueryCompiler(layout).compile(pTime, ir), pTime, ir);
    CostFeatures fXzt = ex.extractFinal(new QueryCompiler(layout).compile(pXzt, ir), pXzt, ir);
    long xztExtra = ((Number) fXzt.extras.get("xzt_index_rows")).longValue();
    long timeOnXzt = ((Number) fXzt.extras.get("time_index_rows")).longValue();
    assertEquals(0L, timeOnXzt, "XZT plan must not report TIME proxy rows");
    assertTrue(xztExtra > 0 || fXzt.missingStats, "xzt_index_rows set");
    assertTrue(fTime.estIndexRows != fXzt.estIndexRows || fXzt.missingStats || fTime.missingStats,
        "XZT estIndexRows should differ from TIME when stats diverge");
    if (pXz != null && pZ != null) {
      CostFeatures fXz = ex.extractFinal(new QueryCompiler(layout).compile(pXz, ir), pXz, ir);
      assertEquals(0L, ((Number) fXz.extras.get("z_index_rows")).longValue());
      assertTrue(((Number) fXz.extras.get("xz_index_rows")).longValue() > 0 || fXz.missingStats);
    }
  }

  @Test
  void rpcPerRangeGrowsWithRowsPerCachingPage() {
    LayoutContext layout = LayoutContext.from(FixtureBuilder.fixtureManifest());
    CostFeaturesExtractor ex = new CostFeaturesExtractor(layout, null);
    BoundIr ir = QueryIrFixtureTest.fixtureQuery();
    PlanEnvelope plan = PlanBuilder.buildCandidates(ir).get(0);
    PhysicalPlan phys = new QueryCompiler(layout).compile(plan, ir);
    // Force tiny caching so many rows ⇒ rpcPerRange > 1 when index rows are large.
    if (phys.scanTasks != null) {
      for (ScanTask t : phys.scanTasks) {
        t.caching = 1;
      }
    }
    CostFeatures f = ex.extractFinal(phys, plan, ir);
    if (f.estIndexRows > f.scanRanges && f.scanRanges > 0) {
      assertTrue(f.rpcPerRange > 1.0,
          "rpcPerRange=" + f.rpcPerRange + " rows=" + f.estIndexRows
              + " ranges=" + f.scanRanges);
    }
    assertTrue(f.rpcPerRange >= 1.0);
    assertEquals(f.scanRanges * f.rpcPerRange, f.rpcEst, 1e-9);
  }

  @Test
  void advancedPostingEstimateFallsBackToSampleAndSeparatesFilteredRows() {
    LayoutContext layout = LayoutContext.from(FixtureBuilder.fixtureManifest());
    BoundIr ir = QueryIrFixtureTest.fixtureQuery();
    List<CodeRange> precise = layout.xzSfc().ranges(ir.spatial.min_x, ir.spatial.min_y,
        ir.spatial.max_x, ir.spatial.max_y);
    assertTrue(!precise.isEmpty());
    long inside = precise.get(0).lo;
    long outside = precise.get(precise.size() - 1).hi + 1L;
    while (kart.compile.CodeRangeBudget.containsCode(precise, outside)) {
      outside++;
    }

    StatsSnapshot stats = new StatsSnapshot();
    stats.sample_rate = 0.5;
    StatsSnapshot.SampleChunk hit = new StatsSnapshot.SampleChunk();
    hit.xz_code = Long.valueOf(inside);
    StatsSnapshot.SampleChunk miss = new StatsSnapshot.SampleChunk();
    miss.xz_code = Long.valueOf(outside);
    stats.sample_chunks.add(hit);
    stats.sample_chunks.add(miss);

    PlanEnvelope pXz = null;
    for (PlanEnvelope plan : PlanBuilder.buildCandidates(ir)) {
      CostFeaturesExtractor.AccessShape shape = CostFeaturesExtractor.accessShape(plan);
      if (shape.usedXz && !shape.intersect && !shape.usedTshape) {
        pXz = plan;
        break;
      }
    }
    assertTrue(pXz != null, "need a single-index XZ plan");
    PhysicalPlan phys = new QueryCompiler(layout).compile(pXz, ir);
    CostFeatures f = new CostFeaturesExtractor(layout, stats).extractFinal(phys, pXz, ir);

    assertEquals(2L, f.estIndexRowsAfterCodeFilter);
    assertEquals(2L, f.estIndexRows);
    assertTrue(f.missingStats, "sample extrapolation must be marked uncertain");
    assertEquals("SAMPLE_EXTRAPOLATION", f.extras.get("xz_posting_count_source"));
    assertEquals(1, ((Number) f.extras.get("xz_sample_matches_scanned")).intValue());
  }

  @Test
  void legacySamplesWithoutAdvancedCodesAreNotTreatedAsPostingSamples() {
    LayoutContext layout = LayoutContext.from(FixtureBuilder.fixtureManifest());
    BoundIr ir = QueryIrFixtureTest.fixtureQuery();
    StatsSnapshot stats = new StatsSnapshot();
    stats.sample_rate = 0.01;
    stats.sample_chunks.add(new StatsSnapshot.SampleChunk());
    stats.sample_chunks.add(new StatsSnapshot.SampleChunk());
    PlanEnvelope pXz = null;
    for (PlanEnvelope plan : PlanBuilder.buildCandidates(ir)) {
      CostFeaturesExtractor.AccessShape shape = CostFeaturesExtractor.accessShape(plan);
      if (shape.usedXz && !shape.intersect && !shape.usedTshape) {
        pXz = plan;
        break;
      }
    }
    assertTrue(pXz != null, "need a single-index XZ plan");
    PhysicalPlan phys = new QueryCompiler(layout).compile(pXz, ir);
    CostFeatures f = new CostFeaturesExtractor(layout, stats).extractFinal(phys, pXz, ir);

    assertEquals("RANGE_UPPER_BOUND", f.extras.get("xz_posting_count_source"));
    assertTrue(f.missingStats, "legacy samples without XZ codes must stay uncertain");
  }

  @Test
  void fastAdvancedRangeCountMatchesThePhysicalBudgetedTasks() {
    LayoutContext layout = LayoutContext.from(FixtureBuilder.fixtureManifest());
    BoundIr ir = QueryIrFixtureTest.fixtureQuery();
    PlanEnvelope pXz = null;
    for (PlanEnvelope plan : PlanBuilder.buildCandidates(ir)) {
      CostFeaturesExtractor.AccessShape shape = CostFeaturesExtractor.accessShape(plan);
      if (shape.usedXz && !shape.intersect && !shape.usedTshape) {
        pXz = plan;
        break;
      }
    }
    assertTrue(pXz != null, "need a single-index XZ plan");
    CostFeatures fast = new CostFeaturesExtractor(layout, new StatsSnapshot()).extractFast(pXz, ir);
    PhysicalPlan phys = new QueryCompiler(layout).compile(pXz, ir);
    CostFeatures fin = new CostFeaturesExtractor(layout, new StatsSnapshot())
        .extractFinal(phys, pXz, ir);
    assertEquals(phys.scanTasks.size(), fast.scanRanges);
    assertEquals(fin.scanRanges, fast.scanRanges);
  }
}
