package kart.cost;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;

/**
 * Offline-verified cost-model correction ({@code KART_COST_PATCH}): the first patch whose {@code when}
 * holds re-weights the CostCard terms and adds feature-proportional terms.
 */
public final class CostPatch {

  static final String[] TERMS = {"index", "set", "fetch", "exact", "reconstruct", "sim", "topk"};
  private static final JsonNode PATCH = load(System.getenv("KART_COST_PATCH"));

  private CostPatch() {
  }

  static JsonNode load(String path) {
    if (path == null || path.isEmpty()) {
      return null;
    }
    try {
      return new ObjectMapper().readTree(new File(path));
    } catch (Exception e) {
      throw new IllegalStateException("bad KART_COST_PATCH " + path, e);
    }
  }

  static void apply(CostCard c) {
    apply(PATCH, c);
  }

  static void apply(JsonNode doc, CostCard c) {
    if (doc == null) {
      return;
    }
    for (JsonNode p : doc.path("patches")) {
      if (!holds(p.path("when"), c)) {
        continue;
      }
      double est = 0;
      for (String t : TERMS) {
        est += p.path("scale").path(t).asDouble(1.0) * term(c, t);
      }
      for (JsonNode a : p.path("add")) {
        double v = num(c, a.path("feature").asText());
        est += Double.isNaN(v) ? 0.0 : a.path("coeff").asDouble() * v;
      }
      c.features.put("cost_patch", p.path("id").asText());
      c.features.put("unpatched_ms", Double.valueOf(c.estimated_ms));
      c.estimated_ms = Math.max(0.0, est);
      return;
    }
  }

  private static boolean holds(JsonNode when, CostCard c) {
    for (JsonNode w : when) {
      String f = w.path(0).asText();
      String op = w.path(1).asText();
      JsonNode v = w.path(2);
      if (v.isNumber()) {
        double x = num(c, f);
        double y = v.asDouble();
        boolean ok = "<".equals(op) ? x < y : "<=".equals(op) ? x <= y : ">".equals(op) ? x > y
            : ">=".equals(op) ? x >= y : "==".equals(op) ? x == y : "!=".equals(op) && x != y;
        if (!ok) {
          return false;
        }
      } else {
        String x = "plan_id".equals(f) ? c.plan_id : String.valueOf(c.features.get(f));
        boolean eq = v.asText().equalsIgnoreCase(x);
        if ("==".equals(op) != eq) {
          return false;
        }
      }
    }
    return true;
  }

  private static double term(CostCard c, String t) {
    switch (t) {
      case "index": return c.l_hat_index;
      case "set": return c.l_hat_set;
      case "fetch": return c.l_hat_fetch;
      case "exact": return c.l_hat_exact;
      case "reconstruct": return c.l_hat_reconstruct;
      case "sim": return c.l_hat_sim;
      default: return c.l_hat_topk;
    }
  }

  private static double num(CostCard c, String f) {
    Object v = c.features.get(f);
    return v instanceof Number ? ((Number) v).doubleValue() : Double.NaN;
  }
}
