package kart.search;

import kart.cost.CostCard;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Fixed-order proposal: singles → pairs → triples → FINISH (T4.2).
 * Prefers FINISH once for each access signature (to emit that family),
 * then INTERSECT to grow toward multi-index plans; START on empty;
 * REPLACE last.
 */
public final class RulePolicy implements ProposalPolicy {

  private final Set<String> finishedSignatures = new HashSet<String>();

  @Override
  public ActionSelection propose(SearchState state, List<LegalAction> legal,
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

    // Singles / pairs / triples: FINISH each signature once before growing.
    if (!alreadyFinished) {
      LegalAction fin = firstOfKind(legal, ActionKind.FINISH);
      if (fin != null) {
        finishedSignatures.add(sig);
        return ActionSelection.of(fin, "rule_finish_family");
      }
    }

    LegalAction inter = firstOfKind(legal, ActionKind.INTERSECT);
    if (inter != null) {
      return ActionSelection.of(inter, "rule_intersect");
    }

    LegalAction replace = firstOfKind(legal, ActionKind.REPLACE);
    if (replace != null) {
      return ActionSelection.of(replace, "rule_replace");
    }

    LegalAction fin = firstOfKind(legal, ActionKind.FINISH);
    return ActionSelection.of(fin != null ? fin : legal.get(0), "rule_fallback");
  }

  /** Mark a signature as finished externally (e.g. after applying FINISH). */
  public void markFinished(String signature) {
    finishedSignatures.add(signature);
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
