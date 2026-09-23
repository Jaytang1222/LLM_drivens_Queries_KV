package kart.nl;

import kart.compile.LayoutContext;
import kart.config.AppConfig;
import kart.dialog.Dialog;
import kart.exec.MemoryBackend;
import kart.ir.ClarificationDetector;
import kart.ir.DraftIr;
import kart.ir.DraftIrParser;
import kart.ir.IrBinder;
import kart.ir.IrSchemaValidator;
import kart.llm.LlmMessage;
import kart.llm.MockLlmClient;
import kart.llm.PromptBuilder;
import kart.llm.ScriptedLlmClient;
import kart.query.QueryEngine;
import kart.snapshot.FixtureBuilder;
import kart.snapshot.SnapshotBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P3 acceptance: startRow rejected, COUNT unsupported, clarification dialog → [A,B].
 */
class QueryNlAcceptanceTest {

  private static IrSchemaValidator validator;
  private static AppConfig.RegionsConfig regions;
  private static Path root;

  @TempDir
  Path tmp;

  @BeforeAll
  static void init() throws Exception {
    root = Paths.get("").toAbsolutePath();
    Path schemas = root.resolve("schemas");
    if (!Files.isDirectory(schemas)) {
      schemas = root.resolve("../schemas");
    }
    validator = new IrSchemaValidator(schemas);
    AppConfig cfg = AppConfig.load(Files.isDirectory(root.resolve("config"))
        ? root : root.resolve(".."));
    regions = cfg.regions();
  }

  @Test
  void mockStartRowFieldRejected() {
    String bad = "{\"ir_version\":\"1.0\",\"source\":{\"dataset_id\":\"fixture_v1\",\"entity\":\"trajectory\"},"
        + "\"semantics\":{\"mode\":\"OBSERVED_POINT\",\"coupling\":\"SAME_POINT\"},"
        + "\"result\":{\"mode\":\"TRAJECTORY_IDS\"},\"startRow\":\"00ff\"}";
    ScriptedLlmClient llm = new ScriptedLlmClient(bad, bad, bad);
    DraftIrParser parser = new DraftIrParser(validator, llm);
    List<LlmMessage> msgs = Arrays.asList(LlmMessage.user("find trajectories"));
    DraftIrParser.ParseResult pr = parser.parseWithRepair(msgs);
    assertEquals(DraftIrParser.STATUS_INVALID_IR, pr.status, pr.error);
    assertTrue(pr.attempts <= 3);
  }

  @Test
  void countRequestUnsupported() throws Exception {
    MemoryBackend kv = SnapshotBuilder.buildFixtureInMemory();
    try {
      Dialog.Outcome out = runDialog(
          "Please COUNT how many trajectories intersect the box",
          new ScriptedLlmClient("{\"unused\":true}"),
          kv,
          Collections.<String>emptyList());
      assertEquals(Dialog.State.Unsupported, out.state);
      assertEquals(DraftIrParser.STATUS_UNSUPPORTED_QUERY, out.status);
    } finally {
      kv.close();
    }
  }

  @Test
  void countModeInDraftUnsupported() {
    String countJson = "{\"ir_version\":\"1.0\",\"source\":{\"dataset_id\":\"fixture_v1\",\"entity\":\"trajectory\"},"
        + "\"semantics\":{\"mode\":\"OBSERVED_POINT\",\"coupling\":\"SAME_POINT\"},"
        + "\"result\":{\"mode\":\"COUNT\"}}";
    DraftIrParser parser = new DraftIrParser(validator, new ScriptedLlmClient(countJson));
    DraftIrParser.ParseResult pr = parser.parseOnce(countJson);
    assertEquals(DraftIrParser.STATUS_UNSUPPORTED_QUERY, pr.status);
  }

  @Test
  void clarificationDialogReachesPlanningAndReturnsAB() throws Exception {
    MemoryBackend kv = SnapshotBuilder.buildFixtureInMemory();
    try {
      String incomplete = incompleteTopK();
      String complete = completeTopK();
      // parseWithRepair may retry; provide enough identical completes after first incomplete
      ScriptedLlmClient llm = new ScriptedLlmClient(incomplete, complete, complete, complete);
      List<String> answers = Arrays.asList(
          "2008-02-02T08:00:00+08:00 to 2008-02-02T08:10:00+08:00",
          "y");
      Dialog.Outcome out = runDialog(
          "Find the 2 trajectories most similar to R near fixture_box (date unknown)",
          llm, kv, answers);

      assertEquals(Dialog.State.Planning, out.state, out.error);
      assertTrue(out.clarifyRounds >= 1);
      assertNotNull(out.queryResult);
      assertEquals("OK", out.queryResult.status, out.queryResult.error);
      assertEquals(Arrays.asList("A", "B"), out.queryResult.trajectoryIds);
    } finally {
      kv.close();
    }
  }

  @Test
  void promptBuilderOmitsPhysicalWords() {
    PromptBuilder pb = new PromptBuilder(regions, "fixture_v1");
    String sys = pb.systemPrompt();
    assertFalse(PromptBuilder.containsPhysicalWords(sys), sys);
  }

  @Test
  void clarificationDetectorAsksOnlyDate() {
    DraftIr d = new DraftIr();
    d.result = new DraftIr.Result();
    d.result.mode = "TOP_K";
    d.result.k = 2;
    d.similarity = new DraftIr.Similarity();
    d.similarity.metric = "DTW";
    d.similarity.reference_trajectory_id = "R";
    d.spatial = new DraftIr.Spatial();
    d.spatial.region_name = "fixture_box";
    d.missing = Arrays.asList("temporal");
    ClarificationDetector det = new ClarificationDetector();
    List<ClarificationDetector.Question> qs = det.detect(d, Collections.<String>emptySet());
    assertEquals(1, qs.size());
    assertEquals("temporal", qs.get(0).field);
  }

  @Test
  void draftIrRepairSucceedsOnSecondAttempt() {
    String bad = "{not-json";
    String good = completeIds();
    ScriptedLlmClient llm = new ScriptedLlmClient(bad, good);
    DraftIrParser parser = new DraftIrParser(validator, llm);
    DraftIrParser.ParseResult pr = parser.parseWithRepair(
        Arrays.asList(LlmMessage.user("ids in box")));
    assertEquals(DraftIrParser.STATUS_OK, pr.status, pr.error);
    assertTrue(pr.attempts >= 2);
  }

  @Test
  void threeBadJsonYieldsInvalidIr() {
    ScriptedLlmClient llm = new ScriptedLlmClient("{a", "{b", "{c", "{d");
    DraftIrParser parser = new DraftIrParser(validator, llm);
    DraftIrParser.ParseResult pr = parser.parseWithRepair(
        Arrays.asList(LlmMessage.user("x")));
    assertEquals(DraftIrParser.STATUS_INVALID_IR, pr.status);
    assertEquals(3, pr.attempts);
  }

  @Test
  void mockLlmClientLoadsFixtureExamples() throws Exception {
    Path mockDir = root.resolve("testdata/llm-mock");
    if (!Files.isDirectory(mockDir)) {
      mockDir = root.resolve("../testdata/llm-mock");
    }
    assertTrue(Files.isDirectory(mockDir), "testdata/llm-mock required");
    MockLlmClient mock = new MockLlmClient(mockDir);
    PromptBuilder pb = new PromptBuilder(regions, "fixture_v1");
    mock.bindUtterances(pb);

    String completeUtt = "Find the 2 trajectories most similar to R inside fixture_box between "
        + "2008-02-02T08:00:00+08:00 and 2008-02-02T08:10:00+08:00";
    String missingUtt = "Find trajectories similar to R near fixture_box but I do not know the date yet";
    assertNotNull(mock.chat(pb.buildMessages(completeUtt, ""), null, null).content);
    assertNotNull(mock.chat(pb.buildMessages(missingUtt, ""), null, null).content);
  }

  private Dialog.Outcome runDialog(String utterance, ScriptedLlmClient llm, MemoryBackend kv,
                                   List<String> answers) throws Exception {
    PromptBuilder prompts = new PromptBuilder(regions, "fixture_v1");
    LayoutContext layout = LayoutContext.from(FixtureBuilder.fixtureManifest());
    IrBinder binder = new IrBinder(regions, kv, layout.tableMeta, layout.shardCount,
        FixtureBuilder.MANIFEST_ID, "point_dtw_v1");
    QueryEngine engine = new QueryEngine(kv, layout);
    Dialog dialog = new Dialog(llm, prompts, validator, binder, engine, tmp.resolve("runs"));
    final Deque<String> q = new ArrayDeque<String>(answers);
    return dialog.run(utterance, new Dialog.Io() {
      @Override
      public void println(String line) {
        // quiet
      }

      @Override
      public String readLine() {
        return q.poll();
      }
    });
  }

  private static String incompleteTopK() {
    return "{\"ir_version\":\"1.0\",\"source\":{\"dataset_id\":\"fixture_v1\",\"entity\":\"trajectory\"},"
        + "\"spatial\":{\"region_name\":\"fixture_box\"},"
        + "\"semantics\":{\"mode\":\"OBSERVED_POINT\",\"coupling\":\"SAME_POINT\"},"
        + "\"similarity\":{\"metric\":\"DTW\",\"reference_trajectory_id\":\"R\",\"exclude_reference\":true},"
        + "\"result\":{\"mode\":\"TOP_K\",\"k\":2},\"missing\":[\"temporal\"]}";
  }

  private static String completeTopK() {
    return "{\"ir_version\":\"1.0\",\"query_id\":\"q_nl_fixture\",\"source\":{\"dataset_id\":\"fixture_v1\","
        + "\"entity\":\"trajectory\"},"
        + "\"temporal\":{\"start\":\"2008-02-02T08:00:00+08:00\",\"end\":\"2008-02-02T08:10:00+08:00\"},"
        + "\"spatial\":{\"region_name\":\"fixture_box\"},"
        + "\"semantics\":{\"mode\":\"OBSERVED_POINT\",\"coupling\":\"SAME_POINT\"},"
        + "\"similarity\":{\"metric\":\"DTW\",\"reference_trajectory_id\":\"R\",\"exclude_reference\":true},"
        + "\"result\":{\"mode\":\"TOP_K\",\"k\":2},\"missing\":[]}";
  }

  private static String completeIds() {
    return "{\"ir_version\":\"1.0\",\"source\":{\"dataset_id\":\"fixture_v1\",\"entity\":\"trajectory\"},"
        + "\"temporal\":{\"start\":\"2008-02-02T08:00:00+08:00\",\"end\":\"2008-02-02T08:10:00+08:00\"},"
        + "\"spatial\":{\"region_name\":\"fixture_box\"},"
        + "\"semantics\":{\"mode\":\"OBSERVED_POINT\",\"coupling\":\"SAME_POINT\"},"
        + "\"result\":{\"mode\":\"TRAJECTORY_IDS\"},\"missing\":[]}";
  }
}
