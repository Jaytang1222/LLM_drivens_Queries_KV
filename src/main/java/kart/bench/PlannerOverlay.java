package kart.bench;

import kart.config.AppConfig;

import java.util.Map;

/** Apply suite/factor/param overrides onto a copied {@link AppConfig.PlannerConfig}. */
public final class PlannerOverlay {

  private PlannerOverlay() {}

  public static AppConfig.PlannerConfig apply(AppConfig.PlannerConfig base,
                                              Map<String, Object> overrides) {
    AppConfig.PlannerConfig p = base == null
        ? AppConfig.PlannerConfig.defaults() : base.copy();
    if (overrides == null || overrides.isEmpty()) {
      return p;
    }
    for (Map.Entry<String, Object> e : overrides.entrySet()) {
      String k = e.getKey();
      Object v = e.getValue();
      if (k == null || v == null) {
        continue;
      }
      if ("beam_width".equals(k)) {
        p.beam_width = asInt(v, p.beam_width);
      } else if ("max_llm_calls".equals(k)) {
        p.max_llm_calls = asInt(v, p.max_llm_calls);
      } else if ("max_candidates".equals(k)) {
        p.max_candidates = asInt(v, p.max_candidates);
      } else if ("max_plan_ms".equals(k)) {
        p.max_plan_ms = asLong(v, p.max_plan_ms);
      } else if ("max_zorder_ranges".equals(k)) {
        p.max_zorder_ranges = asInt(v, p.max_zorder_ranges);
      } else if ("fetch_batch_size".equals(k)) {
        p.fetch_batch_size = asInt(v, p.fetch_batch_size);
      } else if ("stagnation_steps".equals(k)) {
        p.stagnation_steps = asInt(v, p.stagnation_steps);
      } else if ("max_candidate_chunks".equals(k)) {
        p.max_candidate_chunks = asLong(v, p.max_candidate_chunks);
      }
      // policy overrides handled by SuiteRunner (arm selection), not planner fields
    }
    return p;
  }

  private static int asInt(Object v, int def) {
    if (v instanceof Number) {
      return ((Number) v).intValue();
    }
    try {
      return Integer.parseInt(String.valueOf(v));
    } catch (Exception e) {
      return def;
    }
  }

  private static long asLong(Object v, long def) {
    if (v instanceof Number) {
      return ((Number) v).longValue();
    }
    try {
      return Long.parseLong(String.valueOf(v));
    } catch (Exception e) {
      return def;
    }
  }
}
