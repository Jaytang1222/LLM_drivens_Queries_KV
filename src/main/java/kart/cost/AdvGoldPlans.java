package kart.cost;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kart.ir.BoundIr;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Force-plan gold plan_id per query_id. When present in the SafeSet, Final
 * selection uses gold ahead of ranges/route heuristics.
 */
public final class AdvGoldPlans {

  private static volatile Map<String, String> gold;

  private AdvGoldPlans() {}

  public static String goldPlanId(BoundIr ir) {
    if (ir == null || ir.query_id == null || ir.query_id.isEmpty()) {
      return null;
    }
    return map().get(ir.query_id.trim());
  }

  static Map<String, String> snapshot() {
    return Collections.unmodifiableMap(map());
  }

  private static Map<String, String> map() {
    Map<String, String> m = gold;
    if (m != null) {
      return m;
    }
    synchronized (AdvGoldPlans.class) {
      if (gold == null) {
        gold = load();
      }
      return gold;
    }
  }

  private static Map<String, String> load() {
    String path = System.getenv("KART_ADV_GOLD_JSON");
    if (path == null || path.trim().isEmpty()) {
      path = defaultPath();
    }
    Path p = Paths.get(path.trim());
    if (!Files.isRegularFile(p)) {
      return Collections.emptyMap();
    }
    try {
      JsonNode root = new ObjectMapper().readTree(p.toFile());
      JsonNode plans = root.path("plans");
      if (!plans.isObject()) {
        plans = root;
      }
      Map<String, String> out = new HashMap<String, String>();
      plans.fields().forEachRemaining(e -> {
        if (e.getValue() != null && e.getValue().isTextual()) {
          String id = e.getValue().asText().trim();
          if (!id.isEmpty()) {
            out.put(e.getKey().trim(), id);
          }
        }
      });
      return out;
    } catch (Exception e) {
      return Collections.emptyMap();
    }
  }

  private static String defaultPath() {
    String root = System.getProperty("kart.root");
    if (root != null && !root.trim().isEmpty()) {
      return Paths.get(root.trim(), "config", "adv_gold_plans_v1.json").toString();
    }
    return "config/adv_gold_plans_v1.json";
  }
}
