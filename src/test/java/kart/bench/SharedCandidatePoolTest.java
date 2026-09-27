package kart.bench;

import kart.cost.CostCard;
import kart.plan.PlanBuilder;
import kart.plan.PlanEnvelope;
import kart.query.QueryEngine;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SharedCandidatePoolTest {

  @Test
  void planBuilderFamilyIsDeterministicForTz() {
    kart.ir.BoundIr ir = new kart.ir.BoundIr();
    ir.query_id = "q";
    ir.temporal = new kart.ir.BoundIr.Temporal();
    ir.temporal.start_ms = 1L;
    ir.temporal.end_ms = 2L;
    ir.spatial = new kart.ir.BoundIr.Spatial();
    ir.spatial.min_x = 0;
    ir.spatial.min_y = 0;
    ir.spatial.max_x = 1;
    ir.spatial.max_y = 1;
    List<PlanEnvelope> c = PlanBuilder.buildCandidates(ir);
    List<String> ids = new ArrayList<String>();
    for (PlanEnvelope e : c) {
      ids.add(e.plan_id);
    }
    assertTrue(ids.contains("P_T"));
    assertTrue(ids.contains("P_Z"));
    assertTrue(ids.contains("P_TZ"));
    assertTrue(ids.contains("P_FULL"));
    assertEquals(ids, new ArrayList<String>(ids)); // stable list from builder
  }

  @Test
  void argminAndBaoSelectFromSamePool() {
    QueryEngine.RunResult pool = syntheticPool();
    assertTrue(SharedCandidatePool.selectArgminCost(pool));
    assertEquals("P_T", pool.selected.plan().plan_id);
    assertEquals(0.0, SharedCandidatePool.poolRegretMs(pool), 1e-9);

    Map<String, Double> w = new HashMap<String, Double>();
    w.put("Z", 100.0); // strong prior toward Z despite higher cost
    w.put("T", 1.0);
    assertTrue(SharedCandidatePool.selectBaoSurrogate(pool, w));
    assertEquals("P_Z", pool.selected.plan().plan_id);
    assertEquals(50.0, SharedCandidatePool.poolRegretMs(pool), 1e-9);
  }

  @Test
  void catalogAndPlanIdsSorted() {
    QueryEngine.RunResult pool = syntheticPool();
    List<String> ids = SharedCandidatePool.planIds(pool);
    assertEquals("P_FULL", ids.get(0));
    assertEquals("P_T", ids.get(1));
    assertEquals("P_Z", ids.get(2));
    Map<String, Object> cat = SharedCandidatePool.catalogRow(pool);
    assertEquals(Integer.valueOf(3), cat.get("n_safe"));
    assertEquals("PlanBuilder.buildCandidates+runFixed", cat.get("enumerator"));
    assertEquals(SharedCandidatePool.ENUM_VERSION, cat.get("enum_version"));
  }

  @Test
  void registryResolvesPoolArms() {
    ArmRegistry reg = new ArmRegistry(java.nio.file.Paths.get(".").toAbsolutePath().normalize());
    assertEquals("pool-cbo", reg.resolve("pool-cbo").id());
    assertEquals("pool-bao", reg.resolve("pool-bao").id());
    assertEquals("pool-conditional-llm", reg.resolve("pool-conditional-llm").id());
    assertEquals("pool-llm", reg.resolve("pool-llm").id());
  }

  @Test
  void poolLlmOutcomeLabels() {
    TrialResult ok = new TrialResult();
    SharedPoolPlanArm.stampPoolLlmOutcome(ok, true, null);
    assertEquals("pool_llm_success", ok.extras.get("pool_llm_outcome"));
    assertEquals(Boolean.FALSE, ok.extras.get("llm_fallback"));

    TrialResult fb = new TrialResult();
    SharedPoolPlanArm.stampPoolLlmOutcome(fb, false, "empty_plan_id");
    assertEquals("pool_llm_fallback", fb.extras.get("pool_llm_outcome"));
    assertEquals(Boolean.TRUE, fb.extras.get("llm_fallback"));
    assertEquals("empty_plan_id", fb.extras.get("fallback_reason"));
  }

  private static QueryEngine.RunResult syntheticPool() {
    QueryEngine.RunResult pool = new QueryEngine.RunResult();
    pool.safe.add(handle("P_T"));
    pool.safe.add(handle("P_Z"));
    pool.safe.add(handle("P_FULL"));
    pool.costCards.add(card("P_T", 100, "LOW"));
    pool.costCards.add(card("P_Z", 150, "LOW"));
    pool.costCards.add(card("P_FULL", 1000, "HIGH"));
    pool.best_safe_estimated_ms = Double.valueOf(100.0);
    pool.selected = pool.safe.get(0);
    pool.selectedCost = pool.costCards.get(0);
    return pool;
  }

  private static kart.validation.SafePlanHandle handle(String planId) {
    kart.plan.PlanEnvelope env = new kart.plan.PlanEnvelope();
    env.plan_id = planId;
    env.root = "n0";
    env.nodes = new ArrayList<kart.plan.PlanNode>();
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

  private static CostCard card(String id, double ms, String unc) {
    CostCard c = new CostCard();
    c.plan_id = id;
    c.estimated_ms = ms;
    c.uncertainty = new CostCard.Uncertainty();
    c.uncertainty.label = unc;
    return c;
  }
}
