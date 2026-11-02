package kart.cost;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.File;
import java.util.Iterator;
import java.util.Map;

/**
 * Offline-verified cost-model correction.
 *
 * <ul>
 *   <li>{@code KART_COST_PATCH} — applied inside {@link CostModel} (affects CBO if set; leave unset for Plan A).</li>
 *   <li>{@code KART_REWRITE_COST_PATCH} — Plan A: applied only to dual <em>resource-slot</em> deltas used by
 *       gainful rewrite scoring; never touches CBO and never rewrites rule-matching features.</li>
 * </ul>
 */
public final class CostPatch {

  static final String[] TERMS = {"index", "set", "fetch", "exact", "reconstruct", "sim", "topk"};
  private static final ObjectMapper M = new ObjectMapper();
  private static final JsonNode PATCH = load(System.getenv("KART_COST_PATCH"), "KART_COST_PATCH");
  private static final JsonNode REWRITE_PATCH =
      load(System.getenv("KART_REWRITE_COST_PATCH"), "KART_REWRITE_COST_PATCH");
  /** Bench gate. Unset follows the env document; {@code false} skips {@link #apply(CostCard)}. */
  private static final ThreadLocal<Boolean> APPLY = new ThreadLocal<Boolean>();

  private CostPatch() {
  }

  static JsonNode load(String path, String label) {
    if (path == null || path.isEmpty()) {
      return null;
    }
    try {
      return M.readTree(new File(path));
    } catch (Exception e) {
      throw new IllegalStateException("bad " + label + " " + path, e);
    }
  }

  /** True when a rewrite-only cost patch file is configured. */
  public static boolean rewritePatchEnabled() {
    return REWRITE_PATCH != null && REWRITE_PATCH.path("patches").isArray()
        && REWRITE_PATCH.path("patches").size() > 0;
  }

  /** Id of the rewrite patch document (if any). */
  public static String rewritePatchDocId() {
    if (REWRITE_PATCH == null) {
      return null;
    }
    String id = REWRITE_PATCH.path("id").asText(null);
    return id != null && !id.isEmpty() ? id : "rewrite_cost_patch";
  }

  /** When false, {@link CostModel} keeps the unpatched estimate for this thread. */
  public static void setApply(boolean enabled) {
    APPLY.set(Boolean.valueOf(enabled));
  }

  public static void clearApply() {
    APPLY.remove();
  }

  static void apply(CostCard c) {
    Boolean gate = APPLY.get();
    if (gate != null && !gate.booleanValue()) {
      return;
    }
    apply(PATCH, c);
  }

  static void apply(JsonNode doc, CostCard c) {
    if (doc == null || c == null) {
      return;
    }
    for (JsonNode p : doc.path("patches")) {
      if (!holdsCard(p.path("when"), c)) {
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

  /**
   * Plan A: scale dual <em>resource</em> slot option deltas in a rewrite context using
   * {@code KART_REWRITE_COST_PATCH}. Does not modify {@code features} (rule matching stays unpatched).
   * Skips {@code access}. Returns matched patch id or null.
   */
  public static String adjustRewriteResourceSlots(ObjectNode ctx) {
    return adjustRewriteResourceSlots(ctx, REWRITE_PATCH);
  }

  /** Test / explicit-doc entry: scale resource slots using {@code doc} (Plan A semantics). */
  public static String adjustRewriteResourceSlots(ObjectNode ctx, JsonNode doc) {
    if (ctx == null || doc == null || !doc.path("patches").isArray()) {
      return null;
    }
    JsonNode feats = ctx.path("features");
    for (JsonNode p : doc.path("patches")) {
      if (!holdsFeatures(p.path("when"), feats, ctx.path("query").asText(""))) {
        continue;
      }
      JsonNode scale = p.path("scale");
      ObjectNode slots = (ObjectNode) ctx.get("slots");
      if (slots == null) {
        return p.path("id").asText(null);
      }
      scaleSlot(slots, "topk", scale.path("topk").asDouble(1.0));
      scaleSlot(slots, "fetch_batch", scale.path("fetch").asDouble(1.0));
      if (scale.has("meta")) {
        scaleSlot(slots, "meta_probe", scale.path("meta").asDouble(1.0));
      } else {
        double recon = scale.path("reconstruct").asDouble(1.0);
        double sim = scale.path("sim").asDouble(1.0);
        if (recon != 1.0d || sim != 1.0d) {
          scaleSlot(slots, "meta_probe", 0.5d * (recon + sim));
        }
      }
      scaleSlot(slots, "range_budget", scale.has("range")
          ? scale.path("range").asDouble(1.0) : scale.path("index").asDouble(1.0));
      return p.path("id").asText(null);
    }
    return null;
  }

  private static void scaleSlot(ObjectNode slots, String slotName, double factor) {
    if (slots == null || !Double.isFinite(factor) || factor == 1.0d || Math.abs(factor) < 1e-12) {
      return;
    }
    JsonNode slot = slots.get(slotName);
    if (slot == null || !slot.isObject() || !slot.has("options")) {
      return;
    }
    ObjectNode opts = (ObjectNode) slot.get("options");
    Iterator<Map.Entry<String, JsonNode>> it = opts.fields();
    while (it.hasNext()) {
      Map.Entry<String, JsonNode> e = it.next();
      JsonNode v = e.getValue();
      if (v.isNumber()) {
        opts.put(e.getKey(), v.asDouble() * factor);
      } else if (v.isObject()) {
        ObjectNode obj = (ObjectNode) v;
        Iterator<Map.Entry<String, JsonNode>> jt = obj.fields();
        while (jt.hasNext()) {
          Map.Entry<String, JsonNode> f = jt.next();
          if (f.getValue().isNumber()) {
            obj.put(f.getKey(), f.getValue().asDouble() * factor);
          }
        }
      }
    }
  }

  private static boolean holdsCard(JsonNode when, CostCard c) {
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

  /** Match rewrite-patch when-clauses against rewrite context features. */
  static boolean holdsFeatures(JsonNode when, JsonNode feats, String queryType) {
    if (when == null || !when.isArray() || when.size() == 0) {
      return true;
    }
    for (JsonNode w : when) {
      String f = w.path(0).asText();
      String op = w.path(1).asText();
      JsonNode v = w.path(2);
      if ("query".equals(f)) {
        boolean eq = v.asText().equalsIgnoreCase(queryType);
        if ("==".equals(op) != eq) {
          return false;
        }
        continue;
      }
      if (v.isNumber()) {
        double x = feats.path(f).asDouble(Double.NaN);
        double y = v.asDouble();
        boolean ok = Double.isFinite(x) && ("<".equals(op) ? x < y : "<=".equals(op) ? x <= y
            : ">".equals(op) ? x > y : ">=".equals(op) ? x >= y : "==".equals(op) ? x == y
            : "!=".equals(op) && x != y);
        if (!ok) {
          return false;
        }
      } else {
        String x = feats.path(f).asText("");
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
