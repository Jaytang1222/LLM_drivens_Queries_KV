package kart.search;

import kart.cost.CostCard;

import java.util.ArrayList;
import java.util.List;

/**
 * Search step log: legal set, selection, legality, fast cost (T4.4).
 */
public final class SearchLog {

  public static final class Step {
    public int step;
    public String stateSignature;
    public List<String> legalActionIds = new ArrayList<String>();
    public String selectedActionId;
    public boolean legal;
    public String event; // ok | illegal_action | rule_fallback | skip
    public String reason;
    public Double fastCostMs;
    public String completedPlanId;
  }

  private final List<Step> steps = new ArrayList<Step>();

  public List<Step> steps() {
    return steps;
  }

  public Step begin(int stepNo, SearchState state, List<LegalAction> legal, CostCard card) {
    Step s = new Step();
    s.step = stepNo;
    s.stateSignature = state.signature();
    for (LegalAction a : legal) {
      s.legalActionIds.add(a.actionId);
    }
    if (card != null) {
      s.fastCostMs = Double.valueOf(card.estimated_ms);
    }
    steps.add(s);
    return s;
  }

  public boolean hasEvent(String event) {
    for (Step s : steps) {
      if (event.equals(s.event)) {
        return true;
      }
    }
    return false;
  }

  public String toPrettyString() {
    StringBuilder sb = new StringBuilder();
    sb.append("SearchLog steps=").append(steps.size()).append('\n');
    for (Step s : steps) {
      sb.append("  #").append(s.step)
          .append(" state=").append(s.stateSignature)
          .append(" selected=").append(s.selectedActionId)
          .append(" legal=").append(s.legal)
          .append(" event=").append(s.event);
      if (s.fastCostMs != null) {
        sb.append(" fast_ms=").append(s.fastCostMs);
      }
      if (s.completedPlanId != null) {
        sb.append(" plan=").append(s.completedPlanId);
      }
      if (s.reason != null) {
        sb.append(" reason=").append(s.reason);
      }
      sb.append('\n');
    }
    return sb.toString();
  }
}
