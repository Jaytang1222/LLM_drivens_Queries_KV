package kart.search;

import kart.cost.CostCard;

import java.util.List;

/**
 * Chooses one legal action for a search state (design.md §8.2).
 */
public interface ProposalPolicy {

  ActionSelection propose(SearchState state, List<LegalAction> legal,
                          CostCard fastCost, SearchBudget budget);
}
