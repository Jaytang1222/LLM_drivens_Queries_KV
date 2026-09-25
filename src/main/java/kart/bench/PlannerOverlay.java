package kart.bench;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import kart.config.AppConfig;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

/** Apply suite/factor/param overrides onto a copied {@link AppConfig.PlannerConfig}. */
public final class PlannerOverlay {

  public static final String UNCALIBRATED_COEFFS = "config/cost_coeffs_uncalibrated.yaml";

  private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

  private PlannerOverlay() {}

  public static AppConfig.PlannerConfig apply(AppConfig.PlannerConfig base,
                                              Map<String, Object> overrides) {
    return apply(base, overrides, null);
  }

  public static AppConfig.PlannerConfig apply(AppConfig.PlannerConfig base,
                                              Map<String, Object> overrides,
                                              Path root) {
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
      } else if ("use_fast_cost".equals(k)) {
        p.use_fast_cost = asBool(v, p.use_fast_cost);
      } else if ("allow_intersect".equals(k) || "single_index".equals(k)) {
        if ("single_index".equals(k)) {
          p.allow_intersect = !asBool(v, !p.allow_intersect);
        } else {
          p.allow_intersect = asBool(v, p.allow_intersect);
        }
      } else if ("skip_coverage_check".equals(k) || "skip_coverage".equals(k)) {
        p.skip_coverage_check = asBool(v, p.skip_coverage_check);
      } else if ("final_select".equals(k)) {
        p.final_select = String.valueOf(v).trim();
      } else if ("uncalibrated".equals(k)) {
        if (asBool(v, false)) {
          p.cost = loadUncalibrated(root);
        }
      } else if ("cost_coeffs".equals(k) || "cost_coeffs_path".equals(k)) {
        p.cost = loadCostCoeffs(root, String.valueOf(v));
      }
      // policy / arm overrides handled by SuiteRunner (arm selection)
    }
    return p;
  }

  public static Path uncalibratedPath(Path root) {
    Path base = root != null ? root : Paths.get(".").toAbsolutePath().normalize();
    return base.resolve(UNCALIBRATED_COEFFS);
  }

  public static AppConfig.CostCoeffs loadUncalibrated(Path root) {
    return loadCostCoeffs(root, UNCALIBRATED_COEFFS);
  }

  private static AppConfig.CostCoeffs loadCostCoeffs(Path root, String rel) {
    Path p = Paths.get(rel);
    if (!p.isAbsolute()) {
      Path base = root != null ? root : Paths.get(".").toAbsolutePath().normalize();
      p = base.resolve(rel);
    }
    if (!Files.isRegularFile(p)) {
      throw new IllegalStateException("cost coeff file not found: " + p);
    }
    try {
      AppConfig.CostCoeffs c = YAML.readValue(p.toFile(), AppConfig.CostCoeffs.class);
      if (c == null) {
        throw new IllegalStateException("empty cost coeffs: " + p);
      }
      return c;
    } catch (IllegalStateException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("failed to load cost coeffs: " + p, e);
    }
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

  private static boolean asBool(Object v, boolean def) {
    if (v instanceof Boolean) {
      return ((Boolean) v).booleanValue();
    }
    String s = String.valueOf(v).trim().toLowerCase();
    if ("true".equals(s) || "yes".equals(s) || "1".equals(s)) {
      return true;
    }
    if ("false".equals(s) || "no".equals(s) || "0".equals(s)) {
      return false;
    }
    return def;
  }
}
