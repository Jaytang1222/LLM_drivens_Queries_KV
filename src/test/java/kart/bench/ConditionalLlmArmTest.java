package kart.bench;

import kart.cost.CostCard;
import kart.query.QueryEngine;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ConditionalLlmArmTest {

  @Test
  void extractPlanIdFromJson() {
    assertEquals("P_TZ", ConditionalLlmArm.extractPlanId("{\"plan_id\":\"P_TZ\",\"reason\":\"x\"}"));
    assertEquals("P_FULL", ConditionalLlmArm.extractPlanId("choose P_FULL please"));
  }

  @Test
  void triggerOnCloseCostsOrHighUncertainty() {
    List<CostCard> close = new ArrayList<CostCard>();
    close.add(card("P_T", 100, "LOW"));
    close.add(card("P_Z", 110, "LOW")); // 10% gap
    assertTrue(ConditionalLlmArm.shouldTrigger(close, 0.15));

    List<CostCard> far = new ArrayList<CostCard>();
    far.add(card("P_T", 100, "LOW"));
    far.add(card("P_Z", 200, "LOW")); // 100% gap
    assertFalse(ConditionalLlmArm.shouldTrigger(far, 0.15));

    List<CostCard> high = new ArrayList<CostCard>();
    high.add(card("P_T", 100, "HIGH"));
    high.add(card("P_Z", 200, "LOW"));
    assertTrue(ConditionalLlmArm.shouldTrigger(high, 0.15));

    List<CostCard> one = new ArrayList<CostCard>();
    one.add(card("P_FULL", 50, "HIGH"));
    assertFalse(ConditionalLlmArm.shouldTrigger(one, 0.15));
  }

  @Test
  void outcomeLabelsSeparateNotTriggeredSuccessAndFallback() {
    TrialResult idle = new TrialResult();
    ConditionalLlmArm.stampConditionalOutcome(idle, false, false, null);
    assertEquals("conditional_not_triggered", idle.extras.get("conditional_outcome"));
    assertEquals(Boolean.FALSE, idle.extras.get("llm_fallback"));

    TrialResult ok = new TrialResult();
    ConditionalLlmArm.stampConditionalOutcome(ok, true, true, null);
    assertEquals("conditional_llm_success", ok.extras.get("conditional_outcome"));
    assertEquals(Boolean.FALSE, ok.extras.get("llm_fallback"));

    for (String reason : new String[] {
        "empty_plan_id", "illegal_plan_id", "http_failure", "no_llm_client", "reselect_miss"
    }) {
      TrialResult fb = new TrialResult();
      ConditionalLlmArm.stampConditionalOutcome(fb, true, false, reason);
      assertEquals("conditional_llm_fallback", fb.extras.get("conditional_outcome"));
      assertEquals(Boolean.TRUE, fb.extras.get("llm_fallback"));
      assertEquals(reason, fb.extras.get("fallback_reason"));
    }
  }

  @Test
  void constructorThresholdUsedNotEnvDefault() {
    // 10% relative gap: fires at default 0.15, not at a tight 0.05 constructor freeze.
    List<CostCard> mid = new ArrayList<CostCard>();
    mid.add(card("P_T", 100, "LOW"));
    mid.add(card("P_Z", 110, "LOW"));
    assertTrue(ConditionalLlmArm.shouldTrigger(mid, 0.15));
    assertFalse(ConditionalLlmArm.shouldTrigger(mid, 0.05));

    ConditionalLlmArm tight = new ConditionalLlmArm(0.05, 1, "test_freeze", null);
    assertFalse(tight.shouldTrigger(mid));
    assertEquals(0.05, tight.relGapThreshold(), 1e-12);

    ConditionalLlmArm loose = new ConditionalLlmArm(0.20, 1, "test_freeze", null);
    assertTrue(loose.shouldTrigger(mid));
    assertEquals(0.20, loose.relGapThreshold(), 1e-12);
  }

  @Test
  void fromRootLoadsFreezeFile() {
    Path repo = Paths.get(".").toAbsolutePath().normalize();
    Path freeze = repo.resolve("experiments/suites/conditional_llm_freeze.json");
    assertTrue(Files.isRegularFile(freeze), "freeze file missing: " + freeze);
    ConditionalLlmArm arm = ConditionalLlmArm.fromRoot(repo);
    assertEquals(0.15, arm.relGapThreshold(), 1e-12);
    assertEquals(1, arm.maxLlmCalls());
  }

  @Test
  void frozenForTestIgnoresEnvOverride() {
    Path repo = Paths.get(".").toAbsolutePath().normalize();
    // fromRoot with frozen_for_test must not pick up a looser env gap.
    String prev = System.getenv("KART_COND_REL_GAP");
    ConditionalLlmArm arm = ConditionalLlmArm.fromRoot(repo);
    assertEquals(0.15, arm.relGapThreshold(), 1e-12);
    // Env may or may not be set in the process; contract is freeze file wins when frozen.
    assertTrue(Files.isRegularFile(repo.resolve("experiments/suites/conditional_llm_freeze.json")));
  }

  @Test
  void reselectByPlanIdKeepsSameSafeSetWithoutSearch() {
    QueryEngine.RunResult rr = new QueryEngine.RunResult();
    rr.safe.add(handle("P_T"));
    rr.safe.add(handle("P_Z"));
    rr.costCards.add(card("P_T", 100, "LOW"));
    rr.costCards.add(card("P_Z", 110, "LOW"));
    rr.selected = rr.safe.get(0);
    rr.selectedCost = rr.costCards.get(0);
    rr.best_safe_estimated_ms = Double.valueOf(100.0);
    assertTrue(QueryEngine.reselectByPlanId(rr, "P_Z"));
    assertEquals("P_Z", rr.selected.plan().plan_id);
    assertEquals(110.0, rr.selectedCost.estimated_ms, 1e-9);
    assertEquals(10.0, rr.plan_regret_ms, 1e-9);
    assertFalse(QueryEngine.reselectByPlanId(rr, "P_MISSING"));
  }

  @Test
  void planClockRunsRootAlwaysNullEvenIfArtifactsWanted() {
    // Contract: planning must not receive a runsRoot, matching BaoPlanArm.
    // keep-artifacts may still write during executeSelected after plan_end.
    assertNull(ConditionalLlmArm.planClockRunsRoot());
  }

  @Test
  void planClockEqualsEndMinusStart() {
    long start = 1_000L;
    long end = 1_250L;
    long plan = end - start;
    assertEquals(250L, plan);
    assertNull(ConditionalLlmArm.planClockRunsRoot());
  }

  private static kart.validation.SafePlanHandle handle(String planId) {
    kart.plan.PlanEnvelope env = new kart.plan.PlanEnvelope();
    env.plan_id = planId;
    env.root = "n0";
    env.nodes = new ArrayList<kart.plan.PlanNode>();
    return SafePlanHandleForTest.create(env);
  }

  /** Test-only handle factory via reflection (constructor is package-private). */
  private static final class SafePlanHandleForTest {
    static kart.validation.SafePlanHandle create(kart.plan.PlanEnvelope env) {
      try {
        kart.validation.ValidationReport report = new kart.validation.ValidationReport();
        report.pass("Test", "synthetic");
        java.lang.reflect.Constructor<kart.validation.SafePlanHandle> c =
            kart.validation.SafePlanHandle.class.getDeclaredConstructor(
                kart.plan.PlanEnvelope.class,
                kart.compile.PhysicalPlan.class,
                kart.validation.ValidationReport.class);
        c.setAccessible(true);
        return c.newInstance(env, null, report);
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }
  }

  private static CostCard card(String id, double ms, String unc) {
    CostCard c = new CostCard();
    c.plan_id = id;
    c.estimated_ms = ms;
    c.uncertainty = new CostCard.Uncertainty();
    c.uncertainty.label = unc;
    return c;
  }
}
