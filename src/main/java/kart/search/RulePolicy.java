package kart.search;

import kart.cost.CostCard;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Fixed-order frontier proposals (design.md §8.2):
 * {@code START → CHOOSE_MERGE → INTERSECT → PARTITION_UNION → REPLACE → FINISH}.
 * BeamSearch materializes a SafePlan when FINISH is chosen, and also emits the
 * access-complete family once when PARTITION/REPLACE diversifies first.
 */
public final class RulePolicy implements ProposalPolicy {

  private final Set<String> finishedSignatures = new HashSet<String>();

  @Override
  public List<ActionProposal> propose(List<SearchState> frontier,
                                      Map<String, List<LegalAction>> legalByStateId,
                                      Map<String, CostCard> cardsByStateId,
                                      SearchBudget budget) {
    List<ActionProposal> out = new ArrayList<ActionProposal>();
    if (frontier == null) {
      return out;
    }
    for (SearchState state : frontier) {
      List<LegalAction> legal = legalByStateId != null ? legalByStateId.get(state.stateId()) : null;
      CostCard card = cardsByStateId != null ? cardsByStateId.get(state.stateId()) : null;
      ActionSelection sel = proposeOne(state, legal, card, budget);
      out.add(new ActionProposal(state.stateId(),
          sel.requestedActionId != null ? sel.requestedActionId
              : (sel.action != null ? sel.action.actionId : null),
          sel.reason != null ? sel.reason : "RULE",
          sel.action, false, false));
    }
    return out;
  }

  private ActionSelection proposeOne(SearchState state, List<LegalAction> legal,
                                     CostCard fastCost, SearchBudget budget) {
    if (legal == null || legal.isEmpty()) {
      return ActionSelection.of(LegalActionGenerator.finish(), "empty_legal");
    }

    boolean empty = state.usedIndexes().isEmpty();
    String sig = state.signature();
    boolean alreadyFinished = finishedSignatures.contains(sig);

    if (empty) {
      LegalAction start = firstOfKind(legal, ActionKind.START);
      if (start != null) {
        return ActionSelection.of(start, "rule_start_single");
      }
      LegalAction fin = firstOfKind(legal, ActionKind.FINISH);
      return ActionSelection.of(fin != null ? fin : legal.get(0), "rule_finish_empty");
    }

    if (state.usedIndexes().size() >= 2 && state.mergeImpl() == null) {
      LegalAction merge = firstOfKind(legal, ActionKind.CHOOSE_MERGE);
      if (merge != null) {
        return ActionSelection.of(merge, "rule_choose_merge");
      }
    }

    LegalAction inter = firstOfKind(legal, ActionKind.INTERSECT);
    if (inter != null) {
      return ActionSelection.of(inter, "rule_intersect");
    }

    // §8.2 literal: PARTITION → REPLACE → FINISH (after access construction).
    LegalAction part = firstOfKind(legal, ActionKind.PARTITION_UNION);
    if (part != null && !alreadyFinished) {
      return ActionSelection.of(part, "rule_partition");
    }

    // Single-index ablation: INTERSECT is not legal, but REPLACE of the other
    // index would ping-pong T↔Z and never FINISH. Emit P_T/P_Z/P_H first.
    if (state.usedIndexes().size() == 1 && !alreadyFinished) {
      LegalAction finSingle = firstOfKind(legal, ActionKind.FINISH);
      if (finSingle != null) {
        finishedSignatures.add(sig);
        return ActionSelection.of(finSingle, "rule_finish_no_intersect");
      }
    }

    LegalAction replace = firstOfKind(legal, ActionKind.REPLACE);
    if (replace != null && !alreadyFinished) {
      return ActionSelection.of(replace, "rule_replace");
    }

    LegalAction fin = firstOfKind(legal, ActionKind.FINISH);
    if (fin != null) {
      finishedSignatures.add(sig);
      return ActionSelection.of(fin, "rule_finish");
    }
    return ActionSelection.of(legal.get(0), "rule_fallback");
  }

  /** True when every IR-available index is in the access set and merge is chosen if needed. */
  public static boolean accessComplete(SearchState state) {
    if (state == null || state.usedIndexes().isEmpty()) {
      return false;
    }
    List<String> avail = LegalActionGenerator.availableIndexes(state.ir());
    if (!state.usedIndexes().containsAll(avail)) {
      return false;
    }
    return state.usedIndexes().size() < 2 || state.mergeImpl() != null;
  }

  /** Record that a family signature has already emitted a FINISH / family candidate. */
  public void markFinished(String signature) {
    if (signature != null) {
      finishedSignatures.add(signature);
    }
  }

  private static LegalAction firstOfKind(List<LegalAction> legal, ActionKind kind) {
    for (LegalAction a : legal) {
      if (a.kind == kind) {
        return a;
      }
    }
    return null;
  }
}
