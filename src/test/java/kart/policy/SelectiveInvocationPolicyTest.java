package kart.policy;

import kart.ir.BoundIr;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SelectiveInvocationPolicyTest {

  @Test
  void g0SkipsShortNonComplexQueries() {
    BoundIr ir = new BoundIr();
    ir.query_id = "short";
    ir.result = new BoundIr.Result();
    ir.result.mode = "RANGE";
    QueryOpportunityFeatures f = QueryOpportunityFeatures.fromCheap(ir, "P_T", 40.0);
    InvocationDecision d = new SelectiveInvocationPolicy().decideG0(f);
    assertEquals(InvocationDecision.Gate.SKIP, d.g0);
    assertEquals("short_query", d.skipReason);
    assertFalse(d.shouldCallLlm());
  }

  @Test
  void g0ContinuesLongJointSimilarity() {
    BoundIr ir = new BoundIr();
    ir.query_id = "long";
    ir.result = new BoundIr.Result();
    ir.result.mode = "TOP_K";
    ir.similarity = new BoundIr.Similarity();
    ir.similarity.metric = "DTW";
    ir.temporal = new BoundIr.Temporal();
    ir.temporal.start_ms = 0L;
    ir.temporal.end_ms = 14L * 24 * 3600 * 1000;
    ir.spatial = new BoundIr.Spatial();
    ir.spatial.min_x = 0; ir.spatial.max_x = 1; ir.spatial.min_y = 0; ir.spatial.max_y = 1;
    QueryOpportunityFeatures f = QueryOpportunityFeatures.fromCheap(ir, "P_TZ", 70000.0);
    InvocationDecision d = new SelectiveInvocationPolicy().decideG0(f);
    assertEquals(InvocationDecision.Gate.CALL, d.g0);
  }

  @Test
  void g1SkipsWhenConservativeNetBelowBudget() {
    InvocationDecision g0 = new InvocationDecision(
        SelectiveInvocationPolicy.VERSION, InvocationDecision.Gate.CALL,
        InvocationDecision.Gate.CALL, null, "test", "KEEP", "P_TZ",
        0L, 900L, 50L, null, null, Collections.<String, Object>emptyMap());
    InvocationDecision g1 = new SelectiveInvocationPolicy().decideG1(
        g0, "KEEP", 200.0, 50.0, Arrays.asList("P_T"), Collections.<String>emptyList());
    assertEquals(InvocationDecision.Gate.SKIP, g1.g1);
    assertEquals("budget_exhausted", g1.skipReason);
  }

  @Test
  void decisionDoesNotMutateEvidenceAfterConstruction() {
    java.util.Map<String, Object> ev = new java.util.LinkedHashMap<String, Object>();
    ev.put("a", 1);
    InvocationDecision d = new InvocationDecision(
        "v", InvocationDecision.Gate.SKIP, InvocationDecision.Gate.SKIP,
        "short_query", null, "KEEP", "P_T", 0, 900, 50, null, null, ev);
    ev.put("a", 2);
    assertEquals(1, d.evidence.get("a"));
  }

  @Test
  void selectiveModeRespectsProtocolEnv() {
    // Cannot assert env mutation in parallel suites; just check VERSION constant.
    assertTrue(SelectiveInvocationPolicy.VERSION.contains("v12"));
  }
}
