package kart.query;

import com.fasterxml.jackson.databind.JsonNode;
import kart.compile.LayoutContext;
import kart.config.AppConfig;
import kart.exec.ExecLimits;
import kart.exec.MemoryBackend;
import kart.ir.BoundIr;
import kart.llm.LlmClient;
import kart.llm.LlmException;
import kart.llm.LlmMessage;
import kart.llm.LlmOptions;
import kart.llm.LlmResponse;
import kart.plan.PlanBuilder;
import kart.plan.PlanEnvelope;
import kart.search.PlannerMode;
import kart.snapshot.FixtureBuilder;
import kart.snapshot.SnapshotBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Core engine wiring: BestFirst / plan-only / llm_direct / NFR-3 artifacts. */
public final class QueryEnginePlannerModeTest {

  @TempDir
  Path tmp;

  @Test
  void bestFirstPlanOnlyYieldsTimingAndNoExec() throws Exception {
    MemoryBackend kv = SnapshotBuilder.buildFixtureInMemory();
    try {
      LayoutContext layout = LayoutContext.from(FixtureBuilder.fixtureManifest());
      QueryEngine engine = new QueryEngine(kv, layout, ExecLimits.defaults(),
          null, AppConfig.PlannerConfig.defaults(), null, PlannerMode.BEST_FIRST);
      BoundIr ir = QueryIrFixtureTest.fixtureQuery();
      QueryEngine.RunResult rr = engine.run(ir, tmp.resolve("runs_bf"), true);
      assertEquals(PlannerMode.BEST_FIRST, rr.plannerMode);
      assertTrue(rr.planOnly);
      assertEquals("PLAN_ONLY", rr.result.status);
      assertNotNull(rr.t_plan_ms);
      assertNull(rr.t_exec_ms);
      assertNotNull(rr.selected);
      assertNotNull(rr.plan_regret_ms);
      assertEquals(0.0, rr.plan_regret_ms.doubleValue(), 1e-9);
      assertNotNull(rr.result.trace);
      assertEquals("best_first", rr.result.trace.planner_mode);
      assertTrue(Files.exists(rr.runDir.resolve("candidates.json")));
      assertTrue(Files.isDirectory(rr.runDir.resolve("candidates")));
      // At least one candidate directory with full plan.json
      boolean foundPlan = false;
      for (Path p : Files.newDirectoryStream(rr.runDir.resolve("candidates"))) {
        if (Files.isRegularFile(p.resolve("plan.json"))) {
          foundPlan = true;
          break;
        }
      }
      assertTrue(foundPlan, "NFR-3 requires full candidate plan.json under candidates/");
      assertTrue(Files.exists(rr.runDir.resolve("validation_reports.json")));
      String reports = new String(Files.readAllBytes(rr.runDir.resolve("validation_reports.json")),
          StandardCharsets.UTF_8);
      assertTrue(reports.contains("\"safe\"") && reports.contains("true"),
          "SAFE validation reports must be persisted");
    } finally {
      kv.close();
    }
  }

  @Test
  void ruleExecuteFillsTExec() throws Exception {
    MemoryBackend kv = SnapshotBuilder.buildFixtureInMemory();
    try {
      LayoutContext layout = LayoutContext.from(FixtureBuilder.fixtureManifest());
      QueryEngine engine = new QueryEngine(kv, layout, ExecLimits.defaults(),
          null, AppConfig.PlannerConfig.defaults(), null, PlannerMode.RULE);
      QueryEngine.RunResult rr = engine.run(QueryIrFixtureTest.fixtureQuery(), tmp.resolve("runs_r"));
      assertEquals("OK", rr.result.status);
      assertNotNull(rr.t_plan_ms);
      assertNotNull(rr.t_exec_ms);
      assertEquals(rr.t_plan_ms, rr.result.trace.t_plan_ms);
      assertEquals(rr.t_exec_ms, rr.result.trace.t_exec_ms);
    } finally {
      kv.close();
    }
  }

  @Test
  void fixedPlanPathDoesNotEnumerateCandidates() throws Exception {
    MemoryBackend kv = SnapshotBuilder.buildFixtureInMemory();
    try {
      LayoutContext layout = LayoutContext.from(FixtureBuilder.fixtureManifest());
      QueryEngine engine = new QueryEngine(kv, layout, ExecLimits.defaults(),
          null, AppConfig.PlannerConfig.defaults(), null, PlannerMode.RULE);
      BoundIr ir = QueryIrFixtureTest.fixtureQuery();
      QueryEngine.RunResult rr = engine.runFixed(ir, tmp.resolve("runs_fixed"), true,
          PlanBuilder.buildForAccess(ir, false, false, false));
      assertEquals("PLAN_ONLY", rr.result.status);
      assertEquals(1, rr.candidates.size());
      assertEquals(1, rr.safe.size());
      assertEquals("P_FULL", rr.selected.plan().plan_id);
      assertEquals(1, rr.costCards.size());
      assertNotNull(rr.t_plan_ms);
      assertNull(rr.t_exec_ms);

      QueryEngine.RunResult planned = engine.runFixed(ir, null, true,
          PlanBuilder.buildForAccess(ir, false, false, false));
      QueryEngine.RunResult exec = engine.executeSelected(ir, tmp.resolve("runs_exec"), planned);
      assertEquals("OK", exec.result.status);
      assertNotNull(exec.t_exec_ms);
      assertEquals(1, exec.candidates.size());
      assertEquals("P_FULL", exec.selected.plan().plan_id);
    } finally {
      kv.close();
    }
  }

  @Test
  void llmDirectAcceptsStubPlanId() throws Exception {
    MemoryBackend kv = SnapshotBuilder.buildFixtureInMemory();
    try {
      LayoutContext layout = LayoutContext.from(FixtureBuilder.fixtureManifest());
      LlmClient stub = new LlmClient() {
        @Override
        public LlmResponse chat(List<LlmMessage> messages, JsonNode schema, LlmOptions opts)
            throws LlmException {
          return new LlmResponse(
              "{\"response_version\":\"1.0\",\"plan_id\":\"P_TZ\"}");
        }
      };
      QueryEngine engine = new QueryEngine(kv, layout, ExecLimits.defaults(),
          null, AppConfig.PlannerConfig.defaults(), stub, PlannerMode.LLM_DIRECT);
      QueryEngine.RunResult rr = engine.run(QueryIrFixtureTest.fixtureQuery(),
          tmp.resolve("runs_ld"), true);
      assertEquals("PLAN_ONLY", rr.result.status);
      assertNotNull(rr.selected);
      assertEquals("P_TZ", rr.selected.plan().plan_id);
      assertEquals("llm_direct", rr.plannerMode.wireName());
      assertNotNull(rr.plan_regret_ms);
      assertTrue(rr.plan_regret_ms.doubleValue() >= 0.0);
    } finally {
      kv.close();
    }
  }

  @Test
  void llmDirectAcceptsFullPlanEnvelope() throws Exception {
    MemoryBackend kv = SnapshotBuilder.buildFixtureInMemory();
    try {
      LayoutContext layout = LayoutContext.from(FixtureBuilder.fixtureManifest());
      BoundIr ir = QueryIrFixtureTest.fixtureQuery();
      final PlanEnvelope familyPlan = PlanBuilder.buildForAccess(ir, true, true, false);
      LlmClient stub = new LlmClient() {
        @Override
        public LlmResponse chat(List<LlmMessage> messages, JsonNode schema, LlmOptions opts)
            throws LlmException {
          try {
            return new LlmResponse("{\"response_version\":\"1.0\",\"plan\":"
                + familyPlan.toJson() + "}");
          } catch (Exception e) {
            throw new LlmException(e.getMessage());
          }
        }
      };
      QueryEngine engine = new QueryEngine(kv, layout, ExecLimits.defaults(),
          null, AppConfig.PlannerConfig.defaults(), stub, PlannerMode.LLM_DIRECT);
      QueryEngine.RunResult rr = engine.run(ir, tmp.resolve("runs_env"), true);
      assertEquals("PLAN_ONLY", rr.result.status);
      assertNotNull(rr.selected);
      assertEquals(familyPlan.plan_id, rr.selected.plan().plan_id);
    } finally {
      kv.close();
    }
  }
}
