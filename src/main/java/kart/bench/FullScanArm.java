package kart.bench;

import kart.ir.BoundIr;
import kart.plan.PlanBuilder;
import kart.plan.PlanEnvelope;
import kart.query.QueryEngine;
import kart.search.PlannerMode;

import java.nio.file.Path;

/**
 * HBase primary-table baseline: one fixed P_FULL plan, no candidate search or
 * cost argmin.  Exact filtering and Top-K remain the common KART executor.
 */
public final class FullScanArm implements Arm {

  @Override
  public String id() {
    return "fullscan";
  }

  @Override
  public TrialResult run(BoundIr ir, BenchContext ctx) throws Exception {
    QueryEngine engine = ctx.newEngine(PlannerMode.RULE);
    Path artifactDir = ctx.artifactDir(id(), ir.query_id);
    PlanEnvelope plan = PlanBuilder.buildForAccess(ir, false, false, false);
    long t0 = System.currentTimeMillis();
    QueryEngine.RunResult rr = engine.runFixed(ir, artifactDir, ctx.planOnly, plan);
    long wall = System.currentTimeMillis() - t0;
    TrialResult tr = TrialResult.fromRun(rr, wall);
    if (ctx.planOnly && rr != null && rr.t_plan_ms != null) {
      tr.t_plan_ms = rr.t_plan_ms;
    }
    tr.extras.put("baseline_protocol", "fixed_primary_table_fullscan");
    tr.extras.put("candidate_search", Boolean.FALSE);
    tr.extras.put("selection", "fixed_P_FULL");
    return tr;
  }
}
