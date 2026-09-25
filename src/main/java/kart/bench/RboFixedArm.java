package kart.bench;

import kart.ir.BoundIr;
import kart.plan.PlanBuilder;
import kart.plan.PlanEnvelope;
import kart.query.QueryEngine;
import kart.search.PlannerMode;

import java.nio.file.Path;

/**
 * Deterministic rule based optimizer baseline.  The public rule is deliberately
 * simple and fixed: TZ, then T, then Z, then H, then FULL.  No candidate
 * enumeration or cost based argmin is performed by this arm.
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
    boolean[] flags = accessFlags(hasT, hasZ, hasH);
    PlanEnvelope plan = PlanBuilder.buildForAccess(ir, flags[0], flags[1], flags[2]);
    long t0 = System.currentTimeMillis();
    QueryEngine.RunResult rr = engine.runFixed(ir, artifactDir, ctx.planOnly, plan);
    long wall = System.currentTimeMillis() - t0;
    TrialResult tr = TrialResult.fromRun(rr, wall);
    if (ctx.planOnly && rr != null && rr.t_plan_ms != null) {
      tr.t_plan_ms = rr.t_plan_ms;
    }
    tr.extras.put("baseline_protocol", "fixed_rule_template");
    tr.extras.put("candidate_search", Boolean.FALSE);
    tr.extras.put("selection", "fixed_priority_TZ_T_Z_H_FULL");
    tr.extras.put("template_plan_id", plan.plan_id);
    return tr;
  }

  /**
   * Public RBO rule: TZ → T → Z → H → FULL. Never consults CostModel.
   * @return {useT, useZ, useH}
   */
  static boolean[] accessFlags(boolean hasT, boolean hasZ, boolean hasH) {
    boolean useT = hasT;
    boolean useZ = hasZ;
    boolean useH = !useT && !useZ && hasH;
    return new boolean[] { useT, useZ, useH };
  }
}
