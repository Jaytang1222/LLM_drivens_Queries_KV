package kart.search;

import kart.cost.CostCard;
import kart.cost.FastCost;
import kart.ir.BoundIr;
import kart.validation.IncrementalValidator;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Cost-ordered / Best-first proposal policy (IMPLEMENTATION_PLAN §19.1 baseline #4):
 * among legal actions for each frontier state, choose the action whose resulting
 * completed plan has the lowest Fast Cost (ties: FINISH, then action_id).
 */
public final class BestFirstPolicy implements ProposalPolicy {

  private final FastCost fastCost;
  private final IncrementalValidator incremental = new IncrementalValidator();

  public BestFirstPolicy(FastCost fastCost) {
    this.fastCost = fastCost != null ? fastCost : new FastCost(null);
  }

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
      ActionSelection sel = choose(state, legal, card);
      out.add(new ActionProposal(state.stateId(),
          sel.requestedActionId != null ? sel.requestedActionId
              : (sel.action != null ? sel.action.actionId : null),
          sel.reason != null ? sel.reason : "BEST_FIRST",
          sel.action, false, false));
    }
    return out;
  }

  private ActionSelection choose(SearchState state, List<LegalAction> legal, CostCard card) {
    if (legal == null || legal.isEmpty()) {
      return ActionSelection.of(LegalActionGenerator.finish(), "empty_legal");
    }
    BoundIr ir = state.ir();
    int evalCap = evalCap();
    int estimated = 0;
    LegalAction best = null;
    double bestMs = Double.POSITIVE_INFINITY;
    String bestId = null;
    for (LegalAction a : legal) {
      if (a == null) {
        continue;
      }
      double ms;
      if (a.isFinish()) {
        ms = card != null ? card.estimated_ms : Double.POSITIVE_INFINITY;
      } else {
        if (evalCap > 0 && estimated >= evalCap) {
          continue;
        }
        SearchState next;
        try {
          next = state.apply(a);
        } catch (RuntimeException e) {
          continue;
        }
        IncrementalValidator.Result inc = incremental.check(next);
        if (!inc.ok) {
          continue;
        }
        CostCard nc = fastCost.estimate(next.completePlan(), ir);
        estimated++;
        ms = nc != null ? nc.estimated_ms : Double.POSITIVE_INFINITY;
      }
      String aid = a.actionId != null ? a.actionId : "";
      boolean better = ms < bestMs - 1e-12
          || (Math.abs(ms - bestMs) <= 1e-12 && a.isFinish() && (best == null || !best.isFinish()))
          || (Math.abs(ms - bestMs) <= 1e-12 && bestId != null && aid.compareTo(bestId) < 0);
      if (best == null || better) {
        best = a;
        bestMs = ms;
        bestId = aid;
      }
    }
    if (best == null) {
      return ActionSelection.of(legal.get(0), "best_first_fallback");
    }
    return ActionSelection.of(best, "best_first_min_fast_cost");
  }

  /**
   * Cap non-FINISH FastCost evaluations per frontier state. ADV expands legal
   * actions (~2×); without a cap BestFirst can burn seconds on unique plans.
   * Env {@code KART_BEST_FIRST_EVAL_CAP}: unset → 3 when ADV else unlimited (0);
   * {@code 0}/off/false → unlimited; positive int → hard cap.
   */
  static int evalCap() {
    String v = System.getenv("KART_BEST_FIRST_EVAL_CAP");
    if (v != null && !v.trim().isEmpty()) {
      v = v.trim().toLowerCase();
      if (v.equals("0") || v.equals("off") || v.equals("false") || v.equals("no")) {
        return 0;
      }
      try {
        return Math.max(0, Integer.parseInt(v));
      } catch (NumberFormatException e) {
        return LegalActionGenerator.advancedIndexesEnabled() ? 3 : 0;
      }
    }
    return LegalActionGenerator.advancedIndexesEnabled() ? 3 : 0;
  }
}
