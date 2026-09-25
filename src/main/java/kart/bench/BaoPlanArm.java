package kart.bench;

import kart.ir.BoundIr;
import kart.query.QueryEngine;
import kart.search.PlannerMode;
import kart.validation.SafePlanHandle;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Bao select_plan protocol over KART SafePlan family.
 *
 * Upstream: {@code bao_server/main.py BaoModel.select_plan}:
 *   messages = [*arms, buffers]; if no model → PG_OPTIMIZER_INDEX (0);
 *   else predict reward per arm and {@code argmin}.
 *
 * Transplant:
 *   arms = RULE-enumerated SafePlan plan_ids (stable order by plan_id);
 *   reward surrogate = estimated_ms from CostCard (lower better), optional family
 *   prior from {@code kart_plan_family_weights.json} as additive bias only;
 *   select {@code argmin(reward)} — same decision rule as Bao.
 *
 * Does NOT use PostgreSQL, Bao tree-CNN features, or IMDb/JOB weights.
 * Candidate search runs once; the chosen SafePlan is executed without a second beam.
 */
public final class BaoPlanArm implements Arm {

  /** Mirrors bao_server/constants.py PG_OPTIMIZER_INDEX. */
  private static final int PG_OPTIMIZER_INDEX = 0;

  private final Map<String, Double> familyWeight;
  private final boolean weightsFilePresent;

  public BaoPlanArm(Path root) {
    Path wp = root.resolve("experiments/adapters/bao/kart_plan_family_weights.json");
    this.weightsFilePresent = Files.isRegularFile(wp);
    this.familyWeight = loadWeights(wp);
  }

  @Override
  public String id() {
    return "bao";
  }

  @Override
  public TrialResult run(BoundIr ir, BenchContext ctx) throws Exception {
    QueryEngine engine = ctx.newEngine(PlannerMode.RULE);
    Path art = ctx.artifactDir(id(), ir.query_id);
    long t0 = System.currentTimeMillis();
    QueryEngine.RunResult probe = engine.run(ir, null, true, null);
    if (probe == null || probe.safe == null || probe.safe.isEmpty()) {
      TrialResult fail = TrialResult.fail("bao: no safe plans");
      fail.t_plan_ms = Long.valueOf(Math.max(0L, System.currentTimeMillis() - t0));
      fail.applyWrapperPlanClock(t0, fail.t_plan_ms.longValue());
      return fail;
    }

    List<String> arms = new ArrayList<String>();
    Map<String, Double> costByPid = new HashMap<String, Double>();
    Map<String, SafePlanHandle> handleByPid = new HashMap<String, SafePlanHandle>();
    for (SafePlanHandle h : probe.safe) {
      if (h == null || h.plan() == null || h.plan().plan_id == null) {
        continue;
      }
      String pid = h.plan().plan_id;
      if (!arms.contains(pid)) {
        arms.add(pid);
      }
      handleByPid.put(pid, h);
    }
    Collections.sort(arms);
    if (probe.costCards != null) {
      for (kart.cost.CostCard c : probe.costCards) {
        if (c != null && c.plan_id != null) {
          costByPid.put(c.plan_id, Double.valueOf(c.estimated_ms));
        }
      }
    }
    if (arms.isEmpty()) {
      TrialResult fail = TrialResult.fail("bao: empty arm list");
      fail.t_plan_ms = Long.valueOf(Math.max(0L, System.currentTimeMillis() - t0));
      return fail;
    }

    boolean haveCostSignal = !costByPid.isEmpty();
    int chosenIdx;
    String selectMode;
    if (!haveCostSignal && !weightsFilePresent) {
      chosenIdx = Math.min(PG_OPTIMIZER_INDEX, arms.size() - 1);
      selectMode = "no_model_PG_OPTIMIZER_INDEX";
    } else {
      double best = Double.POSITIVE_INFINITY;
      chosenIdx = 0;
      for (int i = 0; i < arms.size(); i++) {
        String pid = arms.get(i);
        double cost = costByPid.containsKey(pid)
            ? costByPid.get(pid).doubleValue()
            : 1.0e12;
        double prior = weightFor(pid);
        double reward = cost / Math.max(0.1, prior);
        if (reward < best) {
          best = reward;
          chosenIdx = i;
        }
      }
      selectMode = "surrogate_reward_argmin";
    }
    String chosen = arms.get(chosenIdx);
    SafePlanHandle chosenHandle = handleByPid.get(chosen);
    if (chosenHandle != null) {
      probe.selected = chosenHandle;
    }
    if (probe.costCards != null) {
      for (kart.cost.CostCard c : probe.costCards) {
        if (c != null && chosen.equals(c.plan_id)) {
          probe.selectedCost = c;
          break;
        }
      }
    }
    long tPlanEnd = System.currentTimeMillis();
    long tPlan = Math.max(0L, tPlanEnd - t0);
    probe.t_plan_ms = Long.valueOf(tPlan);
    probe.planStartEpochMs = t0;
    probe.planEndEpochMs = tPlanEnd;
    probe.t_exec_ms = null;

    QueryEngine.RunResult rr = probe;
    long wall = tPlan;
    if (!ctx.planOnly) {
      rr = engine.executeSelected(ir, art, probe);
      wall = Math.max(0L, System.currentTimeMillis() - t0);
    }

    TrialResult tr = TrialResult.fromRun(rr, wall);
    tr.t_plan_ms = Long.valueOf(tPlan);
    tr.extras.put("plan_start_ms", Long.valueOf(t0));
    tr.extras.put("plan_end_ms", Long.valueOf(tPlanEnd));
    if (tr.t_exec_ms != null) {
      tr.t_e2e_ms = Long.valueOf(tPlan + tr.t_exec_ms.longValue());
    } else {
      tr.t_e2e_ms = Long.valueOf(tPlan);
    }
    tr.extras.put("bao_protocol", "bao_server.select_plan");
    tr.extras.put("bao_select_mode", selectMode);
    tr.extras.put("bao_chosen", chosen);
    tr.extras.put("bao_chosen_index", Integer.valueOf(chosenIdx));
    tr.extras.put("bao_n_arms", Integer.valueOf(arms.size()));
    tr.extras.put("bao_pg_weights", Boolean.FALSE);
    tr.extras.put("bao_second_search", Boolean.FALSE);
    tr.extras.put("bao_upstream", "experiments/third_party/bao/bao_server/main.py");
    tr.extras.put("bao_intentional_changes",
        "KART SafePlan arms + CostCard surrogate reward; no PG tree-CNN / IMDb weights");
    return tr;
  }

  private double weightFor(String planId) {
    String fam = familyOf(planId);
    Double w = familyWeight.get(fam);
    if (w != null) {
      return w.doubleValue();
    }
    if ("P_FULL".equals(planId)) {
      return 0.1;
    }
    return 1.0;
  }

  private static String familyOf(String planId) {
    if (planId == null) {
      return "other";
    }
    if (planId.startsWith("P_FULL")) {
      return "FULL";
    }
    if (planId.startsWith("P_TZH") || planId.contains("TZ") || planId.contains("TH")
        || planId.contains("ZH")) {
      return "COMBO";
    }
    if (planId.startsWith("P_T")) {
      return "T";
    }
    if (planId.startsWith("P_Z")) {
      return "Z";
    }
    if (planId.startsWith("P_H")) {
      return "H";
    }
    return "other";
  }

  private static Map<String, Double> loadWeights(Path path) {
    Map<String, Double> m = new HashMap<String, Double>();
    m.put("T", 3.0);
    m.put("Z", 2.5);
    m.put("H", 2.0);
    m.put("COMBO", 2.2);
    m.put("FULL", 0.2);
    m.put("other", 1.0);
    if (!Files.isRegularFile(path)) {
      return m;
    }
    try {
      com.fasterxml.jackson.databind.JsonNode root =
          new com.fasterxml.jackson.databind.ObjectMapper().readTree(path.toFile());
      if (root.has("weights")) {
        root = root.get("weights");
      }
      java.util.Iterator<String> it = root.fieldNames();
      while (it.hasNext()) {
        String k = it.next();
        m.put(k, Double.valueOf(root.get(k).asDouble()));
      }
    } catch (Exception ignore) {
      // defaults
    }
    return m;
  }
}
