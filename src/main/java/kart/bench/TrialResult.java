package kart.bench;

import kart.exec.QueryResult;
import kart.query.QueryEngine;

import java.util.LinkedHashMap;
import java.util.Map;

/** Outcome of one arm execution (plan and/or e2e). */
public final class TrialResult {
  public QueryEngine.RunResult run;
  public QueryResult queryResult;
  public String plan_id;
  public String planner_mode;
  public Long t_plan_ms;
  public Long t_exec_ms;
  public Long t_e2e_ms;
  public Long t_parse_ms;
  public String error;
  public Map<String, Object> extras = new LinkedHashMap<String, Object>();

  public static TrialResult fromRun(QueryEngine.RunResult rr, long wallE2eMs) {
    TrialResult t = new TrialResult();
    t.run = rr;
    if (rr != null) {
      t.queryResult = rr.result;
      t.t_plan_ms = rr.t_plan_ms;
      t.t_exec_ms = rr.t_exec_ms;
      if (rr.plannerMode != null) {
        t.planner_mode = rr.plannerMode.wireName();
      }
      if (rr.selected != null && rr.selected.plan() != null) {
        t.plan_id = rr.selected.plan().plan_id;
      }
      if (rr.result != null && !"OK".equals(rr.result.status)
          && !"PLAN_ONLY".equals(rr.result.status)) {
        t.error = rr.result.status
            + (rr.result.error != null ? (": " + rr.result.error) : "");
      }
      if (rr.planStartEpochMs > 0L) {
        t.extras.put("plan_start_ms", Long.valueOf(rr.planStartEpochMs));
        t.extras.put("plan_end_ms", Long.valueOf(rr.planEndEpochMs));
      }
      t.extras.put("llm_requested", Boolean.valueOf(rr.llmRequested));
      if (rr.llmFallback) {
        t.extras.put("llm_fallback", Boolean.TRUE);
        t.extras.put("fallback_reason", rr.fallbackReason);
      }
    }
    t.extras.put("t_wall_ms", Long.valueOf(wallE2eMs));
    // E3 latency is plan+exec. Artifact IO is not part of t_e2e.
    if (t.t_plan_ms != null && t.t_exec_ms != null) {
      t.t_e2e_ms = Long.valueOf(t.t_plan_ms.longValue() + t.t_exec_ms.longValue());
      long art = wallE2eMs - t.t_e2e_ms.longValue();
      if (art > 0L) {
        t.extras.put("t_artifact_ms", Long.valueOf(art));
      }
    } else {
      t.t_e2e_ms = Long.valueOf(wallE2eMs);
    }
    return t;
  }

  /**
   * Stamp generate→select using the arm wrapper wall, excluding Coordinator
   * execute. Artifact IO that happened after exec is left on {@code t_wall_ms}.
   */
  public void applyWrapperPlanClock(long startEpochMs, long wallMs) {
    extras.put("plan_start_ms", Long.valueOf(startEpochMs));
    extras.put("plan_end_ms", Long.valueOf(startEpochMs + Math.max(0L, wallMs)));
    extras.put("t_wall_ms", Long.valueOf(wallMs));
    if (t_exec_ms != null) {
      long plan = Math.max(0L, wallMs - t_exec_ms.longValue());
      t_plan_ms = Long.valueOf(plan);
      t_e2e_ms = Long.valueOf(plan + t_exec_ms.longValue());
    } else {
      t_plan_ms = Long.valueOf(Math.max(0L, wallMs));
    }
  }

  public static TrialResult fail(String error) {
    TrialResult t = new TrialResult();
    t.error = error;
    return t;
  }
}
