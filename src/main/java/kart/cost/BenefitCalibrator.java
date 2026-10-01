package kart.cost;

import kart.ir.BoundIr;
import kart.plan.PlanEnvelope;

/**
 * Development residual calibrator for relative exec benefit.
 *
 * <p>{@code pred_exec ≈ b0 + bR·log1p(ranges) + bF·log1p(fetch) + bI·log1p(index)}
 * fitted on opportunity-dev measured exec (not a holdout model). Online uses
 * FastCost feature estimates of the same quantities.
 *
 * <p>{@code S_hat(q,p) = pred_exec(cbo) - pred_exec(p)}. Positive means p is
 * predicted faster than CBO.
 *
 * <p>refine_5: missing features are not silently treated as measured zero for
 * gating; {@link #featureStatus} distinguishes missing vs present zero.
 */
public final class BenefitCalibrator {

  public static final String VERSION = "calib_loglin_v1";

  /** Intercept (cancels in S_hat). */
  static final double B0 = -4368.95402037d;
  static final double B_RANGES = 616.58721963d;
  static final double B_FETCH = 211.02887576d;
  static final double B_INDEX = 142.47608618d;

  public enum FeatureStatus {
    PRESENT,
    ZERO,
    MISSING
  }

  private final FastCost fastCost;
  private final kart.compile.LayoutContext layout;
  private final java.util.Map<PlanEnvelope, CostCard> requestCards =
      new java.util.IdentityHashMap<PlanEnvelope, CostCard>();
  private BoundIr requestIr;

  public BenefitCalibrator(FastCost fastCost) {
    this(fastCost, null);
  }

  public BenefitCalibrator(FastCost fastCost, kart.compile.LayoutContext layout) {
    this.fastCost = fastCost;
    this.layout = layout;
  }

  public static double resolveMinSpeculateSMs() {
    return envDouble("KART_CALIB_MIN_SPEC_S_MS", 500.0d);
  }

  public static double resolveMinAdoptSMs() {
    return envDouble("KART_CALIB_MIN_ADOPT_S_MS", 200.0d);
  }

  /**
   * Conservative saving uncertainty (ms). Empty/negative → unknown (do not
   * pretend zero). Default 800 from refine_5 residual screening order of
   * magnitude; override with {@code KART_CALIB_UNCERTAINTY_MS}.
   */
  public static Double resolveSavingUncertaintyMs() {
    if (OnlineBenefitModel.get() != null) return Double.valueOf(OnlineBenefitModel.get().marginMs);
    String raw = System.getenv("KART_CALIB_UNCERTAINTY_MS");
    if (raw == null || raw.trim().isEmpty()) {
      // refine_6: prefer one-sided overestimate margin when set.
      Double p = resolveOverestimateP80Ms();
      if (p != null) {
        return p;
      }
      return Double.valueOf(800.0d);
    }
    String v = raw.trim().toLowerCase();
    if ("unknown".equals(v) || "none".equals(v) || "na".equals(v)) {
      return null;
    }
    try {
      double d = Double.parseDouble(v);
      return d >= 0.0d ? Double.valueOf(d) : null;
    } catch (NumberFormatException e) {
      return null;
    }
  }

  /**
   * Empirical upper-side residual quantile for {@code r = S_pred - S_actual}
   * (refine_6 §6.3). Default ~1100 ≈ opportunity-dev p95; override with
   * {@code KART_CALIB_OVERESTIMATE_P95_MS} (alias {@code ..._P80_MS}).
   */
  public static Double resolveOverestimateP80Ms() {
    String raw = System.getenv("KART_CALIB_OVERESTIMATE_P95_MS");
    if (raw == null || raw.trim().isEmpty()) {
      raw = System.getenv("KART_CALIB_OVERESTIMATE_P80_MS");
    }
    if (raw == null || raw.trim().isEmpty()) {
      return Double.valueOf(1100.0d);
    }
    String v = raw.trim().toLowerCase();
    if ("unknown".equals(v) || "none".equals(v) || "na".equals(v)) {
      return null;
    }
    try {
      double d = Double.parseDouble(v);
      return d >= 0.0d ? Double.valueOf(d) : null;
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static double envDouble(String key, double def) {
    String raw = System.getenv(key);
    if (raw == null || raw.trim().isEmpty()) {
      return def;
    }
    try {
      return Double.parseDouble(raw.trim());
    } catch (NumberFormatException e) {
      return def;
    }
  }

  public double predictExecMs(PlanEnvelope env, BoundIr ir) {
    CostCard card = estimate(env, ir);
    return predictExecMs(card);
  }

  public double predictExecMs(CostCard card) {
    if (OnlineBenefitModel.get() != null) return OnlineBenefitModel.get().predict(card);
    double ranges = featOrZero(card, "scan_ranges", "seek_ranges");
    double fetch = featOrZero(card, "fetch_gets", "estimated_candidate_chunks");
    double index = featOrZero(card, "estimated_index_rows", "decode_rows");
    return B0 + B_RANGES * log1p(ranges) + B_FETCH * log1p(fetch) + B_INDEX * log1p(index);
  }

  /** True when any driver feature key is absent from the card. */
  public boolean hasMissingFeatures(CostCard card) {
    if (card == null || card.features == null) {
      return true;
    }
    return status(card, "scan_ranges", "seek_ranges") == FeatureStatus.MISSING
        || status(card, "fetch_gets", "estimated_candidate_chunks") == FeatureStatus.MISSING
        || status(card, "estimated_index_rows", "decode_rows") == FeatureStatus.MISSING;
  }

  public FeatureStatus featureStatus(CostCard card, String k1, String k2) {
    return status(card, k1, k2);
  }

  /** Predicted S = E_cbo - E_p (positive ⇒ p faster). NaN if cards unusable. */
  public double predictS(CostCard cboCard, CostCard candCard) {
    if (cboCard == null || candCard == null) {
      return Double.NaN;
    }
    return predictExecMs(cboCard) - predictExecMs(candCard);
  }

  /**
   * Conservative net-saving after uncertainty: {@code S - u}. When uncertainty
   * is unknown, returns {@link Double#NEGATIVE_INFINITY} so gates stay closed.
   */
  public static double conservativeNetS(double sHat, Double uncertaintyMs) {
    if (Double.isNaN(sHat) || Double.isInfinite(sHat)) {
      return Double.NEGATIVE_INFINITY;
    }
    if (uncertaintyMs == null) {
      return Double.NEGATIVE_INFINITY;
    }
    return sHat - uncertaintyMs.doubleValue();
  }

  public CostCard estimate(PlanEnvelope env, BoundIr ir) {
    if (fastCost == null || env == null) {
      return null;
    }
    try {
      if (OnlineBenefitModel.get() != null && layout != null) {
        if (requestIr != ir) { requestCards.clear(); requestIr = ir; }
        CostCard cached = requestCards.get(env);
        if (cached != null) return cached;
        CostCard card = CompiledBenefitFeatures.estimate(fastCost, layout, env, ir);
        requestCards.put(env, card);
        return card;
      }
      return fastCost.estimate(env, ir);
    } catch (Exception e) {
      return null;
    }
  }

  private static FeatureStatus status(CostCard card, String k1, String k2) {
    if (card == null || card.features == null) {
      return FeatureStatus.MISSING;
    }
    Object v = card.features.get(k1);
    if (v == null) {
      v = card.features.get(k2);
    }
    if (v == null) {
      return FeatureStatus.MISSING;
    }
    if (!(v instanceof Number)) {
      return FeatureStatus.MISSING;
    }
    if (((Number) v).doubleValue() == 0.0d) {
      return FeatureStatus.ZERO;
    }
    return FeatureStatus.PRESENT;
  }

  private static double featOrZero(CostCard card, String k1, String k2) {
    if (card == null || card.features == null) {
      return 0.0d;
    }
    Object v = card.features.get(k1);
    if (v == null) {
      v = card.features.get(k2);
    }
    if (v instanceof Number) {
      return ((Number) v).doubleValue();
    }
    return 0.0d;
  }

  private static double log1p(double x) {
    if (x < 0.0d) {
      x = 0.0d;
    }
    return Math.log1p(x);
  }
}
