package kart.plan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kart.compile.LayoutContext;
import kart.compile.PhysicalPlan;
import kart.compile.QueryCompiler;
import kart.ir.BoundIr;
import kart.query.QueryIrFixtureTest;
import kart.snapshot.FixtureBuilder;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2 stage gate: PlanEnvelope / BoundIr JSON must not leak physical rowkey fields.
 * PhysicalPlan may contain hex start/stop bytes.
 */
class LogicalJsonNoPhysicalKeysTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static final Set<String> FORBIDDEN = new HashSet<String>(Arrays.asList(
      "startrow", "start_row", "stoprow", "stop_row",
      "rowkey", "row_key", "startbytes", "stopbytes"
  ));

  @Test
  void planEnvelopeAndBoundIrHaveNoPhysicalKeys() throws Exception {
    BoundIr ir = QueryIrFixtureTest.fixtureQuery();
    String boundJson = MAPPER.writeValueAsString(ir);
    assertNoForbiddenKeys(MAPPER.readTree(boundJson), "BoundIr");

    List<PlanEnvelope> plans = PlanBuilder.buildCandidates(ir);
    assertFalse(plans.isEmpty());
    for (PlanEnvelope env : plans) {
      assertNoForbiddenKeys(MAPPER.readTree(env.toJson()), "PlanEnvelope " + env.plan_id);
    }

    // Physical plan is allowed to carry hex rowkeys — just smoke that compile works.
    LayoutContext layout = LayoutContext.from(FixtureBuilder.fixtureManifest());
    PhysicalPlan phys = new QueryCompiler(layout).compile(plans.get(0), ir);
    String physJson = MAPPER.writeValueAsString(phys);
    assertTrue(physJson.contains("start") || physJson.contains("hex") || physJson.length() > 10);
  }

  static void assertNoForbiddenKeys(JsonNode node, String label) {
    List<String> hits = new ArrayList<String>();
    walk(node, "", hits);
    assertTrue(hits.isEmpty(), label + " has forbidden physical keys: " + hits);
  }

  private static void walk(JsonNode node, String path, List<String> hits) {
    if (node == null || node.isNull()) {
      return;
    }
    if (node.isObject()) {
      Iterator<Map.Entry<String, JsonNode>> it = node.fields();
      while (it.hasNext()) {
        Map.Entry<String, JsonNode> e = it.next();
        String key = e.getKey();
        String lower = key.toLowerCase(Locale.ROOT);
        if (FORBIDDEN.contains(lower) || looksLikeHexRowkeyKey(key)) {
          hits.add(path.isEmpty() ? key : path + "." + key);
        }
        walk(e.getValue(), path.isEmpty() ? key : path + "." + key, hits);
      }
    } else if (node.isArray()) {
      for (int i = 0; i < node.size(); i++) {
        walk(node.get(i), path + "[" + i + "]", hits);
      }
    }
  }

  /** Accidental hex rowkey field names (long hex strings used as object keys). */
  private static boolean looksLikeHexRowkeyKey(String key) {
    if (key == null || key.length() < 16 || (key.length() % 2) != 0) {
      return false;
    }
    for (int i = 0; i < key.length(); i++) {
      char c = key.charAt(i);
      boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
      if (!hex) {
        return false;
      }
    }
    return true;
  }
}
