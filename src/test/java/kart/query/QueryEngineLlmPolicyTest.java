package kart.query;

import kart.compile.LayoutContext;
import kart.config.AppConfig;
import kart.exec.ExecLimits;
import kart.exec.MemoryBackend;
import kart.ir.BoundIr;
import kart.llm.ScriptedLlmClient;
import kart.snapshot.FixtureBuilder;
import kart.snapshot.SnapshotBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FR-3.6: when LlmClient is provided, QueryEngine uses LlmProposalPolicy.
 * Scripted responses may miss action JSON → rule fallback; wiring still yields SafePlans.
 */
public final class QueryEngineLlmPolicyTest {

  @TempDir
  Path tmp;

  @Test
  void withLlmClientSearchStillYieldsSafePlans() throws Exception {
    MemoryBackend kv = SnapshotBuilder.buildFixtureInMemory();
    try {
      LayoutContext layout = LayoutContext.from(FixtureBuilder.fixtureManifest());
      // Non-action JSON → LlmProposalPolicy falls back to RulePolicy; engine must still finish.
      ScriptedLlmClient llm = new ScriptedLlmClient(
          "{}", "{}", "{}", "{}", "{}", "{}", "{}", "{}", "{}", "{}");

      QueryEngine engine = new QueryEngine(kv, layout, ExecLimits.defaults(),
          null, AppConfig.PlannerConfig.defaults(), llm);
      BoundIr ir = QueryIrFixtureTest.fixtureQuery();
      QueryEngine.RunResult rr = engine.run(ir, tmp.resolve("runs"));
      assertNotNull(rr);
      assertTrue(rr.safe != null && !rr.safe.isEmpty(), "expected SafePlans with llm policy");
      assertNotNull(rr.result);
      assertEquals("OK", rr.result.status, rr.result.error);
    } finally {
      kv.close();
    }
  }
}
