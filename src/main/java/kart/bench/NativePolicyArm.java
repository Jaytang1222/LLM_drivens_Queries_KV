package kart.bench;

import kart.ir.BoundIr;
import kart.query.QueryEngine;
import kart.search.PlannerMode;

import java.nio.file.Path;

/** Native planner arm wrapping {@link PlannerMode}. */
public final class NativePolicyArm implements Arm {

  private final String id;
  private final PlannerMode mode;
  private final String forcePlanId;

  public NativePolicyArm(String id, PlannerMode mode) {
    this(id, mode, null);
  }

  public NativePolicyArm(String id, PlannerMode mode, String forcePlanId) {
    this.id = id;
    this.mode = mode;
    this.forcePlanId = forcePlanId;
  }

  @Override
  public String id() {
    return id;
  }

  @Override
  public TrialResult run(BoundIr ir, BenchContext ctx) throws Exception {
    QueryEngine engine = ctx.newEngine(mode);
    Path artifactDir = ctx.artifactDir(id, ir.query_id);
    long t0 = System.currentTimeMillis();
    QueryEngine.RunResult rr = engine.run(ir, artifactDir, ctx.planOnly, forcePlanId);
    long wall = System.currentTimeMillis() - t0;
    TrialResult tr = TrialResult.fromRun(rr, wall);
    if (ctx.planOnly && rr != null && rr.t_plan_ms != null) {
      // Keep engine generate→select clock; do not fold artifact IO into t_plan.
      tr.t_plan_ms = rr.t_plan_ms;
    }
    if (engine.usageAccumulator() != null) {
      kart.llm.LlmUsageAccumulator u = engine.usageAccumulator();
      tr.extras.put("llm_calls", u.callsAsLongOrNull());
      tr.extras.put("tokens_in", u.promptTokensOrNull());
      tr.extras.put("tokens_out", u.completionTokensOrNull());
      if (u.failedAttempts() > 0) {
        tr.extras.put("llm_failed_attempts", Integer.valueOf(u.failedAttempts()));
      }
      if (u.lastHttpStatus() != null) {
        tr.extras.put("http_status", u.lastHttpStatus());
      }
    }
    return tr;
  }
}
