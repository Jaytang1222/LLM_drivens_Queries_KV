package kart.search;

import kart.ir.BoundIr;
import kart.plan.PlanBuilder;
import kart.plan.PlanEnvelope;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

public final class LlmDirectPlanPlannerTest {

  @Test
  void parsePlanIdFromJson() {
    assertEquals("P_TZ", LlmDirectPlanPlanner.parsePlanId(
        "{\"response_version\":\"1.0\",\"plan_id\":\"P_TZ\"}"));
  }

  @Test
  void parsePlanIdRejectsGarbage() {
    assertNull(LlmDirectPlanPlanner.parsePlanId("not json"));
    assertNull(LlmDirectPlanPlanner.parsePlanId("{}"));
    assertNull(LlmDirectPlanPlanner.parsePlanId(null));
  }

  @Test
  void parseFullPlanEnvelope() throws Exception {
    BoundIr ir = new BoundIr();
    ir.query_id = "q";
    ir.snapshot = new BoundIr.Snapshot();
    ir.snapshot.manifest_id = "m";
    ir.temporal = new BoundIr.Temporal();
    ir.temporal.start_ms = 0;
    ir.temporal.end_ms = 1;
    ir.spatial = new BoundIr.Spatial();
    ir.spatial.min_x = 0;
    ir.spatial.min_y = 0;
    ir.spatial.max_x = 1;
    ir.spatial.max_y = 1;
    ir.result = new BoundIr.Result();
    ir.result.mode = "TRAJECTORY_IDS";
    PlanEnvelope env = PlanBuilder.buildForAccess(ir, true, true, false);
    String json = "{\"response_version\":\"1.0\",\"plan\":" + env.toJson() + "}";
    LlmDirectPlanPlanner.DirectChoice c = LlmDirectPlanPlanner.parseChoice(json, null, ir);
    assertNotNull(c.envelope);
    assertEquals("llm_direct_envelope", c.reason);
    assertEquals(env.plan_id, c.envelope.plan_id);
  }

  @Test
  void plannerModeParseAliases() {
    assertEquals(PlannerMode.BEST_FIRST, PlannerMode.parse("best-first"));
    assertEquals(PlannerMode.LLM_DIRECT, PlannerMode.parse("direct"));
    assertEquals(PlannerMode.RULE, PlannerMode.parse("rbo"));
  }
}
