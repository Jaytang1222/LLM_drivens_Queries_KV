package kart.bench;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kart.cost.CostCard;
import kart.ir.BoundIr;
import kart.llm.LlmClient;
import kart.llm.LlmException;
import kart.llm.LlmMessage;
import kart.llm.LlmOptions;
import kart.llm.LlmResponse;
import kart.llm.LlmUsageAccumulator;
import kart.query.QueryEngine;
import kart.search.PlannerMode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Conditional LLM plan arm (comparative §6.3): one CBO search enumerates SafePlans
 * + CostCards; LLM is invoked only when pre-declared uncertainty triggers fire;
 * otherwise CBO wins. Selection reuses the same SafePlan objects — no second search.
 *
 * Frozen triggers (see {@code experiments/suites/conditional_llm_freeze.json}):
 * <ul>
 *   <li>{@code n_safe >= 2}</li>
 *   <li>relative gap between best and second {@code <= rel_gap}</li>
 *   <li>or any card {@code uncertainty.label=HIGH}</li>
 * </ul>
 * Constructor / freeze-file values are the effective thresholds for the arm instance.
 * Env {@code KART_COND_*} is consulted only when constructing from env and freeze is
 * not {@code frozen_for_test}. Runtime {@link #shouldTrigger(List)} never re-reads env.
 *
 * Timing: {@code t_plan_ms = plan_end_ms - plan_start_ms} covers CBO generate→select
 * (plus optional LLM pick + reselect). Coordinator execute and artifact IO are excluded
 * even when {@code --keep-artifacts} is set (plan search always uses a null runsRoot).
 */
public final class ConditionalLlmArm implements Arm {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String FREEZE_REL = "experiments/suites/conditional_llm_freeze.json";

  private final double relGapThreshold;
  private final int maxLlmCalls;
  private final String freezeStatus;
  private final String freezePath;

  public ConditionalLlmArm() {
    this(envDouble("KART_COND_REL_GAP", 0.15), envInt("KART_COND_MAX_LLM", 1),
        "env_defaults", null);
  }

  public ConditionalLlmArm(double relGapThreshold, int maxLlmCalls) {
    this(relGapThreshold, maxLlmCalls, "explicit", null);
  }

  public ConditionalLlmArm(double relGapThreshold, int maxLlmCalls,
                           String freezeStatus, String freezePath) {
    this.relGapThreshold = relGapThreshold;
    this.maxLlmCalls = Math.max(1, maxLlmCalls);
    this.freezeStatus = freezeStatus != null ? freezeStatus : "explicit";
    this.freezePath = freezePath;
  }

  /** Prefer freeze file under repo root; falls back to env defaults. */
  public static ConditionalLlmArm fromRoot(Path root) {
    Path freeze = root == null ? null : root.resolve(FREEZE_REL);
    if (freeze == null || !Files.isRegularFile(freeze)) {
      return new ConditionalLlmArm();
    }
    try {
      JsonNode n = MAPPER.readTree(freeze.toFile());
      double gap = n.path("rel_gap").asDouble(0.15);
      int max = n.path("max_llm").asInt(1);
      String status = n.path("status").asText("unfrozen_defaults");
      boolean locked = "frozen_for_test".equals(status);
      if (!locked) {
        // Ablation / train tuning may override via env while unfrozen.
        gap = envDouble("KART_COND_REL_GAP", gap);
        max = envInt("KART_COND_MAX_LLM", max);
      }
      return new ConditionalLlmArm(gap, max, status,
          freeze.toAbsolutePath().normalize().toString());
    } catch (Exception e) {
      return new ConditionalLlmArm();
    }
  }

  @Override
  public String id() {
    return "kart-conditional-llm";
  }

  public double relGapThreshold() {
    return relGapThreshold;
  }

  public int maxLlmCalls() {
    return maxLlmCalls;
  }

  @Override
  public TrialResult run(BoundIr ir, BenchContext ctx) throws Exception {
    long wall0 = System.currentTimeMillis();
    long planStart = System.currentTimeMillis();
    QueryEngine cbo = ctx.newEngine(PlannerMode.BEST_FIRST);
    // Never write artifacts inside the plan clock (Bao-compatible). keep-artifacts
    // only applies to executeSelected after plan_end.
    Path art = ctx.planOnly ? null : ctx.artifactDir(id(), ir.query_id);
    QueryEngine.RunResult planned = cbo.run(ir, planClockRunsRoot(), true, null);
    if (planned == null || planned.selected == null) {
      TrialResult fail = TrialResult.fail(planned != null && planned.result != null
          ? planned.result.error : "conditional: CBO produced no SafePlan");
      long now = System.currentTimeMillis();
      fail.extras.put("plan_start_ms", Long.valueOf(planStart));
      fail.extras.put("plan_end_ms", Long.valueOf(now));
      fail.t_plan_ms = Long.valueOf(Math.max(0L, now - planStart));
      fail.extras.put("t_wall_ms", Long.valueOf(Math.max(0L, now - wall0)));
      stampFreezeExtras(fail);
      return fail;
    }

    List<CostCard> cards = planned.costCards != null ? planned.costCards : new ArrayList<CostCard>();
    String cboPlanId = planned.selected.plan() != null ? planned.selected.plan().plan_id : null;
    boolean triggered = shouldTrigger(cards);
    String chosen = cboPlanId;
    boolean usedLlm = false;
    String fallbackReason = null;
    String triggerReason = triggered ? describeTrigger(cards, relGapThreshold) : "not_triggered";
    LlmUsageAccumulator usage = new LlmUsageAccumulator();

    if (triggered && ctx.llm != null) {
      String llmPick = askLlmPick(ctx.llm, usage, ir, cards, cboPlanId);
      if (llmPick != null && containsPlan(cards, llmPick)) {
        chosen = llmPick;
        usedLlm = true;
      } else {
        fallbackReason = llmPick == null
            ? (usage.failedAttempts() > 0 ? "http_failure" : "empty_plan_id")
            : "illegal_plan_id";
        triggerReason = triggerReason + ";llm_fallback_cbo";
      }
    } else if (triggered && ctx.llm == null) {
      fallbackReason = "no_llm_client";
      triggerReason = triggerReason + ";no_llm_client";
    }

    if (chosen != null && !chosen.equals(cboPlanId)) {
      if (!QueryEngine.reselectByPlanId(planned, chosen)) {
        chosen = cboPlanId;
        usedLlm = false;
        fallbackReason = "reselect_miss";
        triggerReason = triggerReason + ";reselect_miss_cbo";
      }
    }

    long planEnd = System.currentTimeMillis();
    planned.planStartEpochMs = planStart;
    planned.planEndEpochMs = planEnd;
    planned.t_plan_ms = Long.valueOf(Math.max(0L, planEnd - planStart));

    QueryEngine.RunResult executed;
    if (ctx.planOnly) {
      executed = planned;
    } else {
      executed = cbo.executeSelected(ir, art, planned);
      // Preserve generate→select clock; executeSelected may overwrite with exec-only stamps.
      executed.planStartEpochMs = planStart;
      executed.planEndEpochMs = planEnd;
      executed.t_plan_ms = planned.t_plan_ms;
    }

    long wall = Math.max(0L, System.currentTimeMillis() - wall0);
    TrialResult tr = TrialResult.fromRun(executed, wall);
    // Do not applyWrapperPlanClock (that folded exec into plan when planOnly).
    tr.t_plan_ms = planned.t_plan_ms;
    tr.extras.put("plan_start_ms", Long.valueOf(planStart));
    tr.extras.put("plan_end_ms", Long.valueOf(planEnd));
    if (tr.t_exec_ms != null && tr.t_plan_ms != null) {
      tr.t_e2e_ms = Long.valueOf(tr.t_plan_ms.longValue() + tr.t_exec_ms.longValue());
    } else if (tr.t_plan_ms != null) {
      tr.t_e2e_ms = tr.t_plan_ms;
    }
    tr.extras.put("conditional_llm_triggered", Boolean.valueOf(triggered));
    tr.extras.put("conditional_trigger_reason", triggerReason);
    stampConditionalOutcome(tr, triggered, usedLlm, fallbackReason);
    tr.extras.put("conditional_cbo_plan_id", cboPlanId);
    tr.extras.put("conditional_chosen_plan_id", chosen);
    tr.extras.put("conditional_used_llm", Boolean.valueOf(usedLlm));
    stampFreezeExtras(tr);
    tr.extras.put("conditional_second_search", Boolean.FALSE);
    tr.extras.put("conditional_plan_runs_root_null", Boolean.TRUE);
    tr.extras.put("n_safe", Integer.valueOf(cards.size()));
    tr.extras.put("llm_calls", usage.callsAsLongOrNull());
    tr.extras.put("tokens_in", usage.promptTokensOrNull());
    tr.extras.put("tokens_out", usage.completionTokensOrNull());
    if (usage.failedAttempts() > 0) {
      tr.extras.put("llm_failed_attempts", Integer.valueOf(usage.failedAttempts()));
    }
    return tr;
  }

  /**
   * Three-way label for summaries. Fallback is never a pure-LLM success.
   * {@code llm_fallback=true} is the field {@code SuiteRunner} already copies.
   */
  static void stampConditionalOutcome(TrialResult tr, boolean triggered, boolean usedLlm,
                                      String fallbackReason) {
    if (tr == null) {
      return;
    }
    if (!triggered) {
      tr.extras.put("conditional_outcome", "conditional_not_triggered");
      tr.extras.put("llm_fallback", Boolean.FALSE);
      return;
    }
    if (usedLlm) {
      tr.extras.put("conditional_outcome", "conditional_llm_success");
      tr.extras.put("llm_fallback", Boolean.FALSE);
      return;
    }
    tr.extras.put("conditional_outcome", "conditional_llm_fallback");
    tr.extras.put("llm_fallback", Boolean.TRUE);
    tr.extras.put("fallback_reason",
        fallbackReason == null || fallbackReason.isEmpty() ? "llm_fallback_cbo" : fallbackReason);
  }

  private void stampFreezeExtras(TrialResult tr) {
    tr.extras.put("conditional_rel_gap_threshold", Double.valueOf(relGapThreshold));
    tr.extras.put("conditional_max_llm", Integer.valueOf(maxLlmCalls));
    tr.extras.put("conditional_freeze_status", freezeStatus);
    if (freezePath != null) {
      tr.extras.put("conditional_freeze_path", freezePath);
    }
  }

  /**
   * Runs-root used while the plan clock is open. Always null so QueryEngine cannot
   * fold artifact IO into {@code t_plan_ms} even when {@code --keep-artifacts} is on.
   */
  static Path planClockRunsRoot() {
    return null;
  }

  /** Instance trigger: uses constructor / freeze thresholds, never re-reads env. */
  boolean shouldTrigger(List<CostCard> cards) {
    return shouldTrigger(cards, relGapThreshold);
  }

  static boolean shouldTrigger(List<CostCard> cards, double relGapThreshold) {
    if (cards == null || cards.size() < 2) {
      return false;
    }
    if (anyHighUncertainty(cards)) {
      return true;
    }
    double[] top2 = topTwo(cards);
    if (top2 == null) {
      return false;
    }
    double best = top2[0];
    double second = top2[1];
    double denom = Math.max(best, 1.0);
    double rel = (second - best) / denom;
    return rel <= relGapThreshold;
  }

  static String describeTrigger(List<CostCard> cards, double relGapThreshold) {
    List<String> reasons = new ArrayList<String>();
    if (cards != null && cards.size() >= 2) {
      reasons.add("n_safe=" + cards.size());
    }
    if (anyHighUncertainty(cards)) {
      reasons.add("uncertainty_HIGH");
    }
    double[] top2 = topTwo(cards);
    if (top2 != null) {
      double rel = (top2[1] - top2[0]) / Math.max(top2[0], 1.0);
      reasons.add(String.format(Locale.ROOT, "rel_gap=%.4f", rel));
      reasons.add(String.format(Locale.ROOT, "thr=%.4f", relGapThreshold));
    }
    return reasons.isEmpty() ? "triggered" : String.join(",", reasons);
  }

  private static boolean anyHighUncertainty(List<CostCard> cards) {
    if (cards == null) {
      return false;
    }
    for (CostCard c : cards) {
      if (c != null && c.uncertainty != null && c.uncertainty.label != null
          && "HIGH".equalsIgnoreCase(c.uncertainty.label)) {
        return true;
      }
    }
    return false;
  }

  private static double[] topTwo(List<CostCard> cards) {
    double best = Double.POSITIVE_INFINITY;
    double second = Double.POSITIVE_INFINITY;
    for (CostCard c : cards) {
      if (c == null) {
        continue;
      }
      double v = c.estimated_ms;
      if (v < best) {
        second = best;
        best = v;
      } else if (v < second) {
        second = v;
      }
    }
    if (Double.isInfinite(best) || Double.isInfinite(second)) {
      return null;
    }
    return new double[] {best, second};
  }

  private static boolean containsPlan(List<CostCard> cards, String planId) {
    if (planId == null || cards == null) {
      return false;
    }
    for (CostCard c : cards) {
      if (c != null && planId.equals(c.plan_id)) {
        return true;
      }
    }
    return false;
  }

  private String askLlmPick(LlmClient llm, LlmUsageAccumulator usage, BoundIr ir,
                            List<CostCard> cards, String cboPlanId) {
    StringBuilder sb = new StringBuilder();
    sb.append("Pick exactly one plan_id from the SafePlan candidates for this BoundIR.\n");
    sb.append("Reply with ONLY JSON: {\"plan_id\":\"P_...\",\"reason\":\"...\"}\n");
    sb.append("query_id=").append(ir != null ? ir.query_id : "?").append('\n');
    sb.append("cbo_default=").append(cboPlanId).append('\n');
    sb.append("candidates:\n");
    for (CostCard c : cards) {
      if (c == null) {
        continue;
      }
      sb.append("- ").append(c.plan_id)
          .append(" estimated_ms=").append(c.estimated_ms)
          .append(" uncertainty=")
          .append(c.uncertainty != null ? c.uncertainty.label : "?")
          .append('\n');
    }
    List<LlmMessage> msgs = new ArrayList<LlmMessage>();
    msgs.add(LlmMessage.system("You select among validated SafePlans. Never invent plan_ids."));
    msgs.add(LlmMessage.user(sb.toString()));
    try {
      for (int i = 0; i < maxLlmCalls; i++) {
        LlmResponse resp = llm.chat(msgs, null, LlmOptions.defaults());
        if (usage != null) {
          usage.record(resp);
        }
        String content = resp != null ? resp.content : null;
        String pid = extractPlanId(content);
        if (pid != null) {
          return pid;
        }
      }
    } catch (LlmException e) {
      if (usage != null) {
        usage.recordHttpFailure(e.httpStatus, 0L);
      }
    }
    return null;
  }

  static String extractPlanId(String content) {
    if (content == null) {
      return null;
    }
    int idx = content.indexOf("P_");
    if (idx < 0) {
      return null;
    }
    int end = idx + 2;
    while (end < content.length()) {
      char c = content.charAt(end);
      if (!(Character.isLetterOrDigit(c) || c == '_')) {
        break;
      }
      end++;
    }
    return content.substring(idx, end);
  }

  private static double envDouble(String key, double def) {
    String v = System.getenv(key);
    if (v == null || v.trim().isEmpty()) {
      return def;
    }
    try {
      return Double.parseDouble(v.trim());
    } catch (NumberFormatException e) {
      return def;
    }
  }

  private static int envInt(String key, int def) {
    String v = System.getenv(key);
    if (v == null || v.trim().isEmpty()) {
      return def;
    }
    try {
      return Integer.parseInt(v.trim());
    } catch (NumberFormatException e) {
      return def;
    }
  }
}
