package kart.search;

import kart.catalog.StatsSnapshot;
import kart.compile.LayoutContext;
import kart.cost.CostCard;
import kart.cost.FastCost;
import kart.geo.Rect;
import kart.ir.BoundIr;
import kart.llm.ScriptedLlmClient;
import kart.plan.Op;
import kart.plan.PlanBuilder;
import kart.plan.PlanEnvelope;
import kart.plan.PlanNode;
import kart.query.QueryIrFixtureTest;
import kart.snapshot.FixtureBuilder;
import kart.snapshot.IndexBuilders;
import kart.snapshot.StatsBuilder;
import kart.validation.ValidationReport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P4 acceptance: T4.1–T4.6.
 */
public class BeamSearchAcceptanceTest {

  private LayoutContext layout;
  private FastCost fastCost;
  private StatsSnapshot stats;

  @BeforeEach
  void setUp() {
    layout = LayoutContext.from(FixtureBuilder.fixtureManifest());
    IndexBuilders.LayoutParams lp = new IndexBuilders.LayoutParams();
    lp.epochMs = FixtureBuilder.T0;
    lp.domain = new Rect(0, 0, 100, 100);
    stats = new StatsBuilder(lp).build(FixtureBuilder.MANIFEST_ID, FixtureBuilder.trajectories());
    if (stats.sample_chunks.isEmpty()) {
      StatsSnapshot.SampleChunk sc = new StatsSnapshot.SampleChunk();
      sc.tid = 1;
      sc.chunk_id = 0;
      stats.sample_chunks.add(sc);
    }
    fastCost = new FastCost(layout, stats);
  }

  @Test
  void t41_temporalOnlyHasNoZorderActions() {
    BoundIr ir = QueryIrFixtureTest.fixtureQuery();
    ir.spatial = null;
    SearchState empty = SearchState.initial(ir);
    List<LegalAction> legal = new LegalActionGenerator().generate(empty);
    Set<String> ids = actionIds(legal);
    assertTrue(ids.contains("start_idx_time"));
    assertFalse(ids.contains("start_idx_zorder"));
    assertTrue(ids.contains("finish"));

    SearchState withTime = empty.apply(LegalActionGenerator.start(IndexId.TIME));
    List<LegalAction> after = new LegalActionGenerator().generate(withTime);
    Set<String> afterIds = actionIds(after);
    assertFalse(afterIds.contains("start_idx_time"), "used index must not START again");
    assertFalse(afterIds.contains("intersect_idx_zorder"));
    assertTrue(afterIds.contains("finish"));
  }

  @Test
  void t42_rulePolicyFamiliesMatchPlanBuilder() {
    BoundIr ir = QueryIrFixtureTest.fixtureQuery();
    BeamSearch search = new BeamSearch(layout, fastCost);
    SearchBudget budget = SearchBudget.defaults();
    SearchResult result = search.search(ir, new RulePolicy(), budget);

    Set<String> builderIds = new HashSet<String>();
    for (PlanEnvelope e : PlanBuilder.buildCandidates(ir)) {
      builderIds.add(e.plan_id);
    }
    Set<String> found = new HashSet<String>();
    for (PlanEnvelope e : result.candidates) {
      found.add(e.plan_id);
    }
    assertTrue(found.contains("P_FULL"));
    assertTrue(found.contains("P_T") || found.contains("P_Z") || found.contains("P_TZ"),
        "expected at least one indexed family, got " + found);
    for (String id : found) {
      assertTrue(builderIds.contains(id), "unexpected plan family " + id);
    }
  }

  @Test
  void t43_illegalActionIdLoggedAndCompletes() {
    BoundIr ir = QueryIrFixtureTest.fixtureQuery();
    ScriptedLlmClient llm = new ScriptedLlmClient(
        "{\"action_id\":\"no_such_action\",\"reason\":\"bad\"}",
        "{\"action_id\":\"finish\",\"reason\":\"ok\"}",
        "{\"action_id\":\"finish\",\"reason\":\"ok\"}",
        "{\"action_id\":\"finish\",\"reason\":\"ok\"}",
        "{\"action_id\":\"finish\",\"reason\":\"ok\"}",
        "{\"action_id\":\"finish\",\"reason\":\"ok\"}");
    LlmProposalPolicy policy = new LlmProposalPolicy(llm);
    BeamSearch search = new BeamSearch(layout, fastCost);
    SearchBudget budget = SearchBudget.defaults();
    SearchResult result = search.search(ir, policy, budget);

    assertTrue(result.log.hasEvent("illegal_action"), result.log.toPrettyString());
    assertFalse(result.safePlans.isEmpty(), "search must still produce SafePlans");
  }

  @Test
  void t44_maxLlmCallsZeroDegeneratesToRulePolicy() {
    BoundIr ir = QueryIrFixtureTest.fixtureQuery();
    ScriptedLlmClient llm = new ScriptedLlmClient(
        "{\"action_id\":\"should_not_be_called\"}");
    LlmProposalPolicy policy = new LlmProposalPolicy(llm);
    SearchBudget budget = new SearchBudget(0, 8, 5000, 3, 2);
    SearchResult result = new BeamSearch(layout, fastCost).search(ir, policy, budget);

    assertEquals(0, llm.calls(), "LLM must not be called when max_llm_calls=0");
    assertTrue(result.safePlanIds().contains("P_FULL"));
    assertTrue(result.safePlans.size() >= 1);
    assertTrue(result.log.steps().size() > 0);
    SearchLog.Step first = result.log.steps().get(0);
    assertNotNull(first.legalActionIds);
    assertFalse(first.legalActionIds.isEmpty());
    assertNotNull(first.selectedActionId);
    assertNotNull(first.fastCostMs);
  }

  @Test
  void t45_costCardFieldsAndSampleSizeFromStats() {
    BoundIr ir = QueryIrFixtureTest.fixtureQuery();
    PlanEnvelope plan = PlanBuilder.buildForAccess(ir, true, true, false);
    CostCard card = fastCost.estimate(plan);
    assertNotNull(card.plan_id);
    assertEquals("FAST", card.stage);
    assertNotNull(card.model_version);
    assertNotNull(card.features);
    assertTrue(card.features.containsKey("scan_ranges"));
    assertTrue(card.features.containsKey("estimated_index_rows"));
    assertTrue(card.features.containsKey("estimated_candidate_chunks"));
    assertNotNull(card.main_cost_drivers);
    assertFalse(card.main_cost_drivers.isEmpty());
    assertNotNull(card.uncertainty);
    assertEquals("SAMPLE", card.uncertainty.method);
    assertEquals(stats.sample_chunks.size(), card.uncertainty.sample_size);
    assertNotNull(card.uncertainty.label);
  }

  @Test
  void t46_incompleteSuffixRejectedBySemanticCheck() {
    BoundIr ir = QueryIrFixtureTest.fixtureQuery();
    BeamSearch search = new BeamSearch(layout, fastCost);
    SearchResult result = search.search(ir, new RulePolicy(), SearchBudget.defaults());

    PlanEnvelope incomplete = incompleteTopKSuffix(ir);
    search.validateInjected(ir, incomplete, result);

    assertTrue(result.hasRejectionWith("SemanticCheck"),
        "expected SemanticCheck rejection, rejections=" + summarizeRejections(result));
  }

  @Test
  void p4_mockProducesAtLeastTwoDistinctSafePlanSignatures() {
    BoundIr ir = QueryIrFixtureTest.fixtureQuery();
    // Prefer FINISH so seeded singles complete; then intersects grow.
    ScriptedLlmClient llm = new ScriptedLlmClient(
        "{\"action_id\":\"finish\"}",
        "{\"action_id\":\"finish\"}",
        "{\"action_id\":\"finish\"}",
        "{\"action_id\":\"finish\"}",
        "{\"action_id\":\"finish\"}",
        "{\"action_id\":\"finish\"}");
    SearchResult result = new BeamSearch(layout, fastCost)
        .search(ir, new LlmProposalPolicy(llm), SearchBudget.defaults());

    Set<String> sigs = result.safeSignatures();
    assertTrue(sigs.size() >= 2,
        "expected ≥2 distinct SafePlan signatures, got " + result.safePlanIds()
            + " sigs=" + sigs.size() + " log=\n" + result.log.toPrettyString());
  }

  private static PlanEnvelope incompleteTopKSuffix(BoundIr ir) {
    // Access + filter + project, but missing BATCH_GET / SIMILARITY / TOP_K.
    PlanEnvelope env = new PlanEnvelope();
    env.plan_id = "P_INCOMPLETE_SUFFIX";
    env.query_id = ir.query_id;
    env.manifest_id = ir.snapshot != null ? ir.snapshot.manifest_id : FixtureBuilder.MANIFEST_ID;
    env.nodes.add(node("n01", Op.TIME_RANGE_SCAN, new ArrayList<String>(),
        params("predicate_ref", "/temporal")));
    env.nodes.add(node("n02", Op.DEDUPLICATE, Arrays.asList("n01"), null));
    env.nodes.add(node("n03", Op.FETCH_TRAJECTORY_CHUNK, Arrays.asList("n02"), null));
    Map<String, Object> filter = new HashMap<String, Object>();
    filter.put("predicate_refs", Arrays.asList("/temporal", "/spatial"));
    env.nodes.add(node("n04", Op.EXACT_ST_FILTER, Arrays.asList("n03"), filter));
    env.nodes.add(node("n05", Op.PROJECT_TRAJECTORY_IDS, Arrays.asList("n04"), null));
    env.root = "n05";
    return env;
  }

  private static PlanNode node(String id, Op op, List<String> inputs, Map<String, Object> params) {
    return new PlanNode(id, op, inputs, params);
  }

  private static Map<String, Object> params(String k, Object v) {
    Map<String, Object> m = new HashMap<String, Object>();
    m.put(k, v);
    return m;
  }

  private static Set<String> actionIds(List<LegalAction> legal) {
    Set<String> s = new HashSet<String>();
    for (LegalAction a : legal) {
      s.add(a.actionId);
    }
    return s;
  }

  private static String summarizeRejections(SearchResult result) {
    StringBuilder sb = new StringBuilder();
    for (SearchResult.Rejection r : result.rejections) {
      sb.append(r.planId).append(':');
      for (ValidationReport.Finding f : r.report.findings()) {
        if (!f.ok) {
          sb.append(f.check).append(' ');
        }
      }
      sb.append("; ");
    }
    return sb.toString();
  }
}
