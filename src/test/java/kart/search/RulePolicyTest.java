package kart.search;

import kart.cost.CostCard;
import kart.ir.BoundIr;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** design.md RulePolicy order: START → CHOOSE_MERGE → INTERSECT → PARTITION → REPLACE → FINISH. */
public final class RulePolicyTest {

  @Test
  void prefersChooseMergeBeforeFinishWhenMultiIndex() {
    BoundIr ir = minimalTzIr();
    SearchState state = SearchState.initial(ir)
        .apply(LegalActionGenerator.start(IndexId.TIME))
        .apply(LegalActionGenerator.intersect(IndexId.ZORDER));
    assertEquals(2, state.usedIndexes().size());
    assertEquals(null, state.mergeImpl());

    List<LegalAction> legal = new LegalActionGenerator().generate(state);
    Map<String, List<LegalAction>> byId = new HashMap<String, List<LegalAction>>();
    byId.put(state.stateId(), legal);
    Map<String, CostCard> cards = new HashMap<String, CostCard>();
    cards.put(state.stateId(), new CostCard());

    RulePolicy policy = new RulePolicy();
    List<ActionProposal> props = policy.propose(
        Collections.singletonList(state), byId, cards, SearchBudget.defaults());
    assertEquals(1, props.size());
    assertNotNull(props.get(0).action);
    assertEquals(ActionKind.CHOOSE_MERGE, props.get(0).action.kind);
  }

  @Test
  void prefersIntersectBeforeFinishWhenMergeChosen() {
    BoundIr ir = minimalTzIr();
    // Only TIME used; ZORDER still available → INTERSECT before FINISH.
    SearchState state = SearchState.initial(ir)
        .apply(LegalActionGenerator.start(IndexId.TIME));
    List<LegalAction> legal = new LegalActionGenerator().generate(state);
    assertTrue(legal.stream().anyMatch(a -> a.kind == ActionKind.INTERSECT));

    Map<String, List<LegalAction>> byId = new HashMap<String, List<LegalAction>>();
    byId.put(state.stateId(), legal);
    RulePolicy policy = new RulePolicy();
    List<ActionProposal> props = policy.propose(
        Collections.singletonList(state), byId, Collections.<String, CostCard>emptyMap(),
        SearchBudget.defaults());
    assertEquals(ActionKind.INTERSECT, props.get(0).action.kind);
  }

  @Test
  void partitionBeforeFinishWhenAccessComplete() {
    BoundIr ir = minimalTzIr();
    SearchState state = SearchState.initial(ir)
        .apply(LegalActionGenerator.start(IndexId.TIME))
        .apply(LegalActionGenerator.intersect(IndexId.ZORDER))
        .apply(LegalActionGenerator.chooseMerge(SearchState.MERGE_HASH_SET));
    assertTrue(RulePolicy.accessComplete(state));
    List<LegalAction> legal = new LegalActionGenerator().generate(state);
    Map<String, List<LegalAction>> byId = new HashMap<String, List<LegalAction>>();
    byId.put(state.stateId(), legal);
    ActionProposal prop = new RulePolicy().propose(
        Collections.singletonList(state), byId, Collections.<String, CostCard>emptyMap(),
        SearchBudget.defaults()).get(0);
    if (legal.stream().anyMatch(a -> a.kind == ActionKind.PARTITION_UNION)) {
      assertEquals(ActionKind.PARTITION_UNION, prop.action.kind);
    } else if (legal.stream().anyMatch(a -> a.kind == ActionKind.REPLACE)) {
      assertEquals(ActionKind.REPLACE, prop.action.kind);
    } else {
      assertEquals(ActionKind.FINISH, prop.action.kind);
    }
  }

  @Test
  void afterFamilyEmittedPrefersFinishWhenNoPartitionLeft() {
    BoundIr ir = minimalTzIr();
    SearchState state = SearchState.initial(ir)
        .apply(LegalActionGenerator.start(IndexId.TIME))
        .apply(LegalActionGenerator.intersect(IndexId.ZORDER))
        .apply(LegalActionGenerator.chooseMerge(SearchState.MERGE_HASH_SET));
    List<LegalAction> legal = new LegalActionGenerator().generate(state);
    Map<String, List<LegalAction>> byId = new HashMap<String, List<LegalAction>>();
    byId.put(state.stateId(), legal);
    RulePolicy policy = new RulePolicy();
    policy.markFinished(state.signature());
    ActionProposal prop = policy.propose(
        Collections.singletonList(state), byId, Collections.<String, CostCard>emptyMap(),
        SearchBudget.defaults()).get(0);
    // After family emitted, skip PARTITION/REPLACE re-entry → FINISH.
    assertEquals(ActionKind.FINISH, prop.action.kind);
  }

  private static BoundIr minimalTzIr() {
    BoundIr ir = new BoundIr();
    ir.query_id = "rule_policy_test";
    ir.temporal = new BoundIr.Temporal();
    ir.temporal.start_ms = 0L;
    ir.temporal.end_ms = 3600_000L;
    ir.spatial = new BoundIr.Spatial();
    ir.spatial.min_x = 0;
    ir.spatial.min_y = 0;
    ir.spatial.max_x = 100;
    ir.spatial.max_y = 100;
    ir.result = new BoundIr.Result();
    ir.result.mode = "TRAJECTORY_IDS";
    return ir;
  }
}
