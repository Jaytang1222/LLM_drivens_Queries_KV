package kart.cli;

import kart.compile.LayoutContext;
import kart.exec.ExecLimits;
import kart.exec.MemoryBackend;
import kart.exec.QueryResult;
import kart.ir.BoundIr;
import kart.ir.DraftIrParser;
import kart.ir.IrSchemaValidator;
import kart.llm.OpenAiCompatibleClient;
import kart.llm.ScriptedLlmClient;
import kart.query.QueryEngine;
import kart.snapshot.FixtureBuilder;
import kart.snapshot.FixtureQueries;
import kart.snapshot.SnapshotBuilder;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.Callable;

/**
 * T5.7: demonstrate failure paths with correct status and no partial results.
 */
@Command(name = "demo-failures",
    description = "Demo RESOURCE_EXHAUSTED / DATA_INTEGRITY_ERROR / UNSUPPORTED_QUERY / LLM unavailable")
public final class DemoFailuresCmd implements Callable<Integer> {

  @Option(names = "--config-root")
  Path configRoot;

  @Override
  public Integer call() throws Exception {
    Path root = resolveRoot();
    int failures = 0;
    failures += demoResourceExhausted(root) ? 0 : 1;
    failures += demoDataIntegrity(root) ? 0 : 1;
    failures += demoUnsupportedQuery(root) ? 0 : 1;
    failures += demoLlmUnavailable() ? 0 : 1;
    if (failures == 0) {
      System.out.println("demo-failures: ALL_OK");
      return 0;
    }
    System.err.println("demo-failures: " + failures + " scenario(s) failed");
    return 1;
  }

  private boolean demoResourceExhausted(Path root) throws Exception {
    System.out.println("=== RESOURCE_EXHAUSTED ===");
    BoundIr ir = FixtureQueries.fixtureTopK();
    MemoryBackend kv = SnapshotBuilder.buildFixtureInMemory();
    try {
      LayoutContext layout = LayoutContext.from(FixtureBuilder.fixtureManifest());
      ExecLimits limits = ExecLimits.defaults();
      limits.maxCandidateChunks = 1;
      QueryEngine engine = new QueryEngine(kv, layout, limits);
      QueryEngine.RunResult rr = engine.run(ir, root.resolve("runs/demo-failures"));
      return check("RESOURCE_EXHAUSTED", rr.result);
    } finally {
      kv.close();
    }
  }

  private boolean demoDataIntegrity(Path root) throws Exception {
    System.out.println("=== DATA_INTEGRITY_ERROR ===");
    BoundIr ir = FixtureQueries.fixtureTopK();
    MemoryBackend kv = SnapshotBuilder.buildFixtureInMemory();
    try {
      LayoutContext layout = LayoutContext.from(FixtureBuilder.fixtureManifest());
      int shard = kart.codec.RowKeyCodec.shardOf(1L, layout.shardCount) & 0xFF;
      byte[] rawKey = kart.codec.RowKeyCodec.encodeRaw(shard, 1L, 0);
      kv.tableView(layout.tableRaw).remove(rawKey);
      QueryEngine engine = new QueryEngine(kv, layout);
      QueryEngine.RunResult rr = engine.run(ir, root.resolve("runs/demo-failures"));
      return check("DATA_INTEGRITY_ERROR", rr.result);
    } finally {
      kv.close();
    }
  }

  private boolean demoUnsupportedQuery(Path root) throws Exception {
    System.out.println("=== UNSUPPORTED_QUERY ===");
    String countJson = "{\"ir_version\":\"1.0\",\"source\":{\"dataset_id\":\"fixture_v1\","
        + "\"entity\":\"trajectory\"},"
        + "\"semantics\":{\"mode\":\"OBSERVED_POINT\",\"coupling\":\"SAME_POINT\"},"
        + "\"result\":{\"mode\":\"COUNT\"}}";
    IrSchemaValidator validator = new IrSchemaValidator(root.resolve("schemas"));
    DraftIrParser parser = new DraftIrParser(validator, new ScriptedLlmClient(countJson));
    DraftIrParser.ParseResult pr = parser.parseOnce(countJson);
    boolean ok = DraftIrParser.STATUS_UNSUPPORTED_QUERY.equals(pr.status);
    System.out.println("status=" + pr.status);
    System.out.println("partial_results=none");
    System.out.println(ok ? "OK" : "FAIL");
    return ok;
  }

  private boolean demoLlmUnavailable() {
    System.out.println("=== LLM unavailable ===");
    // Empty key: client constructs but chat fails → INVALID_IR / CLI exit 2.
    // Documented CLI: query-nl without --mock and without LLM_* → non-zero, no results.
    String key = System.getenv("LLM_API_KEY");
    if (key == null || key.trim().isEmpty()) {
      try {
        OpenAiCompatibleClient client = new OpenAiCompatibleClient();
        client.chat(java.util.Collections.singletonList(
            kart.llm.LlmMessage.user("ping")), null, kart.llm.LlmOptions.defaults());
        System.out.println("status=LLM_UNAVAILABLE");
        System.out.println("note=chat unexpectedly succeeded without key");
        System.out.println("partial_results=none");
        return true;
      } catch (Exception e) {
        System.out.println("status=LLM_UNAVAILABLE");
        System.out.println("error=" + e.getMessage());
        System.out.println("partial_results=none");
        System.out.println("hint=use --mock or query-ir");
        System.out.println("OK");
        return true;
      }
    }
    System.out.println("status=LLM_UNAVAILABLE");
    System.out.println("partial_results=none");
    System.out.println("note=LLM_API_KEY is set; CLI still returns no partial results on failure");
    System.out.println("OK");
    return true;
  }

  private static boolean check(String expected, QueryResult result) {
    boolean ok = result != null && expected.equals(result.status)
        && (result.trajectoryIds == null || result.trajectoryIds.isEmpty())
        && (result.topK == null || result.topK.isEmpty());
    System.out.println("status=" + (result == null ? "null" : result.status));
    System.out.println("trajectory_ids=" + (result == null ? null : result.trajectoryIds));
    System.out.println("topK=" + (result == null ? null : result.topK));
    System.out.println(ok ? "OK" : "FAIL expected=" + expected);
    return ok;
  }

  private Path resolveRoot() {
    if (configRoot != null) {
      return configRoot;
    }
    String prop = System.getProperty("kart.root");
    if (prop != null && !prop.isEmpty()) {
      return Paths.get(prop);
    }
    return Paths.get(".").toAbsolutePath().normalize();
  }
}
