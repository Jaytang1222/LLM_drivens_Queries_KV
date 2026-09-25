package kart.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kart.cost.CostCard;
import kart.llm.LlmClient;
import kart.llm.LlmException;
import kart.llm.LlmMessage;
import kart.llm.LlmOptions;
import kart.llm.LlmResponse;
import kart.llm.LlmUsageAccumulator;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Frontier {@code proposals[]} LLM policy (IMPLEMENTATION_PLAN §11.4).
 * Illegal ids fall back to {@link RulePolicy} per state.
 */
public final class LlmProposalPolicy implements ProposalPolicy {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final LlmClient llm;
  private final RulePolicy ruleFallback;
  private final LlmOptions options;
  private LlmUsageAccumulator usage;

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

  public void setUsageAccumulator(LlmUsageAccumulator usage) {
    this.usage = usage;
  }

  public RulePolicy rulePolicy() {
    return ruleFallback;
  }

  @Override
  public List<ActionProposal> propose(List<SearchState> frontier,
                                      Map<String, List<LegalAction>> legalByStateId,
                                      Map<String, CostCard> cardsByStateId,
                                      SearchBudget budget) {
    lastIllegal = false;
    lastRequestedId = null;
    lastEvent = null;

    if (budget == null || !budget.canCallLlm() || llm == null) {
      lastEvent = "rule_fallback";
      return ruleFallback.propose(frontier, legalByStateId, cardsByStateId, budget);
    }

    try {
      List<LlmMessage> messages = buildMessages(frontier, legalByStateId, cardsByStateId);
      budget.recordLlmCall();
      LlmResponse resp = llm.chat(messages, null, options);
      if (usage != null) {
        usage.record(resp);
      }
      List<ParsedProp> parsed = parseProposals(resp != null ? resp.content : null);
      Map<String, SearchState> byId = new HashMap<String, SearchState>();
      for (SearchState s : frontier) {
        byId.put(s.stateId(), s);
      }

      List<ActionProposal> out = new ArrayList<ActionProposal>();
      Map<String, Boolean> covered = new HashMap<String, Boolean>();

      for (ParsedProp pp : parsed) {
        SearchState st = byId.get(pp.stateId);
        if (st == null) {
          lastIllegal = true;
          lastEvent = "illegal_action";
          continue;
        }
        List<LegalAction> legal = legalByStateId.get(pp.stateId);
        LegalAction chosen = LegalActionGenerator.findById(legal, pp.actionId);
        lastRequestedId = pp.actionId;
        if (chosen == null) {
          lastIllegal = true;
          lastEvent = "illegal_action";
          List<ActionProposal> fb = ruleFallback.propose(
              singleton(st), singletonLegal(pp.stateId, legal),
              singletonCard(pp.stateId, cardsByStateId.get(pp.stateId)), budget);
          if (!fb.isEmpty()) {
            ActionProposal f = fb.get(0);
            out.add(new ActionProposal(pp.stateId, pp.actionId,
                "illegal_action:" + pp.actionId + "; fallback=" + f.reasonCode,
                f.action, true, true));
            covered.put(pp.stateId, Boolean.TRUE);
          }
        } else {
          lastEvent = "ok";
          out.add(new ActionProposal(pp.stateId, pp.actionId,
              pp.reasonCode != null ? pp.reasonCode : "llm",
              chosen, false, true));
          covered.put(pp.stateId, Boolean.TRUE);
        }
      }

      // Ensure every frontier state gets a proposal (rule fill).
      for (SearchState st : frontier) {
        if (covered.containsKey(st.stateId())) {
          continue;
        }
        List<ActionProposal> fb = ruleFallback.propose(
            singleton(st),
            singletonLegal(st.stateId(), legalByStateId.get(st.stateId())),
            singletonCard(st.stateId(), cardsByStateId.get(st.stateId())),
            budget);
        out.addAll(fb);
      }
      return out;
    } catch (LlmException e) {
      lastEvent = "rule_fallback";
      return ruleFallback.propose(frontier, legalByStateId, cardsByStateId, budget);
    }
  }

  private static List<SearchState> singleton(SearchState s) {
    List<SearchState> l = new ArrayList<SearchState>();
    l.add(s);
    return l;
  }

  private static Map<String, List<LegalAction>> singletonLegal(String id, List<LegalAction> legal) {
    Map<String, List<LegalAction>> m = new HashMap<String, List<LegalAction>>();
    m.put(id, legal);
    return m;
  }

  private static Map<String, CostCard> singletonCard(String id, CostCard card) {
    Map<String, CostCard> m = new HashMap<String, CostCard>();
    m.put(id, card);
    return m;
  }

  static List<LlmMessage> buildMessages(List<SearchState> frontier,
                                        Map<String, List<LegalAction>> legalByStateId,
                                        Map<String, CostCard> cardsByStateId) {
    List<LlmMessage> msgs = new ArrayList<LlmMessage>();
    msgs.add(LlmMessage.system(
        "You select plan-search actions for a frontier. Reply with JSON only: "
            + "{\"response_version\":\"1.0\",\"proposals\":["
            + "{\"state_id\":\"...\",\"action_id\":\"...\",\"reason_code\":\"...\"}]}. "
            + "action_id must be one of each state's legal actions. "
            + "reason_code is explanatory only."));
    StringBuilder user = new StringBuilder();
    user.append("frontier:\n");
    for (SearchState state : frontier) {
      user.append("--- state_id=").append(state.stateId()).append('\n');
      user.append("signature=").append(state.signature()).append('\n');
      user.append("used_indexes=").append(state.usedIndexes()).append('\n');
      user.append("obligations=").append(state.obligations()).append('\n');
      CostCard fastCost = cardsByStateId != null ? cardsByStateId.get(state.stateId()) : null;
      if (fastCost != null) {
        user.append("fast_cost_ms=").append(fastCost.estimated_ms)
            .append(" sample_size=").append(fastCost.uncertainty.sample_size).append('\n');
      }
      user.append("legal_actions:\n");
      List<LegalAction> legal = legalByStateId != null ? legalByStateId.get(state.stateId()) : null;
      if (legal != null) {
        for (LegalAction a : legal) {
          user.append("  - ").append(a.actionId).append(" : ").append(a.description).append('\n');
        }
      }
    }
    msgs.add(LlmMessage.user(user.toString()));
    return msgs;
  }

  static List<ParsedProp> parseProposals(String content) {
    List<ParsedProp> out = new ArrayList<ParsedProp>();
    if (content == null) {
      return out;
    }
    String trimmed = content.trim();
    try {
      JsonNode n = MAPPER.readTree(trimmed);
      if (n.has("proposals") && n.get("proposals").isArray()) {
        for (JsonNode p : n.get("proposals")) {
          ParsedProp pp = new ParsedProp();
          pp.stateId = text(p, "state_id");
          pp.actionId = text(p, "action_id");
          pp.reasonCode = text(p, "reason_code");
          if (pp.stateId != null && pp.actionId != null) {
            out.add(pp);
          }
        }
        return out;
      }
      // Legacy single-action fallback
      if (n.has("action_id")) {
        ParsedProp pp = new ParsedProp();
        pp.stateId = text(n, "state_id");
        pp.actionId = text(n, "action_id");
        pp.reasonCode = text(n, "reason");
        if (pp.actionId != null) {
          out.add(pp);
        }
      }
    } catch (Exception ignored) {
      // ignore
    }
    return out;
  }

  private static String text(JsonNode n, String field) {
    if (n == null || !n.has(field) || n.get(field).isNull()) {
      return null;
    }
    return n.get(field).asText();
  }

  static final class ParsedProp {
    String stateId;
    String actionId;
    String reasonCode;
  }
}
