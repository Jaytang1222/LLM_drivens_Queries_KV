package kart.cost;

import kart.catalog.StatsSnapshot;
import kart.compile.LayoutContext;
import kart.compile.PhysicalPlan;
import kart.compile.QueryCompiler;
import kart.config.AppConfig;
import kart.geo.Rect;
import kart.ir.BoundIr;
import kart.plan.PlanBuilder;
import kart.plan.PlanEnvelope;
import kart.query.QueryIrFixtureTest;
import kart.snapshot.FixtureBuilder;
import kart.validation.SafePlanHandle;
import kart.validation.ValidationReport;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P5: T5.1–T5.3 cost features, model ranking flip, selector ties.
 */
public class CostModelTest {

  @Test
  void t51_fixtureFeaturesMatchManualDerivation() {
    LayoutContext layout = LayoutContext.from(FixtureBuilder.fixtureManifest());
    BoundIr ir = QueryIrFixtureTest.fixtureQuery();
    PlanEnvelope pTz = PlanBuilder.buildForAccess(ir, true, true, false);
    PhysicalPlan phys = new QueryCompiler(layout).compile(pTz, ir);

    StatsSnapshot stats = new StatsSnapshot();
    stats.sample_rate = 0.01;
    // one bucket covering fixture window
    long bucket = 0L; // T0 is epoch → bucket 0
    stats.time_bucket_posting_counts.put(Long.toString(bucket), 4L);
    // z cells for [4,8]x[4,8] on 0..100 L=8 — extractor will sum known cells
    StatsSnapshot.SampleChunk sc = new StatsSnapshot.SampleChunk();
    sc.tid = 1;
    sc.chunk_id = 0;
    sc.time_buckets.add(bucket);
    sc.z_cells.add(1L);
    stats.sample_chunks.add(sc);

    CostFeaturesExtractor ex = new CostFeaturesExtractor(layout, stats);
    CostFeatures f = ex.extractFinal(phys, pTz, ir);

    assertEquals(phys.scanTasks.size(), f.scanRanges);
    assertTrue(f.scanRanges > 0);
    assertTrue(f.estIndexRows >= 4L, "time postings at least 4");
    assertNotNull(f.toFeatureMap().get("scan_ranges"));
    assertFalse(f.accessBranches.isEmpty());
  }

  @Test
  void t51_missingStatsUsesConservativeUpperBoundAndFlag() {
    LayoutContext layout = LayoutContext.from(FixtureBuilder.fixtureManifest());
    BoundIr ir = QueryIrFixtureTest.fixtureQuery();
    PlanEnvelope pT = PlanBuilder.buildForAccess(ir, true, false, false);
    PhysicalPlan phys = new QueryCompiler(layout).compile(pT, ir);

    CostFeaturesExtractor ex = new CostFeaturesExtractor(layout, null);
    CostFeatures f = ex.extractFinal(phys, pT, ir);
    assertTrue(f.missingStats);
    assertTrue(f.estIndexRows >= 10_000L, "conservative upper bound");
    assertEquals("HIGH", new CostModel(null).estimateFinal(f).uncertainty.label);
  }

  @Test
  void t52_rankingFlipsNarrowTimeWideSpaceVsWideTimeNarrowSpace() {
    LayoutContext layout = LayoutContext.from(FixtureBuilder.fixtureManifest());
    AppConfig.CostCoeffs coeffs = new AppConfig.CostCoeffs();
    coeffs.calibrated = false;
    CostModel model = new CostModel(coeffs);
    assertFalse(model.calibrated());

    // Narrow time (1 bucket), wide space (many cells with huge postings)
    BoundIr narrowT = QueryIrFixtureTest.fixtureQuery();
    narrowT.temporal.end_ms = FixtureBuilder.T0 + 600_000L; // 1 bucket
    narrowT.spatial.min_x = 0;
    narrowT.spatial.min_y = 0;
    narrowT.spatial.max_x = 90;
    narrowT.spatial.max_y = 90;

    StatsSnapshot wideSpace = syntheticStats(/*timePerBucket*/ 10, /*zPerCell*/ 50_000);
    CostFeaturesExtractor ex1 = new CostFeaturesExtractor(layout, wideSpace);
    PlanEnvelope pT1 = PlanBuilder.buildForAccess(narrowT, true, false, false);
    PlanEnvelope pTz1 = PlanBuilder.buildForAccess(narrowT, true, true, false);
    PhysicalPlan physT1 = new QueryCompiler(layout).compile(pT1, narrowT);
    PhysicalPlan physTz1 = new QueryCompiler(layout).compile(pTz1, narrowT);
    CostCard cT1 = model.estimateFinal(ex1.extractFinal(physT1, pT1, narrowT));
    CostCard cTz1 = model.estimateFinal(ex1.extractFinal(physTz1, pTz1, narrowT));
    assertTrue(cT1.estimated_ms < cTz1.estimated_ms,
        "narrow-time/wide-space: P_T should beat P_TZ: " + cT1.estimated_ms + " vs " + cTz1.estimated_ms);

    // Wide time (many buckets with huge postings), narrow space
    BoundIr wideT = QueryIrFixtureTest.fixtureQuery();
    wideT.temporal.end_ms = FixtureBuilder.T0 + 600_000L * 40; // 40 buckets
    wideT.spatial.min_x = 4;
    wideT.spatial.min_y = 4;
    wideT.spatial.max_x = 5;
    wideT.spatial.max_y = 5;

    StatsSnapshot narrowSpace = syntheticStats(/*timePerBucket*/ 80_000, /*zPerCell*/ 5);
    CostFeaturesExtractor ex2 = new CostFeaturesExtractor(layout, narrowSpace);
    PlanEnvelope pT2 = PlanBuilder.buildForAccess(wideT, true, false, false);
    PlanEnvelope pTz2 = PlanBuilder.buildForAccess(wideT, true, true, false);
    PhysicalPlan physT2 = new QueryCompiler(layout).compile(pT2, wideT);
    PhysicalPlan physTz2 = new QueryCompiler(layout).compile(pTz2, wideT);
    CostCard cT2 = model.estimateFinal(ex2.extractFinal(physT2, pT2, wideT));
    CostCard cTz2 = model.estimateFinal(ex2.extractFinal(physTz2, pTz2, wideT));
    assertTrue(cTz2.estimated_ms < cT2.estimated_ms,
        "wide-time/narrow-space: P_TZ should beat P_T: " + cTz2.estimated_ms + " vs " + cT2.estimated_ms);
  }

  @Test
  void t53_tieBreaksByRangesThenPlanId() {
    List<PlanSelector.Scored> scored = new ArrayList<PlanSelector.Scored>();
    scored.add(scoredStub("P_Z", 100.0, 20));
    scored.add(scoredStub("P_T", 100.0, 10));
    scored.add(scoredStub("P_TZ", 100.0, 10));
    PlanSelector.Scored best = PlanSelector.selectScored(scored);
    assertNotNull(best);
    // same ms, fewer ranges → P_T and P_TZ both 10; plan_id → P_T < P_TZ
    assertEquals("P_T", best.handle.plan().plan_id);
  }

  private static PlanSelector.Scored scoredStub(String planId, double ms, int ranges) {
    PlanEnvelope env = new PlanEnvelope();
    env.plan_id = planId;
    PhysicalPlan phys = new PhysicalPlan();
    for (int i = 0; i < ranges; i++) {
      phys.scanTasks.add(new kart.compile.ScanTask());
    }
    SafePlanHandle handle = SafePlanHandleForTest.create(env, phys);
    CostCard card = new CostCard();
    card.plan_id = planId;
    card.estimated_ms = ms;
    card.features.put("scan_ranges", Integer.valueOf(ranges));
    return new PlanSelector.Scored(handle, card);
  }

  /** Fill dense posting maps so extractor finds counts for any bucket/cell. */
  private static StatsSnapshot syntheticStats(long timePerBucket, long zPerCell) {
    StatsSnapshot s = new StatsSnapshot();
    s.sample_rate = 0.01;
    for (long b = 0; b < 80; b++) {
      s.time_bucket_posting_counts.put(Long.toString(b), timePerBucket);
    }
    // dense enough for L=8 domain cells that queries touch
    for (long z = 0; z < 4096; z++) {
      s.zorder_cell_posting_counts.put(Long.toString(z), zPerCell);
    }
    // samples empty → intersect uses min(branch) upper bound (§13.3, no independence)
    return s;
  }

  @Test
  void calibratedSkipsLegacyMappingAndUsesEtaCellForDtw() {
    AppConfig.CostCoeffs c = new AppConfig.CostCoeffs();
    c.calibrated = true;
    c.alpha_seek = 0.0;
    c.c_scan = 1.0;
    c.eta_cell = 0.002;
    c.eta_cell_dtw = 0.00001;
    CostModel model = new CostModel(c);
    assertEquals(0.0, model.coeffs().alpha_seek, 1e-12);
    assertEquals(0.002, model.etaForMetric("DTW"), 1e-12);
  }

  @Test
  void uncalibratedLegacyMapsAlphaSeekFromCScan() {
    AppConfig.CostCoeffs c = new AppConfig.CostCoeffs();
    c.calibrated = false;
    c.alpha_seek = 0.0;
    c.c_scan = 1.0;
    CostModel model = new CostModel(c);
    assertEquals(1.0, model.coeffs().alpha_seek, 1e-12);
  }

  @Test
  void reconstructIncludesMetaGetAndUncachedBytes() {
    AppConfig.CostCoeffs c = new AppConfig.CostCoeffs();
    c.calibrated = true;
    c.gamma_rpc = 2.0;
    c.gamma_byte = 0.0;
    c.rho_linear = 0.0;
    CostModel model = new CostModel(c);
    CostFeatures f = new CostFeatures();
    f.scanRanges = 1;
    f.needsReconstruct = true;
    f.estEligibleTrajs = 10;
    f.estMetaGets = 10;
    f.estUncachedReconBytes = 0;
    f.avgTrajLen = 1;
    CostCard card = model.estimateFinal(f);
    assertEquals(20.0, card.l_hat_reconstruct, 1e-6);
  }

  /** Test-only handle factory via reflection. */
  private static final class SafePlanHandleForTest {
    static SafePlanHandle create(PlanEnvelope env, PhysicalPlan phys) {
      try {
        ValidationReport report = new ValidationReport();
        report.pass("Test", "synthetic");
        java.lang.reflect.Constructor<SafePlanHandle> c =
            SafePlanHandle.class.getDeclaredConstructor(
                PlanEnvelope.class, PhysicalPlan.class, ValidationReport.class);
        c.setAccessible(true);
        return c.newInstance(env, phys, report);
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }
  }
}
