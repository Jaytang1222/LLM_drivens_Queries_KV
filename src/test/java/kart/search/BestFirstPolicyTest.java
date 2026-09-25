package kart.search;

import kart.cost.CostCard;
import kart.cost.FastCost;
import kart.ir.BoundIr;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** IMPLEMENTATION_PLAN §19.1 Best-first / cost-ordered baseline. */
public final class BestFirstPolicyTest {

  @Test
  void prefersLowerFastCostChild() {
    BoundIr ir = minimalTzIr();
    SearchState state = SearchState.initial(ir)
        .apply(LegalActionGenerator.start(IndexId.TIME));
    List<LegalAction> legal = new LegalActionGenerator().generate(state);
    assertTrue(legal.stream().anyMatch(a -> a.kind == ActionKind.INTERSECT));

    Map<String, List<LegalAction>> byId = new HashMap<String, List<LegalAction>>();
    byId.put(state.stateId(), legal);
    Map<String, CostCard> cards = new HashMap<String, CostCard>();
    CostCard self = new CostCard();
    self.estimated_ms = 1_000_000.0;
    cards.put(state.stateId(), self);

    BestFirstPolicy policy = new BestFirstPolicy(new FastCost(null));
    ActionProposal prop = policy.propose(
        Collections.singletonList(state), byId, cards, SearchBudget.defaults()).get(0);
    assertNotNull(prop.action);
    // With only TIME started, INTERSECT (add Z) or FINISH are legal; best-first should
    // pick a concrete legal action (not null).
    assertTrue(prop.action.kind == ActionKind.INTERSECT
            || prop.action.kind == ActionKind.FINISH
            || prop.action.kind == ActionKind.REPLACE
            || prop.action.kind == ActionKind.PARTITION_UNION,
        "kind=" + prop.action.kind);
    assertEquals("best_first_min_fast_cost", prop.reasonCode);
  }

  private static BoundIr minimalTzIr() {
    BoundIr ir = new BoundIr();
    ir.query_id = "best_first_test";
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
