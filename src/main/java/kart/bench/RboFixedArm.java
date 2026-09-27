package kart.bench;

import kart.ir.BoundIr;
import kart.plan.PlanBuilder;
import kart.plan.PlanEnvelope;
import kart.query.QueryEngine;
import kart.search.PlannerMode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic rule based optimizer baseline.  The public rule is deliberately
 * simple and fixed: TZ, then T, then Z, then H, then FULL.  No candidate
 * enumeration or cost based argmin is performed by this arm.
 *
 * <p>If an earlier template is rejected by the shared validator (e.g. empty
 * pre-epoch time coverage), the arm advances to the next template in the same
 * published order — still without consulting CostModel.
 */
public final class RboFixedArm implements Arm {

  @Override
  public String id() {
    return "rbo";
  }

  @Override
  public TrialResult run(BoundIr ir, BenchContext ctx) throws Exception {
    QueryEngine engine = ctx.newEngine(PlannerMode.RULE);
    Path artifactDir = ctx.artifactDir(id(), ir.query_id);
    boolean hasT = ir.temporal != null;
    boolean hasZ = ir.spatial != null;
    boolean hasH = PlanBuilder.vehicleEqPredicateIndex(ir) >= 0;
    List<boolean[]> templates = templatePriority(hasT, hasZ, hasH);
    List<String> rejected = new ArrayList<String>();
    List<Map<String, Object>> rejectedTrace = new ArrayList<Map<String, Object>>();
    long planStart = System.currentTimeMillis();
    long planAcc = 0L;
    QueryEngine.RunResult rr = null;
    PlanEnvelope chosen = null;
    for (boolean[] flags : templates) {
      PlanEnvelope plan = PlanBuilder.buildForAccess(ir, flags[0], flags[1], flags[2]);
      // Rejected templates return before artifact IO. Accepted t_plan_ms is stamped
      // before writeArtifacts, so summing t_plan_ms excludes artifact time.
      QueryEngine.RunResult attempt = engine.runFixed(ir, artifactDir, ctx.planOnly, plan);
      long attemptPlan = attempt == null || attempt.t_plan_ms == null
          ? 0L : attempt.t_plan_ms.longValue();
      planAcc += attemptPlan;
      if (attempt != null && attempt.result != null
          && "NO_SAFE_PLAN".equals(attempt.result.status)) {
        rejected.add(plan.plan_id);
        rejectedTrace.add(rejectedAttempt(plan.plan_id, attemptPlan, attempt.result.error));
        continue;
      }
      rr = attempt;
      chosen = plan;
      break;
    }
    if (rr == null) {
      PlanEnvelope full = PlanBuilder.buildForAccess(ir, false, false, false);
      rr = engine.runFixed(ir, artifactDir, ctx.planOnly, full);
      long attemptPlan = rr == null || rr.t_plan_ms == null ? 0L : rr.t_plan_ms.longValue();
      planAcc += attemptPlan;
      chosen = full;
      if (rr != null && rr.result != null && "NO_SAFE_PLAN".equals(rr.result.status)) {
        rejected.add(full.plan_id);
        rejectedTrace.add(rejectedAttempt(full.plan_id, attemptPlan, rr.result.error));
      }
    }
    long wall = System.currentTimeMillis() - planStart;
    TrialResult tr = TrialResult.fromRun(rr, wall);
    applyPlanClock(tr, planStart, planAcc);
    tr.extras.put("baseline_protocol", "fixed_rule_template");
    tr.extras.put("candidate_search", Boolean.FALSE);
    tr.extras.put("selection", "fixed_priority_TZ_T_Z_H_FULL");
    tr.extras.put("template_plan_id", chosen != null ? chosen.plan_id : null);
    tr.extras.put("rbo_plan_includes_rejected_templates", Boolean.TRUE);
    if (!rejected.isEmpty()) {
      tr.extras.put("rbo_rejected_templates", rejected);
      tr.extras.put("rbo_rejected_trace", rejectedTrace);
    }
    return tr;
  }

  /**
   * Cover every template attempt. {@code t_e2e = t_plan + t_exec} when executed;
   * plan-only keeps {@code t_e2e == t_plan}.
   */
  static void applyPlanClock(TrialResult tr, long planStartEpochMs, long planMs) {
    long plan = Math.max(0L, planMs);
    tr.t_plan_ms = Long.valueOf(plan);
    tr.extras.put("plan_start_ms", Long.valueOf(planStartEpochMs));
    tr.extras.put("plan_end_ms", Long.valueOf(planStartEpochMs + plan));
    if (tr.t_exec_ms != null) {
      tr.t_e2e_ms = Long.valueOf(plan + tr.t_exec_ms.longValue());
    } else {
      tr.t_e2e_ms = Long.valueOf(plan);
    }
  }

  static long sumAttemptPlanMs(long... attemptPlanMs) {
    long sum = 0L;
    if (attemptPlanMs == null) {
      return 0L;
    }
    for (long v : attemptPlanMs) {
      sum += Math.max(0L, v);
    }
    return sum;
  }

  private static Map<String, Object> rejectedAttempt(String planId, long planMs, String reason) {
    Map<String, Object> row = new LinkedHashMap<String, Object>();
    row.put("plan_id", planId);
    row.put("t_plan_ms", Long.valueOf(Math.max(0L, planMs)));
    row.put("reason", reason == null ? "NO_SAFE_PLAN" : reason);
    return row;
  }

  /**
   * Preferred first template only (TZ → T → Z → H → FULL). Used by unit tests;
   * runtime walks {@link #templatePriority} when earlier templates are unsafe.
   * @return {useT, useZ, useH}
   */
  static boolean[] accessFlags(boolean hasT, boolean hasZ, boolean hasH) {
    return templatePriority(hasT, hasZ, hasH).get(0);
  }

  /**
   * Public RBO order: TZ → T → Z → H → FULL. Never consults CostModel.
   * Each entry is {useT, useZ, useH}; all-false is {@code P_FULL}.
   */
  static List<boolean[]> templatePriority(boolean hasT, boolean hasZ, boolean hasH) {
    List<boolean[]> out = new ArrayList<boolean[]>();
    if (hasT && hasZ) {
      out.add(new boolean[] { true, true, false });
    }
    if (hasT) {
      out.add(new boolean[] { true, false, false });
    }
    if (hasZ) {
      out.add(new boolean[] { false, true, false });
    }
    if (hasH) {
      out.add(new boolean[] { false, false, true });
    }
    out.add(new boolean[] { false, false, false });
    return out;
  }
}
