package kart.bench;

import kart.ir.BoundIr;
import kart.plan.PlanBuilder;
import kart.plan.PlanEnvelope;
import kart.query.QueryEngine;
import kart.search.PlannerMode;

/** Offline screening only: validates every constructable merge variant without an LLM or execution. */
public final class PilotSafetyProbeArm implements Arm {
  @Override
  public String id() {
    return "pilot-safety-probe";
  }

  @Override
  public TrialResult run(BoundIr ir, BenchContext ctx) throws Exception {
    if (!ctx.planOnly) {
      return TrialResult.fail("pilot-safety-probe is plan-only");
    }
    long start = System.currentTimeMillis();
    QueryEngine engine = ctx.newEngine(PlannerMode.RULE);
    QueryEngine.RunResult aggregate = new QueryEngine.RunResult();
    aggregate.ir = ir;
    aggregate.planOnly = true;
    for (PlanEnvelope envelope : PlanBuilder.buildCandidatesWithMergeVariants(ir)) {
      QueryEngine.RunResult one = engine.runFixed(ir, null, true, envelope);
      aggregate.candidates.add(envelope);
      if (one == null) {
        continue;
      }
      aggregate.safe.addAll(one.safe);
      aggregate.costCards.addAll(one.costCards);
      aggregate.rejections.addAll(one.rejections);
      aggregate.rejectionReports.addAll(one.rejectionReports);
      if (aggregate.selected == null && one.selected != null) {
        aggregate.selected = one.selected;
        aggregate.selectedCost = one.selectedCost;
        aggregate.result = one.result;
      }
    }
    long end = System.currentTimeMillis();
    aggregate.planStartEpochMs = start;
    aggregate.planEndEpochMs = end;
    aggregate.t_plan_ms = Long.valueOf(Math.max(0L, end - start));
    TrialResult result = TrialResult.fromRun(aggregate, aggregate.t_plan_ms.longValue());
    result.extras.put("screen_only", Boolean.TRUE);
    return result;
  }
}
