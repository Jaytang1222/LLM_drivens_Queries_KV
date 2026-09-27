package kart.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kart.compile.LayoutContext;
import kart.cost.FastCost;
import kart.ir.BoundIr;
import kart.llm.LlmClient;
import kart.llm.LlmException;
import kart.llm.LlmMessage;
import kart.llm.LlmOptions;
import kart.llm.LlmResponse;
import kart.llm.LlmUsageAccumulator;
import kart.plan.PlanBuilder;
import kart.plan.PlanEnvelope;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * §19.1 baseline #5 / §11.4: LLM emits one complete {@link PlanEnvelope}
 * (or a constructor-family {@code plan_id} shortcut); same {@link BeamSearch}
 * compile+validate path. Illegal / failed validation falls back to {@link RulePolicy} beam.
 */
public final class LlmDirectPlanPlanner {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final LayoutContext layout;
  private final FastCost fastCost;
  private final LlmClient llm;
  private final LlmOptions options;
  private final SearchOptions searchOptions;
  private LlmUsageAccumulator usage;

  public LlmDirectPlanPlanner(LayoutContext layout, FastCost fastCost, LlmClient llm) {
    this(layout, fastCost, llm, LlmOptions.defaults(), SearchOptions.defaults());
  }

  public LlmDirectPlanPlanner(LayoutContext layout, FastCost fastCost, LlmClient llm,
                              LlmOptions options) {
    this(layout, fastCost, llm, options, SearchOptions.defaults());
  }

  public LlmDirectPlanPlanner(LayoutContext layout, FastCost fastCost, LlmClient llm,
                              SearchOptions searchOptions) {
    this(layout, fastCost, llm, LlmOptions.defaults(), searchOptions);
  }

  public LlmDirectPlanPlanner(LayoutContext layout, FastCost fastCost, LlmClient llm,
                              LlmOptions options, SearchOptions searchOptions) {
    this.layout = layout;
    this.fastCost = fastCost != null ? fastCost : new FastCost(layout, null);
    this.llm = llm;
    this.options = options != null ? options : LlmOptions.defaults();
    this.searchOptions = searchOptions != null ? searchOptions : SearchOptions.defaults();
  }

  public void setUsageAccumulator(LlmUsageAccumulator usage) {
    this.usage = usage;
  }

  /**
   * @return search result; {@code stopReason} on budget may be set by caller
   */
  public SearchResult plan(BoundIr ir, SearchBudget budget) {
    BeamSearch beam = new BeamSearch(layout, fastCost, searchOptions);
    List<PlanEnvelope> family =
        PlanBuilder.buildCandidatesWithMergeVariants(ir, searchOptions.allowIntersect);
    Map<String, PlanEnvelope> byId = indexByPlanId(family);

    PlanEnvelope chosen = null;
    String reason = null;
    if (llm != null && budget != null && budget.canCallLlm()) {
      try {
        budget.recordLlmCall();
        List<LlmMessage> messages = buildMessages(ir, family);
        LlmResponse resp = llm.chat(messages, null, options);
        if (usage != null) {
          usage.record(resp);
        }
        DirectChoice choice = parseChoice(resp != null ? resp.content : null, byId, ir);
        if (choice.envelope != null) {
          chosen = choice.envelope;
          reason = choice.reason != null ? choice.reason : "llm_direct";
        } else {
          reason = choice.reason != null ? choice.reason : "llm_direct_illegal";
        }
      } catch (LlmException e) {
        reason = "llm_direct_error";
        if (usage != null) {
          usage.recordHttpFailure(e.httpStatus, 0L);
        }
      }
    } else {
      reason = "llm_direct_no_budget";
    }

    if (chosen != null) {
      // Fill required envelope fields from IR when LLM omitted them.
      if (chosen.query_id == null && ir != null) {
        chosen.query_id = ir.query_id;
      }
      if (chosen.manifest_id == null && ir != null && ir.snapshot != null) {
        chosen.manifest_id = ir.snapshot.manifest_id;
      }
      SearchResult direct = new SearchResult();
      direct.budget = budget;
      direct.candidates.add(chosen);
      SearchLog.Step step = new SearchLog.Step();
      step.step = 0;
      step.event = reason;
      step.reason = reason;
      step.completedPlanId = chosen.plan_id;
      step.selectedActionId = chosen.plan_id;
      step.legal = true;
      direct.log.steps().add(step);
      beam.validateInjected(ir, chosen, direct);
      if (!direct.safePlans.isEmpty()) {
        return direct;
      }
      reason = "llm_direct_rejected";
    }

    SearchResult fallback = beam.search(ir, new RulePolicy(), budget);
    SearchLog.Step note = new SearchLog.Step();
    note.step = -1;
    note.event = "llm_direct_fallback";
    note.reason = reason != null ? reason : "fallback";
    note.legal = false;
    fallback.log.steps().add(0, note);
    return fallback;
  }

  static List<LlmMessage> buildMessages(BoundIr ir, List<PlanEnvelope> family) {
    List<LlmMessage> msgs = new ArrayList<LlmMessage>();
    msgs.add(LlmMessage.system(
        "You produce one complete trajectory query PlanEnvelope under the same validator "
            + "as the system. Reply with JSON only in one of two forms:\n"
            + "1) {\"response_version\":\"1.0\",\"plan_id\":\"P_TZ\"} — pick an allowed family id.\n"
            + "2) {\"response_version\":\"1.0\",\"plan\":{...full PlanEnvelope...}} — emit a full "
            + "plan (plan_id, query_id, manifest_id, root, nodes). "
            + "Do not invent unsupported operators; illegal plans are rejected."));
    StringBuilder user = new StringBuilder();
    user.append("query_id=").append(ir != null ? ir.query_id : "?").append('\n');
    user.append("manifest_id=").append(
        ir != null && ir.snapshot != null ? ir.snapshot.manifest_id : "?").append('\n');
    user.append("has_temporal=").append(ir != null && ir.temporal != null).append('\n');
    user.append("has_spatial=").append(ir != null && ir.spatial != null).append('\n');
    user.append("has_vehicle_eq=")
        .append(ir != null && PlanBuilder.vehicleEqPredicateIndex(ir) >= 0).append('\n');
    if (ir != null && ir.result != null) {
      user.append("result_mode=").append(ir.result.mode).append('\n');
    }
    user.append("allowed_plan_ids (shortcut form):\n");
    for (PlanEnvelope e : family) {
      if (e != null && e.plan_id != null) {
        user.append("  - ").append(e.plan_id).append('\n');
      }
    }
    if (!family.isEmpty() && family.get(0) != null) {
      try {
        user.append("example_plan_envelope (form 2 may follow this shape):\n");
        user.append(family.get(0).toJson()).append('\n');
      } catch (Exception ignored) {
        // omit example if serialization fails
      }
    }
    msgs.add(LlmMessage.user(user.toString()));
    return msgs;
  }

  /**
   * Prefer full {@code plan} envelope; else {@code plan_id} family shortcut.
   */
  static DirectChoice parseChoice(String content, Map<String, PlanEnvelope> byId, BoundIr ir) {
    DirectChoice out = new DirectChoice();
    if (content == null) {
      out.reason = "llm_direct_illegal:null";
      return out;
    }
    String trimmed = content.trim();
    try {
      JsonNode n = MAPPER.readTree(trimmed);
      if (n.has("plan") && n.get("plan").isObject()) {
        PlanEnvelope env = PlanEnvelope.fromJson(n.get("plan").toString());
        out.envelope = env;
        out.reason = "llm_direct_envelope";
        return out;
      }
      // Bare PlanEnvelope root (plan_id + nodes + root)
      if (n.has("nodes") && n.has("root") && n.has("plan_id")) {
        PlanEnvelope env = PlanEnvelope.fromJson(trimmed);
        out.envelope = env;
        out.reason = "llm_direct_envelope";
        return out;
      }
      if (n.has("plan_id") && !n.get("plan_id").isNull()) {
        String planId = n.get("plan_id").asText();
        if (byId != null && byId.containsKey(planId)) {
          out.envelope = byId.get(planId);
          out.reason = "llm_direct";
        } else {
          out.reason = "llm_direct_illegal:" + planId;
        }
        return out;
      }
    } catch (Exception e) {
      out.reason = "llm_direct_parse_error";
      return out;
    }
    out.reason = "llm_direct_illegal";
    return out;
  }

  /** @deprecated use {@link #parseChoice}; kept for unit tests of plan_id path */
  static String parsePlanId(String content) {
    DirectChoice c = parseChoice(content, null, null);
    if (c.envelope != null && c.envelope.plan_id != null
        && "llm_direct".equals(c.reason)) {
      return c.envelope.plan_id;
    }
    if (content == null) {
      return null;
    }
    try {
      JsonNode n = MAPPER.readTree(content.trim());
      if (n.has("plan_id") && !n.get("plan_id").isNull()) {
        return n.get("plan_id").asText();
      }
    } catch (Exception ignored) {
      // ignore
    }
    return null;
  }

  private static Map<String, PlanEnvelope> indexByPlanId(List<PlanEnvelope> family) {
    Map<String, PlanEnvelope> m = new LinkedHashMap<String, PlanEnvelope>();
    if (family == null) {
      return m;
    }
    for (PlanEnvelope e : family) {
      if (e != null && e.plan_id != null) {
        m.put(e.plan_id, e);
      }
    }
    return m;
  }

  static final class DirectChoice {
    PlanEnvelope envelope;
    String reason;
  }
}
