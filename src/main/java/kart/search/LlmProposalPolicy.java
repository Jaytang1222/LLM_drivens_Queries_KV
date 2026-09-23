package kart.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kart.cost.CostCard;
import kart.llm.LlmClient;
import kart.llm.LlmException;
import kart.llm.LlmMessage;
import kart.llm.LlmOptions;
import kart.llm.LlmResponse;

import java.util.ArrayList;
import java.util.List;

/**
 * Asks the LLM for {@code action_id} only; illegal ids fall back to {@link RulePolicy} (T4.3).
 * When {@code max_llm_calls=0} / budget cannot call LLM, degenerates to RulePolicy.
 */
public final class LlmProposalPolicy implements ProposalPolicy {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final LlmClient llm;
  private final RulePolicy ruleFallback;
  private final LlmOptions options;

  /** Last propose flags for the search log. */
  public boolean lastIllegal;
  public String lastRequestedId;
  public String lastEvent;

  public LlmProposalPolicy(LlmClient llm) {
    this(llm, new RulePolicy(), LlmOptions.defaults());
  }

  public LlmProposalPolicy(LlmClient llm, RulePolicy ruleFallback, LlmOptions options) {
    this.llm = llm;
    this.ruleFallback = ruleFallback != null ? ruleFallback : new RulePolicy();
    this.options = options != null ? options : LlmOptions.defaults();
  }

  public RulePolicy rulePolicy() {
    return ruleFallback;
  }

  @Override
  public ActionSelection propose(SearchState state, List<LegalAction> legal,
                                 CostCard fastCost, SearchBudget budget) {
    lastIllegal = false;
    lastRequestedId = null;
    lastEvent = null;

    if (budget == null || !budget.canCallLlm() || llm == null) {
      lastEvent = "rule_fallback";
      ActionSelection sel = ruleFallback.propose(state, legal, fastCost, budget);
      return new ActionSelection(sel.action, "max_llm_calls_or_no_client:" + sel.reason,
          false, false, sel.action != null ? sel.action.actionId : null);
    }

    try {
      List<LlmMessage> messages = buildMessages(state, legal, fastCost);
      budget.recordLlmCall();
      LlmResponse resp = llm.chat(messages, null, options);
      String actionId = parseActionId(resp != null ? resp.content : null);
      lastRequestedId = actionId;
      LegalAction chosen = LegalActionGenerator.findById(legal, actionId);
      if (chosen == null) {
        lastIllegal = true;
        lastEvent = "illegal_action";
        ActionSelection fb = ruleFallback.propose(state, legal, fastCost, budget);
        return new ActionSelection(fb.action,
            "illegal_action:" + actionId + "; fallback=" + fb.reason,
            true, true, actionId);
      }
      lastEvent = "ok";
      return new ActionSelection(chosen, "llm", true, false, actionId);
    } catch (LlmException e) {
      lastEvent = "rule_fallback";
      ActionSelection fb = ruleFallback.propose(state, legal, fastCost, budget);
      return new ActionSelection(fb.action, "llm_error:" + e.getMessage() + "; " + fb.reason,
          false, false, null);
    }
  }

  static List<LlmMessage> buildMessages(SearchState state, List<LegalAction> legal,
                                        CostCard fastCost) {
    List<LlmMessage> msgs = new ArrayList<LlmMessage>();
    msgs.add(LlmMessage.system(
        "You select the next plan-search action. Reply with JSON only: "
            + "{\"action_id\":\"...\",\"reason\":\"...\"}. "
            + "action_id must be one of the provided legal actions."));
    StringBuilder user = new StringBuilder();
    user.append("state_signature=").append(state.signature()).append('\n');
    user.append("used_indexes=").append(state.usedIndexes()).append('\n');
    user.append("obligations=").append(state.obligations()).append('\n');
    if (fastCost != null) {
      user.append("fast_cost_ms=").append(fastCost.estimated_ms)
          .append(" sample_size=").append(fastCost.uncertainty.sample_size).append('\n');
    }
    user.append("legal_actions:\n");
    for (LegalAction a : legal) {
      user.append("  - ").append(a.actionId).append(" : ").append(a.description).append('\n');
    }
    msgs.add(LlmMessage.user(user.toString()));
    return msgs;
  }

  static String parseActionId(String content) {
    if (content == null) {
      return null;
    }
    String trimmed = content.trim();
    try {
      JsonNode n = MAPPER.readTree(trimmed);
      if (n.has("action_id")) {
        return n.get("action_id").asText();
      }
    } catch (Exception ignored) {
      // try loose extract
    }
    int i = trimmed.indexOf("action_id");
    if (i >= 0) {
      int q1 = trimmed.indexOf('"', i + 9);
      int q2 = q1 >= 0 ? trimmed.indexOf('"', q1 + 1) : -1;
      if (q1 >= 0 && q2 > q1) {
        return trimmed.substring(q1 + 1, q2);
      }
    }
    return trimmed;
  }
}
