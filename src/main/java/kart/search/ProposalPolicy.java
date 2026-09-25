package kart.search;

import kart.cost.CostCard;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Chooses actions over a frontier (IMPLEMENTATION_PLAN §11.4 {@code proposals[]}).
 */
public interface ProposalPolicy {

  /**
   * Propose actions for the whole frontier in one call.
   *
   * @param frontier ordered frontier states
   * @param legalByStateId map stateId → legal actions
   * @param cardsByStateId map stateId → Fast CostCard
   */
  List<ActionProposal> propose(List<SearchState> frontier,
                               Map<String, List<LegalAction>> legalByStateId,
                               Map<String, CostCard> cardsByStateId,
                               SearchBudget budget);

  /**
   * Legacy single-state adapter (tests / callers). Default builds a one-state frontier.
   */
  default ActionSelection propose(SearchState state, List<LegalAction> legal,
                                  CostCard fastCost, SearchBudget budget) {
    List<SearchState> frontier = new ArrayList<SearchState>();
    frontier.add(state);
    java.util.Map<String, List<LegalAction>> legalMap =
        new java.util.HashMap<String, List<LegalAction>>();
    legalMap.put(state.stateId(), legal);
    java.util.Map<String, CostCard> cards = new java.util.HashMap<String, CostCard>();
    cards.put(state.stateId(), fastCost);
    List<ActionProposal> props = propose(frontier, legalMap, cards, budget);
    if (props == null || props.isEmpty()) {
      return ActionSelection.of(null, "empty_proposals");
    }
    ActionProposal p = props.get(0);
    return new ActionSelection(p.action, p.reasonCode, p.fromLlm, p.illegal, p.actionId);
  }
}
