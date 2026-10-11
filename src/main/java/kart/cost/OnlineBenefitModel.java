package kart.cost;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Paths;

/** Optional development model. Same FastCost features offline and online. */
public final class OnlineBenefitModel {
  private static final OnlineBenefitModel INSTANCE = load();
  private final double[] coefficients;
  private final double intercept;
  private final boolean linear;
  public final double marginMs;
  private OnlineBenefitModel(double[] c, double intercept, double m, boolean linear) {
    coefficients = c;
    this.intercept = intercept;
    marginMs = m;
    this.linear = linear;
  }
  public static OnlineBenefitModel get() { return INSTANCE; }
  private static OnlineBenefitModel load() {
    String path = System.getenv("KART_ONLINE_BENEFIT_MODEL");
    if (path == null || path.trim().isEmpty()) return null;
    try {
      JsonNode j = new ObjectMapper().readTree(Paths.get(path).toFile());
      if (j.path("training_queries").asInt() < 20) {
        throw new IllegalArgumentException("insufficient training queries");
      }
      // OLS may yield a negative index coeff; do not NNLS-clamp to 0.
      double[] c = new double[3];
      for (int i = 0; i < 3; i++) {
        c[i] = j.path("coefficients").get(i).asDouble();
        if (!Double.isFinite(c[i])) throw new IllegalArgumentException("invalid coefficient");
      }
      double intercept = j.path("intercept").asDouble(0.0d);
      if (!Double.isFinite(intercept)) throw new IllegalArgumentException("invalid intercept");
      double margin = j.path("margin_ms").asDouble(Double.NaN);
      if (!Double.isFinite(margin) || margin < 0) throw new IllegalArgumentException("invalid margin");
      String basis = j.path("basis").asText("log");
      if (!"linear".equals(basis) && !"log".equals(basis)) {
        throw new IllegalArgumentException("unknown basis");
      }
      return new OnlineBenefitModel(c, intercept, margin, "linear".equals(basis));
    } catch (Exception e) {
      throw new IllegalStateException("Invalid online benefit model: " + path, e);
    }
  }
  public double predict(CostCard card) {
    double[] t = terms(card);
    return t == null ? Double.NaN : intercept + t[0] + t[1] + t[2];
  }

  /** Per-feature contributions in ms: {ranges, fetch, index rows}; null if unusable. */
  public double[] terms(CostCard card) {
    String[][] keys = {
        {"scan_ranges", "seek_ranges"},
        {"fetch_gets", "estimated_candidate_chunks"},
        {"estimated_index_rows", "decode_rows"}
    };
    if (card == null || card.features == null) return null;
    double[] out = new double[3];
    for (int i = 0; i < 3; i++) {
      Object v = card.features.get(keys[i][0]);
      if (v == null) v = card.features.get(keys[i][1]);
      if (!(v instanceof Number)) return null;
      double d = ((Number) v).doubleValue();
      if (!Double.isFinite(d) || d < 0) return null;
      out[i] = coefficients[i] * (linear ? d / 1000.0 : Math.log1p(d));
    }
    return out;
  }
}
