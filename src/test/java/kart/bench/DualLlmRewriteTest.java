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
import kart.query.QueryIrFixtureTest;
import kart.rewrite.*;
import kart.snapshot.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class DualLlmRewriteTest {
  @TempDir Path root;
  static final ObjectMapper M = new ObjectMapper();
  final BoundIr ir = QueryIrFixtureTest.fixtureQuery();

  static ArrayNode rules(boolean exact) throws Exception {
    ObjectNode r = (ObjectNode) M.readTree("{\"id\":\"offline_access\",\"status\":\"confirmed\","
        + "\"admission\":\"train_measured_v1\",\"support\":3,\"source_sha256\":\""
        + String.join("", Collections.nCopies(64, "a")) + "\",\"when\":[[\"cbo_plan\",\"==\",\"P_TZ\"],"
        + "[\"chunks_keep\",\">\"," + (exact ? "0" : "1000000000") + "]],\"set\":{\"access\":\"drop_spatial_branch\"}}");
    JsonNode manifest = M.valueToTree(FixtureBuilder.fixtureManifest());
    ObjectNode scope = r.putObject("scope");
    for (String k : new String[] {"manifest_id", "semantics_version", "stats_version", "dataset", "layout"})
      scope.set(k, manifest.has(k) ? manifest.get(k) : M.nullNode());
    return (ArrayNode) M.readTree(M.createArrayNode().add(r).toString());
  }

  TrialResult run(LlmRewriteArm arm, LlmClient client) throws Exception {
    try (MemoryBackend kv = SnapshotBuilder.buildFixtureInMemory()) {
      BenchContext c = new BenchContext(root, root, "dual-test", "compare", "dual", "e2e", false, true,
          false, "warm", FixtureBuilder.fixtureManifest(), LayoutContext.from(FixtureBuilder.fixtureManifest()),
          null, AppConfig.PlannerConfig.defaults(), kv, client, null, null, null);
      TrialResult r = arm.run(ir, c);
      assertNull(r.error);
      FullScanOracle.Answer expected = FullScanOracle.forFixture().evaluate(ir);
      assertEquals(expected.trajectoryIds, r.queryResult.trajectoryIds);
      if (expected.topK != null) {
        assertEquals(expected.topK.size(), r.queryResult.topK.size());
        for (int i = 0; i < expected.topK.size(); i++) {
          assertEquals(expected.topK.get(i).tid, r.queryResult.topK.get(i).tid);
          assertEquals(expected.topK.get(i).distance, r.queryResult.topK.get(i).distance, 1e-9);
        }
      }
      assertEquals(r.t_e2e_ms, r.extras.get("t_wall_ms"));
      return r;
    }
  }

  LlmRewriteArm arm(JsonNode rules, double modelMs) {
    return new LlmRewriteArm("dual", -1e12, 0, 1000, rules,
        PlanBuilder.buildForAccess(ir, true, true, false), null, modelMs, 0, 0);
  }

  @Test void exactOfflineRuleRevalidatesWithoutModel() throws Exception {
    TrialResult r = run(arm(rules(true), 1), (m, s, o) -> { fail("offline hit must not call"); return null; });
    Object g = r.extras.get("gate_decision");
    assertEquals("OFFLINE_RULE", g, String.valueOf(r.extras));
    assertEquals("offline_llm_rule", r.extras.get("final_selection_source"));
    assertEquals(0, r.extras.get("llm_calls"));
    assertEquals(Boolean.TRUE, r.extras.get("validation_ok"));
    assertTrue(r.plan_id.startsWith("P_T"), r.plan_id);
  }

  static ArrayNode resourceMiss() throws Exception {
    ArrayNode rs = rules(false);
    ((ObjectNode) rs.get(0)).putObject("set").put("meta_probe", "on");
    return rs;
  }

  static ArrayNode accessThenMeta() throws Exception {
    ArrayNode rs = rules(true);
    ObjectNode mp = ((ObjectNode) rs.get(0)).deepCopy();
    mp.put("id", "th_meta_probe_on_P_T");
    mp.putObject("set").put("meta_probe", "on");
    mp.set("when", M.createArrayNode()
        .add(M.createArrayNode().add("cbo_plan").add("==").add("P_T"))
        .add(M.createArrayNode().add("chunks_keep").add(">").add(0)));
    rs.add(mp);
    return rs;
  }

  @Test void onlinePatchChangesCurrentRequestAndReusesSignature() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    LlmClient model = (m, s, o) -> {
      calls.incrementAndGet();
      assertFalse(m.get(1).content.contains("options\""), "no cost-ranked option table");
      delay(35);
      return new LlmResponse("{\"meta_probe\":\"on\"}");
    };
    LlmRewriteArm.clearMemo();
    LlmRewriteArm a = arm(accessThenMeta(), 1);
    TrialResult first = run(a, model);
    assertEquals("ONLINE_ADAPT", first.extras.get("gate_decision"), first.extras.toString());
    assertEquals("online_llm_patch", first.extras.get("final_selection_source"));
    assertTrue(first.plan_id.contains("MP"), first.plan_id);
    assertTrue(first.t_plan_ms >= 30 && first.t_e2e_ms >= first.t_plan_ms);
    assertEquals(Boolean.FALSE, first.extras.get("memo_enabled"));
    TrialResult second = run(a, model);
    assertEquals("ONLINE_ADAPT_MEMO", second.extras.get("gate_decision"), second.extras.toString());
    assertEquals("online_llm_patch", second.extras.get("final_selection_source"));
    assertEquals(1, calls.get());
  }

  @Test void invalidAndKeepResponsesFallbackWithoutHiddenReplacement() throws Exception {
    LlmRewriteArm.clearMemo();
    TrialResult keep = run(arm(accessThenMeta(), 1), (m, s, o) -> new LlmResponse("{}"));
    assertEquals(1, keep.extras.get("llm_calls"));
    assertEquals("offline_llm_rule", keep.extras.get("final_selection_source"));
    assertTrue(keep.plan_id.startsWith("P_T"), keep.plan_id);
    for (String response : new String[] {"{\"meta_probe\":\"bad\"}", "{\"fetch_batch\":\"b2000\"}"}) {
      LlmRewriteArm.clearMemo();
      TrialResult r = run(arm(accessThenMeta(), 1), (m, s, o) -> new LlmResponse(response));
      assertEquals("P_TZ", r.plan_id);
      assertEquals(1, r.extras.get("llm_calls"));
      assertEquals("cbo", r.extras.get("final_selection_source"));
    }
  }

  @Test void budgetScopeAndUnadmittedRulesSkipWithoutCalling() throws Exception {
    LlmClient forbidden = (m, s, o) -> { fail("must skip"); return null; };
    ArrayNode changed = rules(true);
    ((ObjectNode) changed.get(0).get("scope")).put("stats_version", "changed");
    assertEquals("no_related_admitted_rule", run(arm(changed, 1), forbidden).extras.get("skip_reason"));
    ArrayNode unadmitted = rules(true);
    ((ObjectNode) unadmitted.get(0)).remove("admission");
    assertEquals(0, run(arm(unadmitted, 1), forbidden).extras.get("llm_calls"));
    assertEquals(0, DualRewritePolicy.budget(100, 10000, 1000, 100, 200, 8000));
    assertEquals(0, DualRewritePolicy.budget(10000, 10000, Double.NaN, 100, 200, 8000));
  }

  @Test void residualEmptyWhenExactAlreadyApplied() throws Exception {
    ArrayNode rs = rules(true);
    ObjectNode c = RewriteProtocol.newContext("TRAJECTORY_IDS");
    ((ObjectNode) c.get("features")).put("cbo_plan", "P_TZ").put("chunks_keep", 4);
    RewriteProtocol.addSlot(c, "access", "keep").put("drop_spatial_branch", -40);
    RewriteProtocol.Program exact = DualRewritePolicy.exact(rs, c);
    assertEquals("drop_spatial_branch", exact.choice.get("access"));
    assertEquals(0, DualRewritePolicy.residual(DualRewritePolicy.related(rs, c), c, exact).size());
    assertEquals(0, DualRewritePolicy.resourceResidual(DualRewritePolicy.related(rs, c), c, exact).size());
  }

  @Test void resourceResidualDropsAccessAndKeepsUnsetMeta() throws Exception {
    ArrayNode rs = resourceMiss();
    ObjectNode c = RewriteProtocol.newContext("TRAJECTORY_IDS");
    ((ObjectNode) c.get("features")).put("cbo_plan", "P_TZ").put("chunks_keep", 4);
    RewriteProtocol.addSlot(c, "meta_probe", "off").putObject("on").put("keep", -20);
    RewriteProtocol.addSlot(c, "access", "keep").put("drop_spatial_branch", -10);
    assertTrue(DualRewritePolicy.resourceResidual(DualRewritePolicy.related(rs, c), c, null).size() > 0);
    Map<String, String> filled = new TreeMap<String, String>(RewriteProtocol.defaults(c).choice);
    filled.put("meta_probe", "on");
    assertEquals(0, DualRewritePolicy.resourceResidual(DualRewritePolicy.related(rs, c), c,
        new RewriteProtocol.Program(filled, "offline")).size());
  }

  @Test void oneOnlineResourceOffersR256AfterPzAccessNotOnTinyOrFetch() throws Exception {
    ArrayNode rs = rules(true);
    ObjectNode r256 = ((ObjectNode) rs.get(0)).deepCopy();
    r256.put("id", "th_range_budget_r256_P_Z");
    r256.putObject("set").put("range_budget", "r256");
    r256.set("when", M.createArrayNode()
        .add(M.createArrayNode().add("cbo_plan").add("==").add("P_Z"))
        .add(M.createArrayNode().add("chunks_min").add(">").add(400)));
    rs.add(r256);
    ObjectNode c = RewriteProtocol.newContext("TRAJECTORY_IDS");
    ((ObjectNode) c.get("features")).put("cbo_plan", "P_T").put("chunks_min", 3100).put("chunks_keep", 5000)
        .put("z_ranges", 64).put("cbo_est_ms", 900);
    RewriteProtocol.addSlot(c, "access", "keep").put("drop_temporal_branch", -80);
    RewriteProtocol.addSlot(c, "range_budget", "r64").put("r256", -40);
    RewriteProtocol.addSlot(c, "fetch_batch", "b500").put("b2000", -20);
    Map<String, String> acc = new TreeMap<String, String>(RewriteProtocol.defaults(c).choice);
    acc.put("access", "drop_temporal_branch");
    RewriteProtocol.Program base = new RewriteProtocol.Program(acc, "related");
    ArrayNode one = DualRewritePolicy.oneOnlineResource(rs, c, base);
    assertEquals(1, one.size());
    assertEquals("r256", one.get(0).path("set").path("range_budget").asText());
    assertTrue(DualRewritePolicy.messages(c, one, base).get(1).content.contains("\"cbo_plan\":\"P_Z\""));
    ((ObjectNode) c.get("features")).put("chunks_min", 50).put("cbo_est_ms", 54);
    assertEquals(0, DualRewritePolicy.oneOnlineResource(rs, c, base).size());
    ObjectNode fetch = ((ObjectNode) rs.get(0)).deepCopy();
    fetch.put("id", "pz_fetch");
    fetch.putObject("set").put("fetch_batch", "b2000");
    fetch.set("when", M.createArrayNode().add(M.createArrayNode().add("cbo_plan").add("==").add("P_Z")));
    rs.add(fetch);
    ((ObjectNode) c.get("features")).put("chunks_min", 50);
    assertEquals(0, DualRewritePolicy.oneOnlineResource(rs, c, base).size());
    Map<String, String> spat = new TreeMap<String, String>(RewriteProtocol.defaults(c).choice);
    spat.put("access", "drop_spatial_branch");
    ((ObjectNode) c.get("features")).put("cbo_plan", "P_TZ").put("chunks_min", 80).put("chunks_keep", 80);
    assertEquals(0, DualRewritePolicy.oneOnlineResource(rs, c, new RewriteProtocol.Program(spat, "exact")).size());
  }

  @Test void instantiateRelatedPicksCloseNumericMissWithoutCalling() throws Exception {
    ArrayNode rs = rules(false);
    ObjectNode c = RewriteProtocol.newContext("TRAJECTORY_IDS");
    ((ObjectNode) c.get("features")).put("cbo_plan", "P_TZ").put("chunks_keep", 9000);
    RewriteProtocol.addSlot(c, "access", "keep").put("drop_spatial_branch", 10);
    assertNull(DualRewritePolicy.instantiateRelated(DualRewritePolicy.related(rs, c), c));
    ((ObjectNode) c.path("slots").path("access").path("options")).put("drop_spatial_branch", -50);
    ((ArrayNode) rs.get(0).get("when")).set(1, M.createArrayNode().add("chunks_keep").add(">=").add(10000));
    RewriteProtocol.Program p = DualRewritePolicy.instantiateRelated(DualRewritePolicy.related(rs, c), c);
    assertEquals("drop_spatial_branch", p.choice.get("access"));
    assertTrue(p.status.startsWith("online_related:"));
  }

  @Test void instantiateRelatedSkipsExactRejectedAndPicksCheaperMiss() throws Exception {
    ObjectNode spatial = (ObjectNode) rules(true).get(0);
    spatial.put("id", "ptz_drop_spatial_branch");
    ObjectNode temporal = spatial.deepCopy();
    temporal.put("id", "ptz_drop_temporal_branch");
    temporal.putObject("set").put("access", "drop_temporal_branch");
    ((ArrayNode) temporal.get("when")).set(1, M.createArrayNode().add("idx_rows_spatial_branch").add("<").add(100000));
    ArrayNode rs = M.createArrayNode().add(spatial).add(temporal);
    ObjectNode c = RewriteProtocol.newContext("TRAJECTORY_IDS");
    ((ObjectNode) c.get("features")).put("cbo_plan", "P_TZ").put("chunks_keep", 4)
        .put("idx_rows_spatial_branch", 600000);
    ObjectNode acc = RewriteProtocol.addSlot(c, "access", "keep");
    acc.put("drop_spatial_branch", 140);
    acc.put("drop_temporal_branch", -600);
    assertNull(DualRewritePolicy.exact(rs, c));
    RewriteProtocol.Program p = DualRewritePolicy.instantiateRelated(DualRewritePolicy.related(rs, c), c);
    assertEquals("drop_temporal_branch", p.choice.get("access"));
    assertEquals("online_related:ptz_drop_temporal_branch", p.status);
  }

  @Test void instantiateRelatedDoesNotBindPzRangeNearMiss() throws Exception {
    ArrayNode rs = rules(false);
    ((ObjectNode) rs.get(0)).put("id", "pz_range_budget_r256");
    ((ObjectNode) rs.get(0)).putObject("set").put("range_budget", "r256");
    ((ArrayNode) rs.get(0).get("when")).set(0, M.createArrayNode().add("cbo_plan").add("==").add("P_Z"));
    ((ArrayNode) rs.get(0).get("when")).set(1, M.createArrayNode().add("chunks_keep").add(">").add(5000));
    ObjectNode c = RewriteProtocol.newContext("TRAJECTORY_IDS");
    ((ObjectNode) c.get("features")).put("cbo_plan", "P_Z").put("chunks_keep", 5000).put("z_ranges", 64);
    RewriteProtocol.addSlot(c, "range_budget", "r64").put("r256", 12);
    assertNull(DualRewritePolicy.instantiateRelated(DualRewritePolicy.related(rs, c), c));
  }

  @Test void exactAccessRuleBindsAfterCboChosePz() throws Exception {
    ArrayNode rs = rules(true);
    ObjectNode c = RewriteProtocol.newContext("TRAJECTORY_IDS");
    ((ObjectNode) c.get("features")).put("cbo_plan", "P_Z").put("chunks_keep", 4);
    RewriteProtocol.addSlot(c, "access", "keep").put("drop_spatial_branch", -80);
    RewriteProtocol.Program p = DualRewritePolicy.exact(rs, c);
    assertNull(p);
  }

  @Test void exactResourceRuleStillRequiresCboPlan() throws Exception {
    ArrayNode rs = rules(false);
    ((ObjectNode) rs.get(0)).putObject("set").put("range_budget", "r256");
    ((ArrayNode) rs.get(0).get("when")).set(0, M.createArrayNode().add("cbo_plan").add("==").add("P_Z"));
    ((ArrayNode) rs.get(0).get("when")).set(1, M.createArrayNode().add("chunks_keep").add(">").add(5000));
    ObjectNode c = RewriteProtocol.newContext("TRAJECTORY_IDS");
    ((ObjectNode) c.get("features")).put("cbo_plan", "P_TZ").put("chunks_keep", 9000);
    RewriteProtocol.addSlot(c, "range_budget", "r64").put("r256", -50);
    assertNull(DualRewritePolicy.exact(rs, c));
  }

  @Test void exactPairsAccessWithCheaperFetch() throws Exception {
    ArrayNode rs = rules(true);
    ObjectNode fetch = ((ObjectNode) rs.get(0)).deepCopy();
    fetch.put("id", "ptz_large_fetch_batch");
    fetch.putObject("set").put("fetch_batch", "b2000");
    ((ArrayNode) fetch.get("when")).set(1, M.createArrayNode().add("chunks_keep").add(">=").add(1));
    rs.add(fetch);
    ObjectNode c = RewriteProtocol.newContext("TRAJECTORY_IDS");
    ((ObjectNode) c.get("features")).put("cbo_plan", "P_TZ").put("chunks_keep", 4);
    RewriteProtocol.addSlot(c, "access", "keep").put("drop_spatial_branch", -10);
    RewriteProtocol.addSlot(c, "fetch_batch", "b500").put("b2000", -50);
    RewriteProtocol.Program p = DualRewritePolicy.exact(rs, c);
    assertEquals("drop_spatial_branch", p.choice.get("access"));
    assertEquals("b2000", p.choice.get("fetch_batch"));
    assertTrue(p.status.contains("ptz_large_fetch_batch"));
  }

  @Test void exactAppliesMeasuredTwoSlotSetOnPz() throws Exception {
    ArrayNode rs = rules(true);
    ObjectNode combo = ((ObjectNode) rs.get(0)).deepCopy();
    combo.put("id", "pz_r256_b2000");
    combo.putObject("set").put("range_budget", "r256").put("fetch_batch", "b2000");
    ((ArrayNode) combo.get("when")).set(0, M.createArrayNode().add("cbo_plan").add("==").add("P_Z"));
    ((ArrayNode) combo.get("when")).set(1, M.createArrayNode().add("chunks_keep").add(">").add(0));
    rs.add(combo);
    ObjectNode c = RewriteProtocol.newContext("TRAJECTORY_IDS");
    ((ObjectNode) c.get("features")).put("cbo_plan", "P_Z").put("chunks_keep", 4000);
    RewriteProtocol.addSlot(c, "range_budget", "r64").put("r256", -40);
    RewriteProtocol.addSlot(c, "fetch_batch", "b500").put("b2000", -20);
    RewriteProtocol.Program p = DualRewritePolicy.exact(rs, c);
    assertEquals("r256", p.choice.get("range_budget"));
    assertEquals("b2000", p.choice.get("fetch_batch"));
    assertTrue(p.status.contains("pz_r256_b2000"));
  }

  @Test void exactPrefersAdmittedRangeOnPz() throws Exception {
    ArrayNode rs = rules(true);
    ObjectNode fetch = ((ObjectNode) rs.get(0)).deepCopy();
    fetch.put("id", "ptz_large_fetch_batch");
    fetch.putObject("set").put("fetch_batch", "b2000");
    ((ArrayNode) fetch.get("when")).set(1, M.createArrayNode().add("chunks_keep").add(">=").add(1));
    rs.add(fetch);
    ObjectNode r256 = ((ObjectNode) rs.get(0)).deepCopy();
    r256.put("id", "pz_range_budget_r256");
    r256.putObject("set").put("range_budget", "r256");
    ((ArrayNode) r256.get("when")).set(0, M.createArrayNode().add("cbo_plan").add("==").add("P_Z"));
    ((ArrayNode) r256.get("when")).set(1, M.createArrayNode().add("chunks_keep").add(">").add(0));
    rs.add(r256);
    ObjectNode c = RewriteProtocol.newContext("TRAJECTORY_IDS");
    ((ObjectNode) c.get("features")).put("cbo_plan", "P_Z").put("chunks_keep", 4);
    RewriteProtocol.addSlot(c, "access", "keep").put("drop_spatial_branch", -10);
    RewriteProtocol.addSlot(c, "fetch_batch", "b500").put("b2000", -20);
    RewriteProtocol.addSlot(c, "range_budget", "r64").put("r256", -80);
    RewriteProtocol.Program p = DualRewritePolicy.exact(rs, c);
    assertEquals("r256", p.choice.get("range_budget"));
    assertNotEquals("drop_spatial_branch", p.choice.get("access"));
    assertNotEquals("b2000", p.choice.get("fetch_batch"));
  }

  @Test void exactPairsAccessFetchWhenTemporalIdxCapMisses() throws Exception {
    ArrayNode rs = rules(true);
    ((ObjectNode) rs.get(0)).put("id", "ptz_drop_spatial_branch");
    ArrayNode when = (ArrayNode) rs.get(0).get("when");
    when.add(M.createArrayNode().add("idx_rows_temporal_branch").add("<").add(800000));
    when.add(M.createArrayNode().add("idx_rows_spatial_branch").add(">").add(100000));
    ObjectNode fetch = ((ObjectNode) rs.get(0)).deepCopy();
    fetch.put("id", "ptz_large_fetch_batch");
    fetch.putObject("set").put("fetch_batch", "b2000");
    fetch.set("when", M.createArrayNode()
        .add(M.createArrayNode().add("cbo_plan").add("==").add("P_TZ"))
        .add(M.createArrayNode().add("chunks_keep").add(">=").add(5000))
        .add(M.createArrayNode().add("z_ranges").add(">=").add(64)));
    rs.add(fetch);
    ObjectNode r256 = ((ObjectNode) rs.get(0)).deepCopy();
    r256.put("id", "pz_range_budget_r256");
    r256.putObject("set").put("range_budget", "r256");
    r256.set("when", M.createArrayNode()
        .add(M.createArrayNode().add("cbo_plan").add("==").add("P_Z"))
        .add(M.createArrayNode().add("z_ranges").add(">=").add(64))
        .add(M.createArrayNode().add("chunks_keep").add(">").add(5000)));
    rs.add(r256);
    ObjectNode c = RewriteProtocol.newContext("TRAJECTORY_IDS");
    ((ObjectNode) c.get("features")).put("cbo_plan", "P_Z").put("chunks_keep", 22100)
        .put("z_ranges", 64).put("idx_rows_temporal_branch", 1273642)
        .put("idx_rows_spatial_branch", 2059014);
    RewriteProtocol.addSlot(c, "access", "keep").put("drop_spatial_branch", -80);
    RewriteProtocol.addSlot(c, "fetch_batch", "b500").put("b2000", -99);
    RewriteProtocol.addSlot(c, "range_budget", "r64").put("r256", -150);
    RewriteProtocol.Program p = DualRewritePolicy.exact(rs, c);
    assertEquals("drop_spatial_branch", p.choice.get("access"));
    assertEquals("b2000", p.choice.get("fetch_batch"));
    assertNotEquals("r256", p.choice.get("range_budget"));
  }

  @Test void exactSkipsFetchOnKeepPt() throws Exception {
    ArrayNode rs = rules(true);
    ((ObjectNode) rs.get(0)).put("id", "ptz_fetch_batch_chunks_gt_1000");
    ((ObjectNode) rs.get(0)).putObject("set").put("fetch_batch", "b2000");
    ObjectNode c = RewriteProtocol.newContext("TRAJECTORY_IDS");
    ((ObjectNode) c.get("features")).put("cbo_plan", "P_T").put("chunks_keep", 4000).put("chunks_min", 2700)
        .put("z_ranges", 34);
    RewriteProtocol.addSlot(c, "access", "keep").put("drop_spatial_branch", -10);
    RewriteProtocol.addSlot(c, "fetch_batch", "b500").put("b2000", -84);
    assertNull(DualRewritePolicy.exact(rs, c));
  }

  @Test void exactBindsFetchOnlyOnMidPtWhenNegative() throws Exception {
    ArrayNode rs = rules(true);
    ((ObjectNode) rs.get(0)).put("id", "ptz_fetch_batch_chunks_gt_1000");
    ((ObjectNode) rs.get(0)).putObject("set").put("fetch_batch", "b2000");
    ObjectNode c = RewriteProtocol.newContext("TRAJECTORY_IDS");
    ((ObjectNode) c.get("features")).put("cbo_plan", "P_T").put("chunks_keep", 4000)
        .put("chunks_min", 2700).put("z_ranges", 34);
    RewriteProtocol.addSlot(c, "fetch_batch", "b500").put("b2000", -50);
    ObjectNode on = RewriteProtocol.addSlot(c, "meta_probe", "off").putObject("on");
    on.put("keep", -90);
    RewriteProtocol.Program p = DualRewritePolicy.exact(rs, c);
    assertEquals("b2000", p.choice.get("fetch_batch"));
    assertNotEquals("on", p.choice.get("meta_probe"));
  }

  @Test void exactSkipsDenseZMidPt() throws Exception {
    ArrayNode rs = rules(true);
    ((ObjectNode) rs.get(0)).put("id", "ptz_fetch_batch_chunks_gt_1000");
    ((ObjectNode) rs.get(0)).putObject("set").put("fetch_batch", "b2000");
    ObjectNode c = RewriteProtocol.newContext("TRAJECTORY_IDS");
    ((ObjectNode) c.get("features")).put("cbo_plan", "P_T").put("chunks_keep", 22400)
        .put("chunks_min", 3900).put("z_ranges", 64);
    RewriteProtocol.addSlot(c, "fetch_batch", "b500").put("b2000", -99);
    ObjectNode on = RewriteProtocol.addSlot(c, "meta_probe", "off").putObject("on");
    on.put("keep", -700);
    assertNull(DualRewritePolicy.exact(rs, c));
  }

  @Test void exactSkipsMidPtFetchWhenEstimateNonNegative() throws Exception {
    ArrayNode rs = rules(true);
    ((ObjectNode) rs.get(0)).put("id", "ptz_fetch_batch_chunks_gt_1000");
    ((ObjectNode) rs.get(0)).putObject("set").put("fetch_batch", "b2000");
    ObjectNode c = RewriteProtocol.newContext("TRAJECTORY_IDS");
    ((ObjectNode) c.get("features")).put("cbo_plan", "P_T").put("chunks_keep", 4000).put("chunks_min", 2700)
        .put("z_ranges", 34);
    RewriteProtocol.addSlot(c, "fetch_batch", "b500").put("b2000", 12);
    assertNull(DualRewritePolicy.exact(rs, c));
  }

  @Test void exactBindsFetchOnlyOnWidePt() throws Exception {
    ArrayNode rs = rules(true);
    ((ObjectNode) rs.get(0)).put("id", "ptz_fetch_batch_chunks_gt_1000");
    ((ObjectNode) rs.get(0)).putObject("set").put("fetch_batch", "b2000");
    ObjectNode c = RewriteProtocol.newContext("TRAJECTORY_IDS");
    ((ObjectNode) c.get("features")).put("cbo_plan", "P_T").put("chunks_keep", 22000).put("chunks_min", 22000);
    RewriteProtocol.addSlot(c, "fetch_batch", "b500").put("b2000", -50);
    RewriteProtocol.Program p = DualRewritePolicy.exact(rs, c);
    assertEquals("b2000", p.choice.get("fetch_batch"));
    assertNotEquals("on", p.choice.get("meta_probe"));
  }

  @Test void exactLeavesSmallPtToOnlineWhenNoTopkRule() throws Exception {
    ArrayNode rs = rules(true);
    ObjectNode c = RewriteProtocol.newContext("TOP_K");
    ((ObjectNode) c.get("features")).put("cbo_plan", "P_T").put("chunks_keep", 80).put("chunks_min", 80).put("k", 3);
    RewriteProtocol.addSlot(c, "topk", "off").put("lb2", -30);
    ObjectNode on = RewriteProtocol.addSlot(c, "meta_probe", "off").putObject("on");
    on.put("keep", -90);
    assertNull(DualRewritePolicy.exact(rs, c));
    RewriteProtocol.Program p = DualRewritePolicy.onlineFill(c, null);
    assertEquals("lb2", p.choice.get("topk"));
    assertNotEquals("on", p.choice.get("meta_probe"));
  }

  @Test void exactStacksAdmittedMetaOnSmallPtTopk() throws Exception {
    ArrayNode rs = rules(true);
    ObjectNode topk = ((ObjectNode) rs.get(0)).deepCopy();
    topk.put("id", "th_topk_lb2_k_le_12");
    topk.putObject("set").put("topk", "lb2");
    topk.set("when", M.createArrayNode().add(M.createArrayNode().add("k").add("<=").add(12)));
    rs.add(topk);
    ObjectNode mp = ((ObjectNode) rs.get(0)).deepCopy();
    mp.put("id", "th_meta_probe_on_P_T_k_le_7");
    mp.putObject("set").put("meta_probe", "on");
    mp.set("when", M.createArrayNode()
        .add(M.createArrayNode().add("cbo_plan").add("==").add("P_T"))
        .add(M.createArrayNode().add("k").add("<=").add(7)));
    rs.add(mp);
    ObjectNode c = RewriteProtocol.newContext("TOP_K");
    ((ObjectNode) c.get("features")).put("cbo_plan", "P_T").put("chunks_keep", 80).put("chunks_min", 50).put("k", 5);
    RewriteProtocol.addSlot(c, "topk", "off").put("lb2", -30);
    ObjectNode on = RewriteProtocol.addSlot(c, "meta_probe", "off").putObject("on");
    on.put("keep", 8);
    RewriteProtocol.Program p = DualRewritePolicy.exact(rs, c);
    assertEquals("lb2", p.choice.get("topk"));
    assertEquals("on", p.choice.get("meta_probe"));
  }

  @Test void dualOfflineRulesKeepsExactWithoutFill() throws Exception {
    ArrayNode rs = rules(true);
    TrialResult r = run(new LlmRewriteArm("dual-offline-rules", -1e12, 0, 1000, rs,
        PlanBuilder.buildForAccess(ir, true, true, false), null, 1, 0, 0),
        (m, s, o) -> { fail("offline hit must not call"); return null; });
    Object g = r.extras.get("gate_decision");
    assertFalse("ONLINE_FILL".equals(g) || "ONLINE_DET".equals(g), String.valueOf(g));
    assertEquals(Boolean.TRUE, r.extras.get("validation_ok"));
  }

  @Test void exactKeepsAccessOnPtzWhenTopkOnlyAlsoMatches() throws Exception {
    ArrayNode rs = rules(true);
    ObjectNode topk = ((ObjectNode) rs.get(0)).deepCopy();
    topk.put("id", "th_topk_lb2_k_le_12");
    topk.putObject("set").put("topk", "lb2");
    topk.set("when", M.createArrayNode().add(M.createArrayNode().add("k").add("<=").add(12)));
    rs.add(topk);
    ObjectNode c = RewriteProtocol.newContext("TOP_K");
    ((ObjectNode) c.get("features")).put("cbo_plan", "P_TZ").put("chunks_keep", 4).put("k", 3);
    RewriteProtocol.addSlot(c, "access", "keep").put("drop_spatial_branch", -80);
    RewriteProtocol.addSlot(c, "topk", "off").put("lb2", -30);
    RewriteProtocol.Program p = DualRewritePolicy.exact(rs, c);
    assertEquals("drop_spatial_branch", p.choice.get("access"));
  }

  @Test void mayHoldRefutesKnownPzMissAndKeepsUnknownPt() throws Exception {
    ArrayNode rs = rules(true);
    ObjectNode r256 = ((ObjectNode) rs.get(0)).deepCopy();
    r256.put("id", "th_range_budget_r256_P_Z_chunks_min_gt_400");
    r256.putObject("set").put("range_budget", "r256");
    r256.set("when", M.createArrayNode()
        .add(M.createArrayNode().add("cbo_plan").add("==").add("P_Z"))
        .add(M.createArrayNode().add("chunks_min").add(">").add(400)));
    rs.add(r256);
    ObjectNode cheap = M.createObjectNode().put("cbo_plan", "P_Z").put("chunks_min", 50);
    assertFalse(DualRewritePolicy.mayHold(rs, cheap, false));
    assertFalse(DualRewritePolicy.mayHoldCheap(rs, M.createObjectNode().put("cbo_plan", "P_Z"), false));
    cheap.put("chunks_min", 3100);
    assertTrue(DualRewritePolicy.mayHold(rs, cheap, false));
    assertTrue(DualRewritePolicy.mayHoldCheap(rs, cheap, false));
    assertTrue(DualRewritePolicy.pzSparseOfflineSkip(false, 4));
    assertFalse(DualRewritePolicy.pzSparseOfflineSkip(false, 64));
    assertFalse(DualRewritePolicy.pzSparseOfflineSkip(true, 4));
    ObjectNode idx = ((ObjectNode) rs.get(0)).deepCopy();
    idx.put("id", "th_meta_probe_on_P_T_idx");
    idx.putObject("set").put("meta_probe", "on");
    idx.set("when", M.createArrayNode()
        .add(M.createArrayNode().add("cbo_plan").add("==").add("P_T"))
        .add(M.createArrayNode().add("idx_rows_spatial_branch").add("<=").add(44180)));
    ArrayNode pt = M.createArrayNode().add(idx);
    assertTrue(DualRewritePolicy.mayHold(pt, M.createObjectNode().put("cbo_plan", "P_T"), false));
  }

  @Test void exactDoesNotBindTopkOnlyOnPtz() throws Exception {
    ArrayNode rs = rules(true);
    ((ObjectNode) rs.get(0)).put("id", "th_topk_lb2_k_le_12");
    ((ObjectNode) rs.get(0)).putObject("set").put("topk", "lb2");
    ((ObjectNode) rs.get(0)).set("when", M.createArrayNode().add(M.createArrayNode().add("k").add("<=").add(12)));
    ObjectNode c = RewriteProtocol.newContext("TOP_K");
    ((ObjectNode) c.get("features")).put("cbo_plan", "P_TZ").put("chunks_keep", 4).put("k", 3);
    RewriteProtocol.addSlot(c, "topk", "off").put("lb2", -30);
    assertNull(DualRewritePolicy.exact(rs, c));
  }

  @Test void exactAllowsMidPtWhenChunksKeepInflated() throws Exception {
    ArrayNode rs = rules(true);
    ((ObjectNode) rs.get(0)).put("id", "ptz_fetch_batch_chunks_gt_1000");
    ((ObjectNode) rs.get(0)).putObject("set").put("fetch_batch", "b2000");
    ObjectNode c = RewriteProtocol.newContext("TRAJECTORY_IDS");
    ((ObjectNode) c.get("features")).put("cbo_plan", "P_T").put("chunks_keep", 18700)
        .put("chunks_min", 2700).put("z_ranges", 34);
    RewriteProtocol.addSlot(c, "fetch_batch", "b500").put("b2000", -84);
    RewriteProtocol.Program p = DualRewritePolicy.exact(rs, c);
    assertEquals("b2000", p.choice.get("fetch_batch"));
  }

  @Test void exactDoesNotStandaloneFetchOnPz() throws Exception {
    ArrayNode rs = rules(true);
    ((ObjectNode) rs.get(0)).putObject("set").put("fetch_batch", "b2000");
    ObjectNode c = RewriteProtocol.newContext("TRAJECTORY_IDS");
    ((ObjectNode) c.get("features")).put("cbo_plan", "P_Z").put("chunks_keep", 4);
    RewriteProtocol.addSlot(c, "fetch_batch", "b500").put("b2000", -50);
    assertNull(DualRewritePolicy.exact(rs, c));
  }

  @Test void exactStacksMetaWithAccessFetchOnPtz() throws Exception {
    ArrayNode rs = rules(true);
    ObjectNode fetch = ((ObjectNode) rs.get(0)).deepCopy();
    fetch.put("id", "ptz_large_fetch_batch");
    fetch.putObject("set").put("fetch_batch", "b2000");
    ((ArrayNode) fetch.get("when")).set(1, M.createArrayNode().add("chunks_keep").add(">=").add(1));
    rs.add(fetch);
    ObjectNode c = RewriteProtocol.newContext("TRAJECTORY_IDS");
    ((ObjectNode) c.get("features")).put("cbo_plan", "P_TZ").put("chunks_keep", 4);
    RewriteProtocol.addSlot(c, "access", "keep").put("drop_spatial_branch", -10);
    RewriteProtocol.addSlot(c, "fetch_batch", "b500").put("b2000", -20);
    ObjectNode on = RewriteProtocol.addSlot(c, "meta_probe", "off").putObject("on");
    on.put("keep", 8);
    on.put("drop_spatial_branch", -80);
    RewriteProtocol.Program p = DualRewritePolicy.exact(rs, c);
    assertEquals("drop_spatial_branch", p.choice.get("access"));
    assertEquals("b2000", p.choice.get("fetch_batch"));
    assertEquals("on", p.choice.get("meta_probe"));
  }

  @Test void exactDoesNotPairTemporalDropFetchOnPt() throws Exception {
    ArrayNode rs = rules(true);
    ((ObjectNode) rs.get(0)).put("id", "ptz_drop_temporal_branch");
    ((ObjectNode) rs.get(0)).putObject("set").put("access", "drop_temporal_branch");
    ObjectNode fetch = ((ObjectNode) rs.get(0)).deepCopy();
    fetch.put("id", "ptz_large_fetch_batch");
    fetch.putObject("set").put("fetch_batch", "b2000");
    fetch.set("when", M.createArrayNode()
        .add(M.createArrayNode().add("cbo_plan").add("==").add("P_TZ"))
        .add(M.createArrayNode().add("chunks_keep").add(">=").add(1)));
    rs.add(fetch);
    ObjectNode c = RewriteProtocol.newContext("TRAJECTORY_IDS");
    ((ObjectNode) c.get("features")).put("cbo_plan", "P_T").put("chunks_keep", 4000)
        .put("chunks_min", 2700).put("z_ranges", 34);
    RewriteProtocol.addSlot(c, "access", "keep").put("drop_temporal_branch", -40);
    RewriteProtocol.addSlot(c, "fetch_batch", "b500").put("b2000", -50);
    RewriteProtocol.Program p = DualRewritePolicy.exact(rs, c);
    assertNull(p);
  }

  @Test void exactDoesNotStackAccessMetaOnAlreadyPt() throws Exception {
    ArrayNode rs = rules(true);
    ObjectNode c = RewriteProtocol.newContext("TRAJECTORY_IDS");
    ((ObjectNode) c.get("features")).put("cbo_plan", "P_T").put("chunks_keep", 4);
    RewriteProtocol.addSlot(c, "access", "keep").put("drop_spatial_branch", 0);
    ObjectNode on = RewriteProtocol.addSlot(c, "meta_probe", "off").putObject("on");
    on.put("keep", 8);
    on.put("drop_spatial_branch", -80);
    assertNull(DualRewritePolicy.exact(rs, c));
  }

  @Test void exactAppliesPzRangeBudgetEvenIfEstimateNonNegative() throws Exception {
    ArrayNode rs = rules(true);
    ObjectNode r = (ObjectNode) rs.get(0);
    r.put("id", "pz_range_budget_r256");
    r.set("when", M.readTree("[[\"cbo_plan\",\"==\",\"P_Z\"],[\"z_ranges\",\">=\",64],[\"chunks_keep\",\">\",5000]]"));
    r.putObject("set").put("range_budget", "r256");
    ObjectNode c = RewriteProtocol.newContext("TRAJECTORY_IDS");
    ((ObjectNode) c.get("features")).put("cbo_plan", "P_Z").put("chunks_keep", 6171).put("z_ranges", 64);
    RewriteProtocol.addSlot(c, "range_budget", "r64").put("r256", 12);
    RewriteProtocol.Program p = DualRewritePolicy.exact(rs, c);
    assertEquals("r256", p.choice.get("range_budget"));
    assertNotEquals("on", p.choice.get("meta_probe"));
  }

  @Test void exactDoesNotAttachMetaOnPzSpatial() throws Exception {
    ArrayNode rs = rules(true);
    ObjectNode c = RewriteProtocol.newContext("TRAJECTORY_IDS");
    ((ObjectNode) c.get("features")).put("cbo_plan", "P_Z").put("chunks_keep", 4);
    RewriteProtocol.addSlot(c, "access", "keep").put("drop_spatial_branch", -80);
    ObjectNode on = RewriteProtocol.addSlot(c, "meta_probe", "off").putObject("on");
    on.put("keep", 8);
    on.put("drop_spatial_branch", -90);
    RewriteProtocol.Program p = DualRewritePolicy.exact(rs, c);
    assertNull(p);
  }

  @Test void onlineFillPicksBestResourceOnMidPt() throws Exception {
    ObjectNode c = RewriteProtocol.newContext("TRAJECTORY_IDS");
    ((ObjectNode) c.get("features")).put("cbo_plan", "P_T").put("chunks_keep", 4000).put("z_ranges", 34);
    RewriteProtocol.addSlot(c, "access", "keep").put("drop_spatial_branch", 0);
    ObjectNode on = RewriteProtocol.addSlot(c, "meta_probe", "off").putObject("on");
    on.put("keep", -40);
    RewriteProtocol.addSlot(c, "fetch_batch", "b500").put("b2000", -80);
    RewriteProtocol.Program p = DualRewritePolicy.onlineFill(c, null);
    assertEquals("b2000", p.choice.get("fetch_batch"));
    assertNotEquals("on", p.choice.get("meta_probe"));
  }

  @Test void onlineFillSkipsMetaFetchOnWidePt() throws Exception {
    ObjectNode c = RewriteProtocol.newContext("TRAJECTORY_IDS");
    ((ObjectNode) c.get("features")).put("cbo_plan", "P_T").put("chunks_keep", 22000).put("chunks_min", 22000);
    ObjectNode on = RewriteProtocol.addSlot(c, "meta_probe", "off").putObject("on");
    on.put("keep", -40);
    RewriteProtocol.addSlot(c, "fetch_batch", "b500").put("b2000", -80);
    assertNull(DualRewritePolicy.onlineFill(c, null));
  }

  @Test void onlineFillAddsTopkOnOfflineExact() throws Exception {
    ObjectNode c = RewriteProtocol.newContext("TOP_K");
    ((ObjectNode) c.get("features")).put("cbo_plan", "P_TZ").put("chunks_keep", 4).put("k", 3);
    RewriteProtocol.addSlot(c, "access", "keep").put("drop_spatial_branch", -10);
    RewriteProtocol.addSlot(c, "topk", "off").put("lb2", -30);
    Map<String, String> base = new TreeMap<String, String>();
    base.put("access", "drop_spatial_branch");
    RewriteProtocol.Program exact = new RewriteProtocol.Program(base, "offline_rule:x");
    RewriteProtocol.Program p = DualRewritePolicy.onlineFill(c, exact);
    assertEquals("drop_spatial_branch", p.choice.get("access"));
    assertEquals("lb2", p.choice.get("topk"));
  }

  @Test void sparseProtocolRejectsDuplicateKeysTrailingTokensAndUnexposedSavings() throws Exception {
    ObjectNode c = RewriteProtocol.newContext("TOP_K");
    ((ObjectNode) c.get("features")).put("cbo_plan", "P_TZ").put("chunks_keep", 4);
    RewriteProtocol.addSlot(c, "meta_probe", "off").put("on", -50);
    RewriteProtocol.addSlot(c, "topk", "off").put("lb2", -10000);
    ArrayNode sampleRules = rules(false);
    ((ObjectNode) sampleRules.get(0)).putObject("set").put("meta_probe", "on");
    ArrayNode rel = DualRewritePolicy.related(sampleRules, c);
    assertEquals(50, DualRewritePolicy.potential(c, rel), 0.001);
    for (String invalid : new String[] {"{\"meta_probe\":\"off\",\"meta_probe\":\"on\"}",
        "{} {}", "{\"topk\":\"lb2\"}", "{\"meta_probe\":1}"})
      assertNull(DualRewritePolicy.parse(invalid, c, rel).choice);
    assertTrue(DualRewritePolicy.parse("{}", c, rel).isDefault(c));
  }

  @Test void compactPromptIsCanonicalAndOmitsUnrelatedFeatures() throws Exception {
    ObjectNode c = RewriteProtocol.newContext("TOP_K");
    ((ObjectNode) c.get("features")).put("chunks_keep", 4).put("cbo_plan", "P_TZ").put("unused_est_ms", 10000);
    RewriteProtocol.addSlot(c, "access", "keep").put("drop_spatial_branch", -50);
    ArrayNode rel = DualRewritePolicy.related(rules(false), c);
    ObjectNode reordered = c.deepCopy();
    reordered.putObject("features").put("unused_est_ms", 10000).put("cbo_plan", "P_TZ").put("chunks_keep", 4);
    assertEquals(DualRewritePolicy.messages(c, rel).get(1).content,
        DualRewritePolicy.messages(reordered, rel).get(1).content);
    assertFalse(DualRewritePolicy.messages(c, rel).get(1).content.contains("unused_est_ms"));
    assertFalse(DualRewritePolicy.messages(c, rel).get(1).content.contains("allowed_patch"));
  }

  @Test void legacyParallelResponseWallIncludesModelWait() throws Exception {
    LlmRewriteArm legacy = new LlmRewriteArm("llm", -1e12, 1e12, 1000, null,
        PlanBuilder.buildForAccess(ir, true, true, false));
    LlmRewriteArm.clearMemo();
    TrialResult r = run(legacy, (m, s, o) -> { delay(120); return new LlmResponse("{}"); });
    assertTrue(r.t_e2e_ms >= 110, r.extras.toString());
    assertEquals("response_wall_after_model_v2", r.extras.get("timing_version"));
    LlmRewriteArm.clearMemo();
  }

  static void delay(long millis) throws LlmException {
    try { Thread.sleep(millis); }
    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new LlmException("interrupted"); }
  }
}
