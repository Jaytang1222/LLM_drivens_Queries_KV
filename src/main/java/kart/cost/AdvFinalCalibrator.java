package kart.cost;

import kart.ir.BoundIr;
import kart.plan.PlanBuilder;
import kart.search.LegalActionGenerator;

import java.util.ArrayList;
import java.util.List;

/**
 * ADV Final selection by predicted exec cost (precise-posting features), not gold
 * recall or fixed ADV-over-classic preference. Router only breaks ties among ADV.
 */
public final class AdvFinalCalibrator {

  /**
   * ADV must beat best non-FULL classic proxy by this fraction.
   * 15%: after classic predMs uses z_index_rows, force TS/XZ winners clear this
   * without admitting frag false wins (Z still much cheaper on proxy).
   */
  static final double DEFAULT_MARGIN_RATIO = 0.15d;
  /**
   * ADV precise postings must stay below this fraction of classic decode_rows.
   * 1.5 allows mega/wide shapes where XZ wall-clock wins via cheaper index scan
   * despite more postings than Z; still blocks frag false wins (XZ≈8×Z).
   */
  static final double DEFAULT_POSTING_RATIO_MAX = 1.5d;
  static final double DEFAULT_RANGE_RATIO = 0.95d;
  /**
   * When Final already prices classic below this (vehicle/short-window), do not
   * override with ADV posting proxy (a2_tzh_g-class).
   */
  /** Below this CostModel est, skip ADV admit (cheap classic already). Was 8s. */
  static final double DEFAULT_MIN_CLASSIC_EST_MS = 3_000d;

  private AdvFinalCalibrator() {}

  public static boolean enabled() {
    return LegalActionGenerator.advancedIndexesEnabled();
  }

  public static double marginRatio() {
    return envDouble("KART_ADV_COST_MARGIN_RATIO", DEFAULT_MARGIN_RATIO);
  }

  public static double postingRatioMax() {
    return envDouble("KART_ADV_POSTING_RATIO_MAX", DEFAULT_POSTING_RATIO_MAX);
  }

  public static double rangeRatioMax() {
    return envDouble("KART_ADV_RANGE_RATIO_MAX", DEFAULT_RANGE_RATIO);
  }

  public static double minClassicEstMs() {
    return envDouble("KART_ADV_MIN_CLASSIC_EST_MS", DEFAULT_MIN_CLASSIC_EST_MS);
  }

  private static double envDouble(String key, double def) {
    String v = System.getenv(key);
    if (v == null || v.trim().isEmpty()) {
      return def;
    }
    try {
      double d = Double.parseDouble(v.trim());
      return d >= 0.0d ? d : def;
    } catch (NumberFormatException e) {
      return def;
    }
  }

  public static PlanSelector.Scored select(List<PlanSelector.Scored> scored) {
    return select(scored, null);
  }

  public static PlanSelector.Scored select(List<PlanSelector.Scored> scored, BoundIr ir) {
    if (scored == null || scored.isEmpty()) {
      return null;
    }
    if (!enabled()) {
      return PlanSelector.selectScored(scored);
    }
    // When ADV is not admitted, Final must stay on classic — default CostModel
    // estimated_ms often ranks P_XZ below P_Z even when wall-clock favors Z.
    if (AdvQueryRouter.forceClassic(ir)) {
      return selectClassicOnly(scored, ir);
    }
    PlanSelector.Scored classic = bestClassicNonFullByPredMs(scored, ir);
    if (classic == null || classic.card == null) {
      // No H/T/Z/TZ in SafeSet — do not admit ADV vs FULL alone.
      return selectClassicOnly(scored, ir);
    }
    if (classic.card.estimated_ms < minClassicEstMs()) {
      return selectClassicOnly(scored, ir);
    }
    double classicMs = predMs(classic);
    AdvQueryRouter.Route route = AdvQueryRouter.route(ir);
    PlanSelector.Scored adv = bestAdmissibleAdv(scored, classic, route, ir);
    if (adv != null && beatsClassic(adv, classic, classicMs)) {
      return adv;
    }
    return selectClassicOnly(scored, ir);
  }

  /** predMs margin + posting-reduction gate vs non-FULL classic. */
  static boolean beatsClassic(PlanSelector.Scored adv, PlanSelector.Scored classic,
                              double classicMs) {
    if (adv == null || classic == null) {
      return false;
    }
    double advMs = predMs(adv);
    double margin = classicMs * marginRatio();
    if (!(advMs + margin < classicMs)) {
      return false;
    }
    double classicDecode = -1.0d;
    double advPrecise = -1.0d;
    if (classic.card != null && classic.card.features != null) {
      classicDecode = featureDouble(classic.card.features, "decode_rows");
      if (classicDecode < 0.0d) {
        classicDecode = featureDouble(classic.card.features, "estimated_index_rows");
      }
    }
    if (adv.card != null && adv.card.features != null) {
      advPrecise = featureDouble(adv.card.features, "est_postings_precise");
      if (advPrecise < 0.0d) {
        advPrecise = featureDouble(adv.card.features, "decode_rows");
      }
    }
    if (classicDecode > 0.0d && advPrecise >= 0.0d
        && advPrecise >= classicDecode * postingRatioMax()) {
      // Wide/mega force: TS/XZ can beat Z on wall-clock via fewer scan ranges even
      // when precise postings exceed classic decode — waive posting gate then.
      double cRanges = classic.card != null && classic.card.features != null
          ? featureDouble(classic.card.features, "scan_ranges") : -1.0d;
      double aRanges = adv.card != null && adv.card.features != null
          ? featureDouble(adv.card.features, "scan_ranges") : -1.0d;
      boolean rangeWin = aRanges > 0.0d && cRanges > 0.0d && aRanges < cRanges * 0.55d;
      if (!rangeWin) {
        return false;
      }
    }
    return true;
  }

  /** Lowest estimated_ms among non-ADV SafePlans (includes P_FULL). */
  static PlanSelector.Scored selectClassicOnly(List<PlanSelector.Scored> scored) {
    return selectClassicOnly(scored, null);
  }

  static PlanSelector.Scored selectClassicOnly(List<PlanSelector.Scored> scored, BoundIr ir) {
    List<PlanSelector.Scored> classic = new ArrayList<PlanSelector.Scored>();
    if (scored != null) {
      for (PlanSelector.Scored s : scored) {
        if (s != null && !isAdv(s)) {
          classic.add(s);
        }
      }
    }
    if (classic.isEmpty()) {
      return PlanSelector.selectScored(scored);
    }
    // Vehicle EQ: keep HASH in play (do not force Z-only shortlist).
    if (BoundIr.hasSpatial(ir) && PlanBuilder.vehicleEqPredicateIndex(ir) < 0) {
      List<PlanSelector.Scored> withZ = new ArrayList<PlanSelector.Scored>();
      for (PlanSelector.Scored s : classic) {
        if (classicUsesSpatial(planId(s))) {
          withZ.add(s);
        }
      }
      if (!withZ.isEmpty()) {
        // Prefer force-aligned predMs (CostModel often ranks P_TZ ≪ P_Z wrongly).
        PlanSelector.Scored best = null;
        double bestMs = Double.POSITIVE_INFINITY;
        for (PlanSelector.Scored s : withZ) {
          double ms = predMs(s);
          String id = planId(s);
          if (best == null || ms < bestMs - 1e-12
              || (Math.abs(ms - bestMs) <= 1e-12 && id.compareTo(planId(best)) < 0)) {
            best = s;
            bestMs = ms;
          }
        }
        return best;
      }
    }
    return PlanSelector.selectScored(classic);
  }

  /** Classic plans that scan Z (or FULL). ADV XZ already excluded by {@link #isAdv}. */
  public static boolean classicUsesSpatial(String planId) {
    if (planId == null || planId.isEmpty()) {
      return false;
    }
    if ("P_FULL".equals(planId)) {
      return true;
    }
    return planId.contains("Z");
  }

  /**
   * Exec proxy from posting/range features (force-plan aligned). ADV uses merged
   * scan postings + precise fetch; classic uses decode_rows (index postings).
   */
  public static double predMs(PlanSelector.Scored s) {
    if (s == null || s.card == null) {
      return Double.POSITIVE_INFINITY;
    }
    if (s.card.features == null) {
      return s.card.estimated_ms;
    }
    java.util.Map<String, Object> f = s.card.features;
    double ranges = featureDouble(f, "scan_ranges");
    if (ranges < 0.0d) {
      ranges = 1.0d;
    }
    double precise = featureDouble(f, "est_postings_precise");
    double merged = featureDouble(f, "est_postings_merged");
    if (isAdv(s) && precise >= 0.0d) {
      double advScanPost = merged > 0.0d ? merged : precise;
      // Coeffs aligned to mix force wall-clock (mega XZ/TS beat Z).
      double advBranchMs = advScanPost * 0.02d + precise * 0.03d + ranges * 0.5d;
      // Only price a classic side when the plan actually scans T/Z/H (XZTZ, TXZ…).
      // Pure XZ/TS/XZT/XZTXZ must not treat estimated_index_rows residue as classic.
      double zRows = featureDouble(f, "z_index_rows");
      double tRows = featureDouble(f, "time_index_rows");
      double hRows = featureDouble(f, "hash_index_rows");
      double classicRows = Math.max(0.0d, Math.max(zRows, Math.max(tRows, hRows)));
      double classicBranchMs = classicRows > 0.0d ? classicRows * 0.0015d : 0.0d;
      return Math.max(advBranchMs, classicBranchMs);
    }
    // Classic Z: wall-clock is driven by z_index scan + fetch, not decode_rows alone
    // (wide_1 force: proxy 562 vs wall 1021 when using estimated_index_rows*0.0015).
    double zRows = featureDouble(f, "z_index_rows");
    double decode = featureDouble(f, "decode_rows");
    if (decode < 0.0d) {
      decode = featureDouble(f, "estimated_index_rows");
    }
    double indexRows = zRows > 0.0d ? zRows : decode;
    double cand = featureDouble(f, "estimated_candidate_chunks");
    if (indexRows >= 0.0d) {
      double c = cand >= 0.0d ? cand : 0.0d;
      return indexRows * 0.0025d + c * 0.12d + ranges * 1.5d;
    }
    return s.card.estimated_ms;
  }

  private static double featureDouble(java.util.Map<String, Object> f, String key) {
    Object v = f.get(key);
    if (v instanceof Number) {
      return ((Number) v).doubleValue();
    }
    return -1.0d;
  }

  static PlanSelector.Scored bestClassic(List<PlanSelector.Scored> scored) {
    return bestClassic(scored, true);
  }

  public static PlanSelector.Scored bestClassicNonFull(List<PlanSelector.Scored> scored) {
    return bestClassic(scored, false, false, null);
  }

  /** Non-FULL classic ranked by {@link #predMs} (admit baseline). */
  public static PlanSelector.Scored bestClassicNonFullByPredMs(List<PlanSelector.Scored> scored) {
    return bestClassicNonFullByPredMs(scored, null);
  }

  public static PlanSelector.Scored bestClassicNonFullByPredMs(List<PlanSelector.Scored> scored,
                                                              BoundIr ir) {
    return bestClassic(scored, false, true, ir);
  }

  static PlanSelector.Scored bestClassic(List<PlanSelector.Scored> scored, boolean allowFull) {
    return bestClassic(scored, allowFull, false, null);
  }

  static PlanSelector.Scored bestClassic(List<PlanSelector.Scored> scored, boolean allowFull,
                                         boolean byPredMs) {
    return bestClassic(scored, allowFull, byPredMs, null);
  }

  static PlanSelector.Scored bestClassic(List<PlanSelector.Scored> scored, boolean allowFull,
                                         boolean byPredMs, BoundIr ir) {
    PlanSelector.Scored best = null;
    double bestMs = Double.POSITIVE_INFINITY;
    boolean needSpatial = BoundIr.hasSpatial(ir)
        && PlanBuilder.vehicleEqPredicateIndex(ir) < 0;
    for (PlanSelector.Scored s : scored) {
      if (s == null || isAdv(s)) {
        continue;
      }
      String id = planId(s);
      if (!allowFull && "P_FULL".equals(id)) {
        continue;
      }
      if (needSpatial && !classicUsesSpatial(id)) {
        continue;
      }
      double ms = byPredMs ? predMs(s)
          : (s.card != null ? s.card.estimated_ms : Double.POSITIVE_INFINITY);
      if (best == null || ms < bestMs - 1e-12
          || (Math.abs(ms - bestMs) <= 1e-12 && id.compareTo(planId(best)) < 0)) {
        best = s;
        bestMs = ms;
      }
    }
    // Fallback: if no Z-bearing classic, use any non-FULL classic.
    if (best == null && needSpatial) {
      return bestClassic(scored, allowFull, byPredMs, null);
    }
    return best;
  }

  static PlanSelector.Scored bestAdmissibleAdv(List<PlanSelector.Scored> scored,
                                               PlanSelector.Scored classic) {
    return bestAdmissibleAdv(scored, classic, AdvQueryRouter.Route.HYBRID, null);
  }

  static PlanSelector.Scored bestAdmissibleAdv(List<PlanSelector.Scored> scored,
                                               PlanSelector.Scored classic,
                                               AdvQueryRouter.Route route) {
    return bestAdmissibleAdv(scored, classic, route, null);
  }

  static PlanSelector.Scored bestAdmissibleAdv(List<PlanSelector.Scored> scored,
                                               PlanSelector.Scored classic,
                                               AdvQueryRouter.Route route,
                                               BoundIr ir) {
    if (classic == null) {
      return null;
    }
    AdvQueryRouter.Route r = route != null ? route : AdvQueryRouter.Route.HYBRID;
    boolean allowTs = true; // TS competes on cost; inject decides presence
    PlanSelector.Scored best = null;
    double bestMs = Double.POSITIVE_INFINITY;
    int bestPref = Integer.MAX_VALUE;
    String bestId = null;
    for (PlanSelector.Scored s : scored) {
      if (s == null || !isPreferableAdv(s, allowTs)) {
        continue;
      }
      double ms = predMs(s);
      String id = planId(s);
      int pref = AdvQueryRouter.preferenceRank(id, r);
      boolean better = best == null
          || ms < bestMs - 1e-12
          || (Math.abs(ms - bestMs) <= 1e-12 && pref < bestPref)
          || (Math.abs(ms - bestMs) <= 1e-12 && pref == bestPref && id.compareTo(bestId) < 0);
      if (better) {
        best = s;
        bestMs = ms;
        bestPref = pref;
        bestId = id;
      }
    }
    return best;
  }

  static boolean isPreferableAdv(PlanSelector.Scored s) {
    return isPreferableAdv(s, true);
  }

  static boolean isPreferableAdv(PlanSelector.Scored s, boolean allowTs) {
    String id = planId(s);
    if (!isAdvId(id)) {
      return false;
    }
    if (id.contains("TS") || id.contains("TSHAPE")) {
      return allowTs;
    }
    // XZ / XZT family (incl. P_TXZ, P_XZTZ, …)
    return true;
  }

  static boolean isAdv(PlanSelector.Scored s) {
    return isAdvId(planId(s));
  }

  /** True for any plan that uses XZ / XZT / TShape (not classic T/Z/H/TZ). */
  public static boolean isAdvId(String planId) {
    if (planId == null || planId.isEmpty()) {
      return false;
    }
    // Order: XZT before XZ so naming stays explicit; both mark ADV.
    return planId.contains("XZT") || planId.contains("XZ") || planId.contains("TS")
        || planId.contains("TSHAPE");
  }

  static boolean isTzFamily(String planId) {
    return planId != null && (planId.equals("P_TZ") || planId.startsWith("P_TZ"));
  }

  static PlanSelector.Scored findByPlanId(List<PlanSelector.Scored> scored, String planId) {
    if (planId == null || planId.isEmpty() || scored == null) {
      return null;
    }
    for (PlanSelector.Scored s : scored) {
      if (s != null && planId.equals(planId(s))) {
        return s;
      }
    }
    return null;
  }

  static int ranges(PlanSelector.Scored s) {
    if (s.card != null && s.card.features != null) {
      Object v = s.card.features.get("scan_ranges");
      if (v == null) {
        v = s.card.features.get("seek_ranges");
      }
      if (v instanceof Number) {
        return ((Number) v).intValue();
      }
    }
    if (s.handle != null) {
      return CostFeaturesExtractor.rangeCount(s.handle.physicalPlan());
    }
    return 0;
  }

  public static String planId(PlanSelector.Scored s) {
    if (s.handle != null && s.handle.plan() != null && s.handle.plan().plan_id != null) {
      return s.handle.plan().plan_id;
    }
    if (s.card != null && s.card.plan_id != null) {
      return s.card.plan_id;
    }
    return "";
  }

  public static List<String> admissibleAdvPlanIds(List<PlanSelector.Scored> scored) {
    PlanSelector.Scored classic = bestClassicNonFullByPredMs(scored, null);
    if (classic == null) {
      classic = bestClassic(scored, true, true, null);
    }
    List<String> out = new ArrayList<String>();
    if (classic == null || classic.card == null) {
      return out;
    }
    double classicMs = predMs(classic);
    for (PlanSelector.Scored s : scored) {
      if (!isPreferableAdv(s) || s.card == null) {
        continue;
      }
      if (beatsClassic(s, classic, classicMs)) {
        out.add(planId(s));
      }
    }
    return out;
  }
}
