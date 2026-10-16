package kart.bench;

import kart.query.QueryEngine;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TrialResultTimingTest {

  @Test
  void fromRunExportsPlannerPhaseTimings() {
    QueryEngine.RunResult run = new QueryEngine.RunResult();
    run.t_plan_ms = Long.valueOf(100L);
    run.t_exec_ms = Long.valueOf(50L);
    run.t_search_ms = Long.valueOf(60L);
    run.t_validation_ms = Long.valueOf(25L);
    run.t_final_cost_ms = Long.valueOf(15L);

    TrialResult row = TrialResult.fromRun(run, 160L);

    assertEquals(Long.valueOf(60L), row.extras.get("t_search_ms"));
    assertEquals(Long.valueOf(25L), row.extras.get("t_validation_ms"));
    assertEquals(Long.valueOf(15L), row.extras.get("t_final_cost_ms"));
    assertEquals(Long.valueOf(150L), row.t_e2e_ms);
  }
}
