package kart.bench;

import com.fasterxml.jackson.databind.node.ObjectNode;
import kart.compile.LayoutContext;
import kart.cost.BenefitCalibrator;
import kart.cost.FastCost;
import kart.ir.BoundIr;
import kart.plan.Op;
import kart.plan.PlanBuilder;
import kart.plan.PlanEnvelope;
import kart.plan.PlanNode;
import kart.rewrite.DualRewritePolicy;
import kart.rewrite.RewriteProtocol;
import kart.rewrite.SafeRewriteBuilder;
import kart.search.IndexId;
import kart.snapshot.FixtureBuilder;
import kart.snapshot.FixtureQueries;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Phase A: advanced indexes survive dual rewrite (keep + range_budget). */
class AdvancedRewriteSurvivalTest {

  @Test
  void buildForAccessNamesMatchIndexesNotReplaceBug() {
    BoundIr ir = FixtureQueries.fixtureTopK();
    assertEquals("P_XZTXZ",
        PlanBuilder.buildForAccess(ir, true, true, false, Op.XZT_RANGE_SCAN, Op.XZ_RANGE_SCAN, null, null).plan_id);
    assertEquals("P_XZTTS",
        PlanBuilder.buildForAccess(ir, true, true, false, Op.XZT_RANGE_SCAN, Op.TSHAPE_RANGE_SCAN, null, null).plan_id);
    assertEquals("P_XZTZ",
        PlanBuilder.buildForAccess(ir, true, true, false, Op.XZT_RANGE_SCAN, Op.ZORDER_RANGE_SCAN, null, null).plan_id);
    assertEquals("P_XZTZH",
        PlanBuilder.buildForAccess(ir, true, true, true, Op.XZT_RANGE_SCAN, Op.ZORDER_RANGE_SCAN, null, null).plan_id);
    assertEquals("P_TZ", DualRewritePolicy.aliasCboPlan("P_XZTZ"));
    assertEquals("P_TZ", DualRewritePolicy.aliasCboPlan("P_XZTXZ"));
    assertEquals("P_TZH", DualRewritePolicy.aliasCboPlan("P_XZTZH"));
  }

  @Test
  void branchIdsAndDropPreserveAdvancedOps() throws Exception {
    BoundIr ir = FixtureQueries.fixtureTopK();
    PlanEnvelope xzTxz = PlanBuilder.buildForIndexes(ir, Arrays.asList(IndexId.XZT, IndexId.XZ), "HASH_SET", null);
    assertEquals("P_XZTXZ", xzTxz.plan_id);
    assertEquals(new java.util.HashSet<>(Arrays.asList("temporal_branch", "spatial_branch")),
        SafeRewriteBuilder.removableBranches(xzTxz).keySet());
    PlanEnvelope xzt = SafeRewriteBuilder.removeAccessBranch(xzTxz, "spatial_branch", ir);
    assertEquals("P_XZT", xzt.plan_id);
    assertTrue(xzt.nodes.stream().anyMatch(n -> n.op == Op.XZT_RANGE_SCAN));
    PlanEnvelope xz = SafeRewriteBuilder.removeAccessBranch(xzTxz, "temporal_branch", ir);
    assertEquals("P_XZ", xz.plan_id);
    assertTrue(xz.nodes.stream().anyMatch(n -> n.op == Op.XZ_RANGE_SCAN));
  }

  @Test
  void keepAccessPreservesAdvancedPlanIds() throws Exception {
    BoundIr ir = FixtureQueries.fixtureTopK();
    LayoutContext layout = LayoutContext.from(FixtureBuilder.fixtureManifest());
    BenefitCalibrator cal = new BenefitCalibrator(new FastCost(layout, null), layout);
    List<List<String>> specs = Arrays.asList(
        Collections.singletonList(IndexId.XZ),
        Collections.singletonList(IndexId.TSHAPE),
        Collections.singletonList(IndexId.XZT),
        Arrays.asList(IndexId.XZT, IndexId.XZ));
    for (List<String> idxs : specs) {
      String merge = idxs.size() >= 2 ? "HASH_SET" : null;
      PlanEnvelope env = PlanBuilder.buildForIndexes(ir, idxs, merge, null);
      ObjectNode ctx = LlmRewriteArm.context(ir, env, cal, layout);
      assertEquals(env.plan_id, ctx.path("features").path("cbo_plan").asText(), env.plan_id);
      assertEquals(DualRewritePolicy.aliasCboPlan(env.plan_id),
          ctx.path("features").path("cbo_plan_alias").asText(), env.plan_id);
      Map<String, String> def = RewriteProtocol.defaults(ctx).choice;
      Map<String, String> keep = new TreeMap<>(def);
      keep.put(RewriteProtocol.ACCESS, "keep");
      PlanEnvelope built = LlmRewriteArm.build(ir, env, keep, def);
      assertEquals(env.plan_id, built.plan_id, "keep must not rewrite " + env.plan_id);
    }
  }

  @Test
  void rangeBudgetAppliesToAdvancedSpatialOps() throws Exception {
    BoundIr ir = FixtureQueries.fixtureTopK();
    for (String idx : new String[] {IndexId.XZ, IndexId.TSHAPE}) {
      PlanEnvelope env = PlanBuilder.buildForIndexes(ir, Collections.singletonList(idx), null, null);
      Map<String, String> def = new HashMap<>();
      def.put("range_budget", "r64");
      Map<String, String> prog = new HashMap<>();
      prog.put(RewriteProtocol.ACCESS, "keep");
      prog.put("range_budget", "r16");
      PlanEnvelope built = LlmRewriteArm.build(ir, env, prog, def);
      assertEquals(env.plan_id + "+R16", built.plan_id);
      Op want = IndexId.XZ.equals(idx) ? Op.XZ_RANGE_SCAN : Op.TSHAPE_RANGE_SCAN;
      PlanNode n = null;
      for (PlanNode x : built.nodes) {
        if (x.op == want) {
          n = x;
          break;
        }
      }
      assertNotNull(n, idx);
      assertEquals(16, ((Number) n.params.get("max_ranges")).intValue());
    }
  }

  @Test
  void oldAliasWhenMatchesRealAdvancedPlan() {
    com.fasterxml.jackson.databind.ObjectMapper m = new com.fasterxml.jackson.databind.ObjectMapper();
    ObjectNode f = m.createObjectNode();
    f.put("cbo_plan", "P_XZ");
    f.put("cbo_plan_alias", "P_Z");
    // mayHold is a conservative search gate and may over-approximate aliases;
    // exact()/related() perform the strict match used before applying a rewrite.
    assertTrue(mayHoldOn(m, f, "P_Z"));
    assertTrue(mayHoldOn(m, f, "P_XZ"));
  }

  @Test
  void exactAndRelatedRulesDoNotAliasClassicRulesOntoTs() {
    com.fasterxml.jackson.databind.ObjectMapper m = new com.fasterxml.jackson.databind.ObjectMapper();
    com.fasterxml.jackson.databind.node.ArrayNode rules = m.createArrayNode();
    ObjectNode legacy = rules.addObject();
    legacy.put("id", "legacy_p_z");
    legacy.put("status", "confirmed");
    legacy.put("admission", "train_measured_v1");
    legacy.put("support", 3);
    legacy.put("source_sha256", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
    legacy.putArray("when").add(m.createArrayNode().add("cbo_plan").add("==").add("P_Z"));
    legacy.putObject("set").put("range_budget", "r256");

    ObjectNode c = RewriteProtocol.newContext("TRAJECTORY_IDS");
    ((ObjectNode) c.get("features")).put("cbo_plan", "P_TS").put("cbo_plan_alias", "P_Z");
    RewriteProtocol.addSlot(c, "access", "keep").put("drop_spatial_branch", -80);
    RewriteProtocol.addSlot(c, "range_budget", "r64").put("r256", -150);

    assertTrue(DualRewritePolicy.exact(rules, c) == null);
    assertEquals(0, DualRewritePolicy.related(rules, c).size());
    assertTrue(!DualRewritePolicy.mayHaveRuleForPlan(rules, "P_TS"),
        "a plan-specific P_Z rule cannot justify building a rewrite context for P_TS");
    assertTrue(DualRewritePolicy.mayHaveRuleForPlan(rules, "P_Z"),
        "classic plans retain their existing rewrite path");

    ObjectNode generic = rules.addObject();
    generic.put("id", "generic_resource_rule");
    generic.put("status", "confirmed");
    generic.put("admission", "train_measured_v1");
    generic.put("support", 3);
    generic.put("source_sha256", "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc");
    generic.putArray("when").add(m.createArrayNode().add("chunks_keep").add(">=").add(1));
    generic.putObject("set").put("fetch_batch", "b2000");
    assertTrue(DualRewritePolicy.mayHaveRuleForPlan(rules, "P_TS"),
        "a rule without an exact plan constraint may still apply to an advanced plan");

    ObjectNode tsRule = rules.addObject();
    tsRule.put("id", "ts_exact");
    tsRule.put("status", "confirmed");
    tsRule.put("admission", "train_measured_v1");
    tsRule.put("support", 3);
    tsRule.put("source_sha256", "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
    tsRule.putArray("when").add(m.createArrayNode().add("cbo_plan").add("==").add("P_TS"));
    tsRule.putObject("set").put("range_budget", "r256");
    assertTrue(DualRewritePolicy.mayHaveRuleForPlan(rules, "P_TS"),
        "an explicitly trained advanced-plan rule must retain the full rewrite path");
  }

  @Test
  void exactPlanRuleBeatsAliasHijack() throws Exception {
    com.fasterxml.jackson.databind.ObjectMapper m = new com.fasterxml.jackson.databind.ObjectMapper();
    com.fasterxml.jackson.databind.node.ArrayNode rules = m.createArrayNode();
    ObjectNode alias = rules.addObject();
    alias.put("id", "adm_p_tz_alias");
    alias.put("status", "confirmed");
    alias.put("admission", "train_measured_v1");
    alias.put("support", 9);
    alias.put("source_sha256", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
    alias.putArray("when").add(m.createArrayNode().add("cbo_plan").add("==").add("P_TZ"));
    alias.putObject("set").put("fetch_batch", "b2000").put("range_budget", "r256");
    ObjectNode exact = rules.addObject();
    exact.put("id", "adm_p_xztz_exact");
    exact.put("status", "confirmed");
    exact.put("admission", "train_measured_v1");
    exact.put("support", 3);
    exact.put("source_sha256", "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
    exact.putArray("when")
        .add(m.createArrayNode().add("cbo_plan").add("==").add("P_XZTZ"))
        .add(m.createArrayNode().add("chunks_keep").add(">=").add(50));
    exact.putObject("set").put(RewriteProtocol.ACCESS, "drop_spatial_branch")
        .put("fetch_batch", "b2000").put("meta_probe", "on");
    ObjectNode c = RewriteProtocol.newContext("TRAJECTORY_IDS");
    ((ObjectNode) c.get("features")).put("cbo_plan", "P_XZTZ").put("cbo_plan_alias", "P_TZ")
        .put("chunks_keep", 80);
    RewriteProtocol.addSlot(c, "access", "keep").put("drop_spatial_branch", -80);
    RewriteProtocol.addSlot(c, "range_budget", "r64").put("r256", -150);
    RewriteProtocol.addSlot(c, "fetch_batch", "b500").put("b2000", -20);
    RewriteProtocol.addSlot(c, "meta_probe", "off").putObject("on").put("drop_spatial_branch", -10);
    RewriteProtocol.Program p = DualRewritePolicy.exact(rules, c);
    assertNotNull(p);
    assertTrue(p.status.contains("adm_p_xztz_exact"), p.status);
    assertEquals("drop_spatial_branch", p.choice.get(RewriteProtocol.ACCESS));
  }

  private static boolean mayHoldOn(com.fasterxml.jackson.databind.ObjectMapper m, ObjectNode feats, String plan) {
    com.fasterxml.jackson.databind.node.ArrayNode rules = m.createArrayNode();
    ObjectNode r = rules.addObject();
    r.put("status", "confirmed");
    r.put("admission", "train_measured_v1");
    r.put("support", 3);
    r.put("source_sha256", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
    r.putArray("when").add(m.createArrayNode().add("cbo_plan").add("==").add(plan));
    r.putObject("set").put("range_budget", "r256");
    return DualRewritePolicy.mayHold(rules, feats, false);
  }
}
