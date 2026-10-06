package kart.bench;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import kart.compile.LayoutContext;
import kart.config.AppConfig;
import kart.exec.MemoryBackend;
import kart.ir.BoundIr;
import kart.llm.*;
import kart.oracle.FullScanOracle;
import kart.plan.*;
import kart.query.QueryEngine;
import kart.query.QueryIrFixtureTest;
import kart.rewrite.ActionMemory;
import kart.rewrite.RewriteProtocol;
import kart.rewrite.SafeRewriteBuilder;
import kart.snapshot.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class LlmRewriteContractTest {
  @TempDir Path root;
  private static final ObjectMapper M = new ObjectMapper();
  private final BoundIr ir = QueryIrFixtureTest.fixtureQuery();

  private PlanEnvelope ptz() {
    return PlanBuilder.buildForAccess(ir, true, true, false);
  }

  private static PlanNode exact(PlanEnvelope p) {
    for (PlanNode n : p.nodes) {
      if (n.op == Op.EXACT_ST_FILTER) {
        return n;
      }
    }
    throw new AssertionError("no exact filter");
  }

  @Test void legalRemovalMatchesTemplateAndKeepsAllPredicates() throws Exception {
    PlanEnvelope p = ptz();
    assertEquals(new HashSet<>(Arrays.asList("temporal_branch", "spatial_branch")),
        SafeRewriteBuilder.removableBranches(p).keySet());
    PlanEnvelope t = SafeRewriteBuilder.removeAccessBranch(p, "spatial_branch", ir);
    assertEquals("P_T", t.plan_id);
    assertEquals(PlanBuilder.buildForAccess(ir, true, false, false).signature(), t.signature());
    assertEquals(exact(p).params.get("predicate_refs"), exact(t).params.get("predicate_refs"));
    assertTrue(t.nodes.stream().noneMatch(n -> n.op == Op.INTERSECT || n.op == Op.ZORDER_RANGE_SCAN));
    assertEquals("P_Z", SafeRewriteBuilder.removeAccessBranch(p, "temporal_branch", ir).plan_id);
    assertEquals(p.signature(), ptz().signature(), "input plan must not be mutated");
  }

  @Test void illegalTargetsAndStructuresAreRejected() {
    PlanEnvelope p = ptz();
    for (String bad : new String[] {"vehicle_branch", "n01", "foo", null}) {
      assertThrows(SafeRewriteBuilder.Rejected.class, () -> SafeRewriteBuilder.removeAccessBranch(p, bad, ir));
    }
    assertTrue(SafeRewriteBuilder.removableBranches(PlanBuilder.buildForAccess(ir, true, false, false)).isEmpty());
    assertTrue(SafeRewriteBuilder.removableBranches(
        PlanBuilder.buildForAccess(ir, true, true, false, "HASH_SET", "TIME_BIPART")).isEmpty(), "UNION partitions");
    PlanEnvelope noExactSpatial = ptz();
    exact(noExactSpatial).params.put("predicate_refs", Collections.singletonList("/temporal"));
    assertEquals(Collections.singleton("temporal_branch"), SafeRewriteBuilder.removableBranches(noExactSpatial).keySet());
    assertThrows(SafeRewriteBuilder.Rejected.class,
        () -> SafeRewriteBuilder.removeAccessBranch(noExactSpatial, "spatial_branch", ir));
  }

  private static ObjectNode synth(boolean reversedInsert) {
    ObjectNode c = RewriteProtocol.newContext("TRAJECTORY_IDS");
    ((ObjectNode) c.get("features")).put("chunks_keep", 2700).put("cbo_plan", "P_TZ").put("has_temporal", true);
    ObjectNode a = RewriteProtocol.addSlot(c, "access", "keep");
    ObjectNode on = RewriteProtocol.addSlot(c, "meta_probe", "off").putObject("on");
    ObjectNode f = RewriteProtocol.addSlot(c, "fetch_batch", "b500");
    if (reversedInsert) {
      a.put("drop_temporal_branch", 120.0).put("drop_spatial_branch", -40.0);
      on.put("drop_spatial_branch", -300.0).put("keep", -5.0);
      f.put("b200", 30.0).put("b2000", -10.0);
    } else {
      a.put("drop_spatial_branch", -40.0).put("drop_temporal_branch", 120.0);
      on.put("keep", -5.0).put("drop_spatial_branch", -300.0);
      f.put("b2000", -10.0).put("b200", 30.0);
    }
    RewriteProtocol.addSlot(c, "range_budget", "r64").put("r16", 0.0);
    ((ObjectNode) c.path("slots").path("range_budget")).putArray("ignored_with").add("drop_spatial_branch");
    RewriteProtocol.addSlot(c, "topk", "off");
    RewriteProtocol.prune(c);
    return c;
  }

  @Test void programSearchEstimatesAndCombinationRules() {
    ObjectNode c = synth(false);
    assertEquals(new TreeSet<>(Arrays.asList("access", "fetch_batch", "meta_probe", "range_budget")), RewriteProtocol.slots(c));
    RewriteProtocol.Program d = RewriteProtocol.deterministic(c);
    Map<String, String> want = new TreeMap<>();
    want.put("access", "drop_spatial_branch");
    want.put("fetch_batch", "b2000");
    want.put("meta_probe", "on");
    want.put("range_budget", "r64");
    assertEquals(want, d.choice, "ties prefer the default option");
    assertEquals(-350.0, RewriteProtocol.estimate(c, d.choice), 1e-9);
    Map<String, String> noEvidence = new TreeMap<>(want);
    noEvidence.put("access", "drop_temporal_branch");
    assertTrue(Double.isInfinite(RewriteProtocol.estimate(c, noEvidence)), "meta_probe on without evidence");
    assertEquals(-340.0, RewriteProtocol.runnerUp(c, d.choice), 1e-9, "range_budget is ignored under drop_spatial_branch");
    assertTrue(RewriteProtocol.defaults(c).isDefault(c));
    assertEquals(0.0, RewriteProtocol.estimate(c, RewriteProtocol.defaults(c).choice), 1e-9);
  }

  @Test void schemaSignatureAndPromptAreOrderIndependent() throws Exception {
    ObjectNode a = synth(false);
    ObjectNode b = synth(true);
    assertEquals(RewriteProtocol.schema(a), RewriteProtocol.schema(b));
    assertEquals(RewriteProtocol.signature(a), RewriteProtocol.signature(b));
    JsonNode s = RewriteProtocol.schema(a);
    assertEquals("[\"access\",\"fetch_batch\",\"meta_probe\",\"range_budget\"]", s.path("required").toString());
    assertEquals("[\"b200\",\"b2000\",\"b500\"]", s.path("properties").path("fetch_batch").path("enum").toString());
    assertFalse(s.path("additionalProperties").asBoolean(true));
    String u = RewriteProtocol.messages(a, false).get(1).content;
    assertTrue(u.contains("meta_probe (default off):"));
    assertTrue(u.contains("with drop_spatial_branch -300"));
    assertTrue(u.contains("(no effect with access drop_spatial_branch)"));
    assertNotEquals(u, RewriteProtocol.messages(a, true).get(1).content);
    assertFalse(u.contains("P_TZ"), "no plan-id hint in prompt");
    OpenAiCompatibleClient cl = new OpenAiCompatibleClient("http://x/v1", "k", "kart-refine7-1p5b-t2", false);
    JsonNode body = cl.buildBody(RewriteProtocol.messages(a, false), s, RewriteProtocol.options(3000L, 60));
    assertEquals(0.0, body.path("temperature").asDouble());
    assertEquals(s, body.path("response_format").path("json_schema").path("schema"));
  }

  @Test void strictParsing() {
    ObjectNode c = synth(false);
    RewriteProtocol.Program ok = RewriteProtocol.parse(
        "{\"access\":\"keep\",\"fetch_batch\":\"b500\",\"meta_probe\":\"on\",\"range_budget\":\"r16\"}", c);
    assertEquals("ok", ok.status);
    assertEquals("on", ok.choice.get("meta_probe"));
    String[][] bad = {
        {"{\"access\":\"keep\",\"fetch_batch\":\"b500\",\"meta_probe\":\"on\"}", "invalid_shape"},
        {"{\"access\":\"keep\",\"fetch_batch\":\"b500\",\"meta_probe\":\"on\",\"range_budget\":\"r16\",\"why\":\"x\"}", "invalid_shape"},
        {"{\"access\":\"drop\",\"fetch_batch\":\"b500\",\"meta_probe\":\"on\",\"range_budget\":\"r16\"}", "invalid_shape"},
        {"{\"access\":\"keep\",\"fetch_batch\":500,\"meta_probe\":\"on\",\"range_budget\":\"r16\"}", "invalid_shape"},
        {"{\"access\":\"drop_temporal_branch\",\"fetch_batch\":\"b500\",\"meta_probe\":\"on\",\"range_budget\":\"r64\"}", "inapplicable_combination"},
        {"keep everything", "invalid_json"}};
    for (String[] b : bad) {
      RewriteProtocol.Program p = RewriteProtocol.parse(b[0], c);
      assertNull(p.choice, b[0]);
      assertEquals(b[1], p.status, b[0]);
    }
  }

  @Test void rulesMatchOnlyConfirmedKnownOptions() throws Exception {
    ObjectNode c = synth(false);
    JsonNode rules = M.readTree("[{\"id\":\"t\",\"status\":\"trial\",\"when\":[[\"chunks_keep\",\">\",1]],\"set\":{\"fetch_batch\":\"b200\"}},"
        + "{\"id\":\"x\",\"status\":\"confirmed\",\"when\":[[\"chunks_keep\",\">\",1]],\"set\":{\"fetch_batch\":\"b9\"}},"
        + "{\"id\":\"y\",\"status\":\"confirmed\",\"when\":[[\"chunks_keep\",\"<\",1000]],\"set\":{\"meta_probe\":\"on\"}},"
        + "{\"id\":\"z\",\"status\":\"confirmed\",\"when\":[[\"chunks_keep\",\">=\",1000],[\"cbo_plan\",\"==\",\"P_TZ\"],"
        + "[\"has_temporal\",\"==\",true]],\"set\":{\"meta_probe\":\"on\"}}]");
    RewriteProtocol.Program p = RewriteProtocol.matchRule(rules, c);
    assertEquals("rule:z", p.status);
    Map<String, String> want = new TreeMap<>(RewriteProtocol.defaults(c).choice);
    want.put("meta_probe", "on");
    assertEquals(want, p.choice);
    assertNull(RewriteProtocol.matchRule(M.readTree("[{\"id\":\"u\",\"status\":\"confirmed\",\"when\":[[\"nope\",\">\",1]],"
        + "\"set\":{\"meta_probe\":\"on\"}}]"), c), "unknown feature never matches");
    JsonNode compose = M.readTree("[{\"id\":\"a\",\"when\":[[\"chunks_keep\",\">\",1]],\"set\":{\"fetch_batch\":\"b2000\"}},"
        + "{\"id\":\"b\",\"status\":\"confirmed\",\"when\":[[\"cbo_plan\",\"==\",\"P_TZ\"]],\"set\":{\"meta_probe\":\"on\"}}]");
    RewriteProtocol.Program both = RewriteProtocol.matchRule(compose, c);
    assertEquals("rule:a+b", both.status);
    Map<String, String> merged = new TreeMap<>(RewriteProtocol.defaults(c).choice);
    merged.put("fetch_batch", "b2000");
    merged.put("meta_probe", "on");
    assertEquals(merged, both.choice);
  }

  private static BoundIr variant(BoundIr base, String metric, boolean topK) throws Exception {
    BoundIr q = BoundIr.fromJson(M.writeValueAsString(base));
    if (topK) {
      q.similarity.metric = metric;
    } else {
      q.result.mode = "TRAJECTORY_IDS";
      q.result.k = null;
      q.similarity = null;
    }
    return q;
  }

  @Test void actionPlansValidateAndPreserveResults() throws Exception {
    LayoutContext layout = LayoutContext.from(FixtureBuilder.fixtureManifest());
    try (MemoryBackend kv = SnapshotBuilder.buildFixtureInMemory()) {
      QueryEngine engine = new QueryEngine(kv, layout);
      for (String metric : new String[] {"DTW", "FRECHET", "HAUSDORFF"}) {
        for (boolean topK : new boolean[] {true, false}) {
          BoundIr q = variant(ir, metric, topK);
          FullScanOracle.Answer want = FullScanOracle.forFixture().evaluate(q);
          PlanEnvelope base = PlanBuilder.buildForAccess(q, true, true, false);
          List<PlanEnvelope> plans = new ArrayList<>(Arrays.asList(base,
              SafeRewriteBuilder.withMetaProbe(base),
              SafeRewriteBuilder.withMetaProbe(SafeRewriteBuilder.removeAccessBranch(base, "spatial_branch", q)),
              SafeRewriteBuilder.withMetaProbe(SafeRewriteBuilder.removeAccessBranch(base, "temporal_branch", q)),
              SafeRewriteBuilder.withParam(base, Op.FETCH_TRAJECTORY_CHUNK, "fetch_batch", 1, "B1"),
              SafeRewriteBuilder.withParam(base, Op.ZORDER_RANGE_SCAN, "max_ranges", 1, "R1")));
          if (topK) {
            Map<String, String> prog = new TreeMap<>();
            prog.put("topk", "lb1");
            prog.put("meta_probe", "on");
            Map<String, String> def = new TreeMap<>();
            def.put("topk", "off");
            plans.add(LlmRewriteArm.build(q, base, prog, def));
          }
          for (PlanEnvelope p : plans) {
            QueryEngine.RunResult rr = engine.runFixed(q, null, true, p, QueryEngine.PrepareMode.SAFETY_ONLY);
            assertNotNull(rr.selected, metric + " " + p.plan_id + " " + rr.rejectionReports);
            QueryEngine.RunResult ex = engine.executeSelected(q, root, rr);
            assertEquals("OK", ex.result.status, ex.result.error);
            assertEquals(want.trajectoryIds, ex.result.trajectoryIds, metric + " " + p.plan_id);
            if (topK) {
              for (int i = 0; i < want.topK.size(); i++) {
                assertEquals(want.topK.get(i).distance, ex.result.topK.get(i).distance, 1e-9, p.plan_id);
              }
            }
          }
        }
      }
    }
  }

  @Test void validatorRejectsMisplacedOrOutOfRangeParams() throws Exception {
    PlanEnvelope base = ptz();
    List<PlanEnvelope> bad = Arrays.asList(
        SafeRewriteBuilder.withParam(base, Op.FETCH_TRAJECTORY_CHUNK, "fetch_batch", 0, "B0"),
        SafeRewriteBuilder.withParam(base, Op.ZORDER_RANGE_SCAN, "max_ranges", 5000, "R5000"),
        SafeRewriteBuilder.withParam(base, Op.TIME_RANGE_SCAN, "max_ranges", 8, "RT"),
        SafeRewriteBuilder.withParam(SafeRewriteBuilder.withParam(base, Op.BATCH_GET_TRAJECTORY, "topk_lb_b0", 2, "LB"),
            Op.BATCH_GET_TRAJECTORY, "topk_lb_k", 1, null),
        SafeRewriteBuilder.withParam(base, Op.BATCH_GET_TRAJECTORY, "topk_lb_b0", 2, "LBnok"));
    try (MemoryBackend kv = SnapshotBuilder.buildFixtureInMemory()) {
      QueryEngine engine = new QueryEngine(kv, LayoutContext.from(FixtureBuilder.fixtureManifest()));
      for (PlanEnvelope p : bad) {
        QueryEngine.RunResult rr;
        try {
          rr = engine.runFixed(ir, null, true, p, QueryEngine.PrepareMode.SAFETY_ONLY);
        } catch (Exception e) {
          rr = null;
        }
        assertTrue(rr == null || rr.selected == null, p.plan_id);
      }
    }
    assertThrows(SafeRewriteBuilder.Rejected.class, () -> SafeRewriteBuilder.withMetaProbe(SafeRewriteBuilder.withMetaProbe(base)));
  }

  @Test void timeoutAndFailureReturnNoDecisionWithoutRetry() {
    AtomicInteger calls = new AtomicInteger();
    Map<String, Object> x = new LinkedHashMap<>();
    LlmClient slow = (m, s, o) -> {
      calls.incrementAndGet();
      try {
        Thread.sleep(5000L);
      } catch (InterruptedException e) {
        throw new LlmException("interrupted");
      }
      return new LlmResponse("{}");
    };
    assertNull(LlmRewriteArm.call(slow, Collections.emptyList(), M.createObjectNode(), RewriteProtocol.options(50, 40), 50L, x));
    assertEquals("llm_timeout", x.get("llm_error"));
    assertEquals("cancelled", x.get("llm_cancel"));
    x.clear();
    LlmClient broken = (m, s, o) -> {
      calls.incrementAndGet();
      throw new LlmException("boom");
    };
    assertNull(LlmRewriteArm.call(broken, Collections.emptyList(), M.createObjectNode(), RewriteProtocol.options(50, 40), 50L, x));
    assertEquals("llm_failure", x.get("llm_error"));
    assertEquals(2, calls.get());
  }

  private TrialResult armRun(LlmRewriteArm arm, LlmClient model) throws Exception {
    try (MemoryBackend kv = SnapshotBuilder.buildFixtureInMemory()) {
      BenchContext ctx = new BenchContext(root, root, "fixture", "compare", "rl", "e2e", false, true,
          false, "warm", FixtureBuilder.fixtureManifest(), LayoutContext.from(FixtureBuilder.fixtureManifest()),
          null, AppConfig.PlannerConfig.defaults(), kv, model, null, null, null);
      TrialResult r = arm.run(ir, ctx);
      assertNull(r.error);
      assertEquals(FullScanOracle.forFixture().evaluate(ir).trajectoryIds, r.queryResult.trajectoryIds);
      assertEquals(r.t_e2e_ms.longValue(), (Long) r.extras.get("t_wall_ms"));
      assertEquals(r.t_e2e_ms - r.t_plan_ms - r.t_exec_ms, ((Long) r.extras.get("t_e2e_residual_ms")).longValue());
      return r;
    }
  }

  private static LlmClient metaOn(AtomicInteger calls) {
    return (m, s, o) -> {
      calls.incrementAndGet();
      ObjectNode out = M.createObjectNode();
      s.path("properties").fields().forEachRemaining(e -> {
        String pick = null;
        for (JsonNode v : e.getValue().path("enum")) {
          String t = v.asText();
          if (t.equals("keep") || t.equals("off") || t.equals("b500") || t.equals("r64") || t.equals("on")) {
            pick = pick == null || t.equals("on") ? t : pick;
          }
        }
        out.put(e.getKey(), pick == null ? e.getValue().path("enum").get(0).asText() : pick);
      });
      return new LlmResponse(out.toString());
    };
  }

  @Test void gatesSkipWithoutCalling() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    TrialResult shortQ = armRun(new LlmRewriteArm("llm", 1e12, 0, 8000, null, ptz()), metaOn(calls));
    assertEquals("g1_short_query", shortQ.extras.get("skip_reason"));
    TrialResult noGain = armRun(new LlmRewriteArm("llm", -1e12, -1e12, 8000, null, ptz()), metaOn(calls));
    assertEquals("g2_no_gain", noGain.extras.get("skip_reason"));
    TrialResult audit = armRun(new LlmRewriteArm("cbo", -1e12, 1e12, 8000, null, ptz()), metaOn(calls));
    assertEquals("mode_cbo", audit.extras.get("skip_reason"));
    assertEquals(0, calls.get());
    JsonNode ctx = (JsonNode) audit.extras.get("llm_context");
    assertTrue(RewriteProtocol.slots(ctx).containsAll(Arrays.asList("access", "meta_probe", "fetch_batch", "range_budget", "topk")),
        ctx.toString());
    assertNotNull(audit.extras.get("g3_dominant"));
    assertNotNull(audit.extras.get("g4_covered"));
    for (TrialResult r : Arrays.asList(shortQ, noGain, audit)) {
      assertEquals(r.extras.get("cbo_plan_id"), r.plan_id);
    }
  }

  @Test void parallelLlmThenMemoAdoptsThenFeedbackRevokes() throws Exception {
    LlmRewriteArm.clearMemo();
    AtomicInteger calls = new AtomicInteger();
    LlmRewriteArm arm = new LlmRewriteArm("llm", -1e12, 1e12, 8000, null, ptz());
    TrialResult first = armRun(arm, metaOn(calls));
    assertEquals("CALL_PARALLEL", first.extras.get("gate_decision"));
    assertEquals(1, calls.get());
    assertEquals(first.extras.get("cbo_plan_id"), first.plan_id, "first encounter executes CBO while the model runs");
    assertEquals(Boolean.TRUE, first.extras.get("memo_stored"));

    TrialResult second = armRun(arm, metaOn(calls));
    assertEquals("MEMO_HIT", second.extras.get("gate_decision"));
    assertEquals(1, calls.get());
    assertEquals(Boolean.TRUE, second.extras.get("rewrite_adopted"), String.valueOf(second.extras));
    assertTrue(second.plan_id.contains("+MP"), second.plan_id);

    Map<String, Object> slow = new HashMap<>();
    slow.put("rewrite_adopted", Boolean.TRUE);
    LlmRewriteArm.feedback(ir.query_id, (String) second.extras.get("memo_signature"), 1L << 40, slow);
    assertEquals(Boolean.TRUE, slow.get("memo_revoked"));
    TrialResult third = armRun(arm, metaOn(calls));
    assertEquals("signature_revoked", third.extras.get("fallback_reason"));
    assertEquals(third.extras.get("cbo_plan_id"), third.plan_id);
    assertEquals(1, calls.get());
    LlmRewriteArm.clearMemo();
  }

  @Test void rulesDeterministicAndSweepModes() throws Exception {
    LlmRewriteArm.clearMemo();
    JsonNode rules = M.readTree("[{\"id\":\"mp\",\"status\":\"confirmed\",\"when\":[[\"has_temporal\",\"==\",true]],"
        + "\"set\":{\"meta_probe\":\"on\",\"topk\":\"lb2\"}}]");
    TrialResult rule = armRun(new LlmRewriteArm("llm", -1e12, -1e12, 8000, rules, ptz()), null);
    assertEquals("RULE", rule.extras.get("gate_decision"));
    assertEquals("rule:mp", rule.extras.get("program_status"));
    assertTrue(rule.plan_id.contains("+MP") && rule.plan_id.contains("+LB2"), rule.plan_id);

    TrialResult det = armRun(new LlmRewriteArm("deterministic", -1e12, 1e12, 8000, null, ptz()), null);
    assertEquals("DETERMINISTIC", det.extras.get("gate_decision"));

    TrialResult sw = armRun(new LlmRewriteArm("sweep", -1e12, 0, 8000, null, ptz()), null);
    assertEquals(sw.extras.get("cbo_plan_id"), sw.plan_id);
    ArrayNode rows = (ArrayNode) sw.extras.get("sweep");
    assertTrue(rows.size() >= 5, rows.toString());
    for (JsonNode r : rows) {
      assertFalse(r.has("error"), r.toString());
      assertTrue(r.path("same_result").asBoolean(), r.toString());
    }
    JsonNode ctx = (JsonNode) sw.extras.get("llm_context");
    Map<String, String> def = RewriteProtocol.defaults(ctx).choice;
    boolean hasDefault = false;
    for (JsonNode r : rows) {
      hasDefault |= def.equals(M.convertValue(r.get("program"), Map.class));
    }
    assertTrue(hasDefault, rows.toString());
    ArrayNode plans = (ArrayNode) sw.extras.get("cbo_plans");
    assertTrue(plans.size() >= 2, plans.toString());
    for (JsonNode p : plans) {
      assertTrue(p.has("card"), p.toString());
      assertTrue(p.path("selected").asBoolean() || p.has("error") || p.path("same_result").asBoolean(), p.toString());
    }
    LlmRewriteArm.clearMemo();
  }

  @Test void pathCNearMissCallsAndFarMissSkips() throws Exception {
    LlmRewriteArm.clearMemo();
    AtomicInteger calls = new AtomicInteger();
    JsonNode far = M.readTree("[{\"id\":\"far\",\"status\":\"confirmed\",\"when\":[[\"cbo_est_ms\",\">\",1.0e15],"
        + "[\"time_span_s\",\">\",1.0e15]],\"set\":{\"meta_probe\":\"on\"}}]");
    TrialResult skipFar = armRun(new LlmRewriteArm("llm", -1e12, 1e12, 8000, far, ptz()), metaOn(calls));
    assertEquals("CALL_PARALLEL", skipFar.extras.get("gate_decision"), String.valueOf(skipFar.extras));
    assertEquals(1, calls.get());
    assertEquals(skipFar.extras.get("cbo_plan_id"), skipFar.plan_id);
    LlmRewriteArm.clearMemo();
    calls.set(0);
    JsonNode near = M.readTree("[{\"id\":\"near\",\"status\":\"confirmed\",\"when\":[[\"cbo_est_ms\",\">\",1200],"
        + "[\"has_temporal\",\"==\",true]],\"set\":{\"meta_probe\":\"on\"}}]");
    TrialResult hit = armRun(new LlmRewriteArm("llm", -1e12, 1e12, 8000, near, ptz()), metaOn(calls));
    assertEquals("NEAR_THEN_LLM", hit.extras.get("gate_decision"), String.valueOf(hit.extras));
    assertEquals(Boolean.TRUE, hit.extras.get("rewrite_adopted"));
    assertEquals(1, calls.get());
    LlmRewriteArm.clearMemo();
  }

  @Test void ragEvidenceDrivesKnnAndPrompt() throws Exception {
    LlmRewriteArm.clearMemo();
    StringBuilder mem = new StringBuilder("[");
    for (String q : new String[] {"TRAJECTORY_IDS", "TOP_K"}) {
      mem.append(mem.length() > 1 ? "," : "").append("{\"query_id\":\"train_").append(q).append("\",\"query\":\"")
          .append(q).append("\",\"features\":{\"cbo_plan\":\"P_TZ\",\"cbo_est_ms\":100},")
          .append("\"measured\":[{\"program\":{\"meta_probe\":\"on\"},\"delta_ms\":-100}],\"best\":{\"meta_probe\":\"on\"}}");
    }
    ActionMemory memory = new ActionMemory(M.readTree(mem.append("]").toString()));
    TrialResult knn = armRun(new LlmRewriteArm("knn", -1e12, -1e12, 8000, null, ptz(), memory), null);
    assertEquals("KNN", knn.extras.get("gate_decision"), String.valueOf(knn.extras));
    assertEquals(Boolean.TRUE, knn.extras.get("program_evidence_backed"));
    assertTrue(knn.plan_id.contains("+MP"), knn.plan_id);

    StringBuilder prompt = new StringBuilder();
    LlmClient spy = (m, s, o) -> {
      prompt.append(m.get(1).content);
      return new LlmResponse("{}");
    };
    TrialResult llm = armRun(new LlmRewriteArm("llm", -1e12, -1e12, 8000, null, ptz(), memory), spy);
    assertEquals("CALL_PARALLEL", llm.extras.get("gate_decision"));
    assertTrue(prompt.toString().contains("similar past queries") && prompt.toString().contains("meta_probe=on -100"),
        prompt.toString());
    LlmRewriteArm.clearMemo();
  }

  @Test void validatorRejectionYieldsNoExecutableHandle() throws Exception {
    PlanEnvelope bad = ptz();
    for (PlanNode n : bad.nodes) {
      if (n.op == Op.INTERSECT) {
        n.params.put("provisional_merge", Boolean.TRUE);
      }
    }
    try (MemoryBackend kv = SnapshotBuilder.buildFixtureInMemory()) {
      QueryEngine engine = new QueryEngine(kv, LayoutContext.from(FixtureBuilder.fixtureManifest()));
      QueryEngine.RunResult rr;
      try {
        rr = engine.runFixed(ir, null, true, bad, QueryEngine.PrepareMode.SAFETY_ONLY);
      } catch (Exception e) {
        rr = null;
      }
      assertTrue(rr == null || rr.selected == null);
    }
  }
}
