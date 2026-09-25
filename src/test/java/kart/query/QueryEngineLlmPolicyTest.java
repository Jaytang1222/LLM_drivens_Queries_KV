package kart.query;

import kart.compile.LayoutContext;
import kart.config.AppConfig;
import kart.exec.ExecLimits;
import kart.exec.MemoryBackend;
import kart.ir.BoundIr;
import kart.llm.MockLlmClient;
import kart.llm.PromptBuilder;
import kart.snapshot.FixtureBuilder;
import kart.snapshot.SnapshotBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FR-3.6 / supported-semantics: when LlmClient is provided, QueryEngine uses LlmProposalPolicy
 * (StatusLog policy=llm). Mock may rule-fallback on action proposals; wiring still counts.
 */
public final class QueryEngineLlmPolicyTest {

  @TempDir
  Path tmp;

  @Test
  void withLlmClientSearchStillYieldsSafePlans() throws Exception {
    Path root = Paths.get(".").toAbsolutePath().normalize();
    if (!Files.isDirectory(root.resolve("testdata/llm-mock"))) {
      root = Paths.get("..").toAbsolutePath().normalize();
    }
    MemoryBackend kv = SnapshotBuilder.buildFixtureInMemory();
    try {
      LayoutContext layout = LayoutContext.from(FixtureBuilder.fixtureManifest());
      MockLlmClient mock = new MockLlmClient(root.resolve("testdata/llm-mock"));
      AppConfig.RegionsConfig regions = AppConfig.load(root).regions();
      mock.bindUtterances(new PromptBuilder(regions, "fixture_v1"));

      QueryEngine engine = new QueryEngine(kv, layout, ExecLimits.defaults(),
          null, AppConfig.PlannerConfig.defaults(), mock);
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
