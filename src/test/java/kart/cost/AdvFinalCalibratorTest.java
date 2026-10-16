package kart.cost;

import kart.ir.BoundIr;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public final class AdvFinalCalibratorTest {

  @Test
  void bestAdmissibleAdvPrefersLowerEstimatedMs() {
    List<PlanSelector.Scored> scored = new ArrayList<PlanSelector.Scored>();
    scored.add(card("P_TZ", 50_000.0, 2000));
    scored.add(card("P_XZ", 40_000.0, 1200));
    scored.add(card("P_XZTZ", 45_000.0, 1100));
    PlanSelector.Scored classic = AdvFinalCalibrator.bestClassic(scored);
    assertEquals("P_TZ", AdvFinalCalibrator.planId(classic));
    PlanSelector.Scored adv = AdvFinalCalibrator.bestAdmissibleAdv(
        scored, classic, AdvQueryRouter.Route.SPATIAL_XZ);
    assertNotNull(adv);
    assertEquals("P_XZ", AdvFinalCalibrator.planId(adv));
  }

  @Test
  void admissibleWhenAdvPostingsBeatClassicDecode() {
    List<PlanSelector.Scored> scored = new ArrayList<PlanSelector.Scored>();
    scored.add(cardClassic("P_TZ", 50_000.0, 1760, 1_200_000, 3900));
    scored.add(cardAdv("P_XZ", 80_000.0, 256, 10_575, 12_556));
    assertEquals(1, AdvFinalCalibrator.admissibleAdvPlanIds(scored).size());
    assertEquals("P_XZ", AdvFinalCalibrator.admissibleAdvPlanIds(scored).get(0));
  }

  @Test
  void predMsAdvBelowClassicOnForceLikeFeatures() {
    PlanSelector.Scored tz = cardClassic("P_TZ", 36_000.0, 1760, 1_192_356, 3900);
    tz.card.features.put("z_index_rows", Long.valueOf(1_192_356L));
    PlanSelector.Scored xz = cardAdv("P_XZ", 77_000.0, 256, 10_575, 12_556);
    assertTrue(AdvFinalCalibrator.predMs(xz) < AdvFinalCalibrator.predMs(tz));
  }

  @Test
  void wideForceLikeTsBeatsClassicProxy() {
    // mix6 adv_wide_1: Z wall 1021 / TS 705; classic proxy must clear 15% margin.
    PlanSelector.Scored z = cardClassic("P_Z", 26_000.0, 120, 161_799, 3500);
    z.card.features.put("z_index_rows", Long.valueOf(161_799L));
    PlanSelector.Scored ts = cardAdv("P_TS", 71_000.0, 256, 9_668, 9_671);
    double zMs = AdvFinalCalibrator.predMs(z);
    assertTrue(AdvFinalCalibrator.beatsClassic(ts, z, zMs),
        "TS proxy=" + AdvFinalCalibrator.predMs(ts) + " classic=" + zMs);
    List<PlanSelector.Scored> scored = new ArrayList<PlanSelector.Scored>();
    scored.add(z);
    scored.add(ts);
    PlanSelector.Scored adv = AdvFinalCalibrator.bestAdmissibleAdv(
        scored, z, AdvQueryRouter.Route.SPATIAL_XZ, null);
    assertEquals("P_TS", AdvFinalCalibrator.planId(adv));
  }

  @Test
  void mixedAdvancedProxyIncludesClassicBranchRows() {
    PlanSelector.Scored mixed = cardAdv("P_XZTZ", 373_968.0, 260, 48_650, 48_650);
    mixed.card.features.put("estimated_index_rows", Long.valueOf(4_426_364L));
    mixed.card.features.put("z_index_rows", Long.valueOf(4_377_714L));

    double advBranchMs = 48_650.0 * 0.02 + 48_650.0 * 0.03 + 260.0 * 0.5;
    double classicBranchMs = 4_377_714.0 * 0.0015;
    assertEquals(Math.max(advBranchMs, classicBranchMs), AdvFinalCalibrator.predMs(mixed), 1e-9);
  }

  @Test
  void spatialIrPrefersZBearingClassicOverCheapTimeOnly() {
    List<PlanSelector.Scored> scored = new ArrayList<PlanSelector.Scored>();
    scored.add(cardClassic("P_T", 5_000.0, 10, 500, 20));
    scored.add(cardClassic("P_Z", 80_000.0, 640, 5_000, 200));
    scored.add(cardAdv("P_XZ", 90_000.0, 256, 10_000, 10_000));
    BoundIr ir = new BoundIr();
    ir.spatial = new BoundIr.Spatial();
    ir.spatial.min_x = 0; ir.spatial.min_y = 0;
    ir.spatial.max_x = 1; ir.spatial.max_y = 1;
    PlanSelector.Scored classic = AdvFinalCalibrator.bestClassicNonFullByPredMs(scored, ir);
    assertEquals("P_Z", AdvFinalCalibrator.planId(classic));
    PlanSelector.Scored picked = AdvFinalCalibrator.selectClassicOnly(scored, ir);
    assertEquals("P_Z", AdvFinalCalibrator.planId(picked));
  }

  @Test
  void tsMayBePreferredWhenCheapestAdv() {
    List<PlanSelector.Scored> scored = new ArrayList<PlanSelector.Scored>();
    scored.add(card("P_TZ", 50_000.0, 2000));
    scored.add(card("P_XZ", 40_000.0, 1200));
    scored.add(card("P_TS", 8_000.0, 500));
    PlanSelector.Scored classic = AdvFinalCalibrator.bestClassic(scored);
    PlanSelector.Scored adv = AdvFinalCalibrator.bestAdmissibleAdv(
        scored, classic, AdvQueryRouter.Route.SPATIAL_XZ, null);
    assertNotNull(adv);
    assertEquals("P_TS", AdvFinalCalibrator.planId(adv));
  }

  @Test
  void fatRouteKeepsXzWhenClearlyCheaperThanTs() {
    List<PlanSelector.Scored> scored = new ArrayList<PlanSelector.Scored>();
    scored.add(cardClassic("P_Z", 26_000.0, 120, 161_799, 3500));
    scored.get(0).card.features.put("z_index_rows", Long.valueOf(161_799L));
    scored.add(cardAdv("P_XZ", 71_000.0, 256, 8_000, 8_000));
    scored.add(cardAdv("P_TS", 71_000.0, 256, 12_000, 12_000));
    PlanSelector.Scored adv = AdvFinalCalibrator.bestAdmissibleAdv(
        scored, scored.get(0), AdvQueryRouter.Route.SPATIAL_XZ, null);
    assertEquals("P_XZ", AdvFinalCalibrator.planId(adv));
  }

  @Test
  void whenAdmitFailsClassicOnlyIgnoresCheaperAdvEstimatedMs() {
    // CostModel ranks XZ cheaper; proxy does not clear 20% margin vs Z.
    List<PlanSelector.Scored> scored = new ArrayList<PlanSelector.Scored>();
    scored.add(cardClassic("P_Z", 300_000.0, 640, 20_000, 200));
    scored.add(cardAdv("P_XZ", 90_000.0, 256, 18_000, 18_000));
    scored.add(cardAdv("P_TXZ", 80_000.0, 300, 18_000, 18_000));
    assertTrue(AdvFinalCalibrator.admissibleAdvPlanIds(scored).isEmpty());
    PlanSelector.Scored picked = AdvFinalCalibrator.selectClassicOnly(scored);
    assertNotNull(picked);
    assertEquals("P_Z", AdvFinalCalibrator.planId(picked));
  }

  @Test
  void txzIsAdvancedNotClassic() {
    assertTrue(AdvFinalCalibrator.isAdvId("P_TXZ"));
    assertTrue(AdvFinalCalibrator.isAdvId("P_XZTZ"));
    assertTrue(!AdvFinalCalibrator.isAdvId("P_TZ"));
    assertTrue(!AdvFinalCalibrator.isAdvId("P_Z"));
  }

  @Test
  void tzFamilyHelper() {
    assertTrue(AdvFinalCalibrator.isTzFamily("P_TZ"));
  }

  private static PlanSelector.Scored card(String planId, double ms, int ranges) {
    CostCard c = new CostCard();
    c.plan_id = planId;
    c.estimated_ms = ms;
    c.features.put("scan_ranges", Integer.valueOf(ranges));
    return new PlanSelector.Scored(null, c);
  }

  private static PlanSelector.Scored cardAdv(String planId, double ms, int ranges,
                                             long precise, long merged) {
    PlanSelector.Scored s = card(planId, ms, ranges);
    s.card.features.put("est_postings_precise", Long.valueOf(precise));
    s.card.features.put("est_postings_merged", Long.valueOf(merged));
    s.card.features.put("decode_rows", Long.valueOf(precise));
    s.card.features.put("estimated_candidate_chunks", Long.valueOf(precise));
    return s;
  }

  private static PlanSelector.Scored cardClassic(String planId, double ms, int ranges,
                                                 long decode, long cand) {
    PlanSelector.Scored s = card(planId, ms, ranges);
    s.card.features.put("decode_rows", Long.valueOf(decode));
    s.card.features.put("estimated_index_rows", Long.valueOf(decode));
    s.card.features.put("estimated_candidate_chunks", Long.valueOf(cand));
    return s;
  }
}
