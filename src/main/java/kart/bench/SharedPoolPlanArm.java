package kart.bench;

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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * E2 shared-pool selection arms: one PlanBuilder+validator enumeration, then
 * selector-only choice among the same SafePlan set.
 *
 * <ul>
 *   <li>{@code pool-cbo} — argmin CostCard</li>
 *   <li>{@code pool-bao} — Bao surrogate reward on the same cards</li>
 *   <li>{@code pool-conditional-llm} — conditional LLM pick / else argmin</li>
 *   <li>{@code pool-llm} — always ask LLM on the shared pool (else argmin)</li>
 * </ul>
 *
 * Native-search arms ({@code kart}, {@code cbo}, {@code bao}, …) remain separate;
 * do not mix {@code plan_regret_ms} scopes across tables.
 */
public final class SharedPoolPlanArm implements Arm {

  public enum Selector {
    CBO_ARGMIN,
    BAO_SURROGATE,
    CONDITIONAL_LLM,
    LLM_ALWAYS
  }

  private final String armId;
  private final Selector selector;
  private final Map<String, Double> familyWeight;
  private final ConditionalLlmArm condHelper;

  public SharedPoolPlanArm(String armId, Selector selector, Path root) {
    this.armId = armId;
    this.selector = selector;
    Path wp = root == null ? null
        : root.resolve("experiments/adapters/bao/kart_plan_family_weights.json");
    this.familyWeight = loadWeights(wp);
    this.condHelper = ConditionalLlmArm.fromRoot(root);
  }

  public static SharedPoolPlanArm cbo(Path root) {
    return new SharedPoolPlanArm("pool-cbo", Selector.CBO_ARGMIN, root);
  }

  public static SharedPoolPlanArm bao(Path root) {
    return new SharedPoolPlanArm("pool-bao", Selector.BAO_SURROGATE, root);
  }

  public static SharedPoolPlanArm conditionalLlm(Path root) {
    return new SharedPoolPlanArm("pool-conditional-llm", Selector.CONDITIONAL_LLM, root);
  }

  public static SharedPoolPlanArm llm(Path root) {
    return new SharedPoolPlanArm("pool-llm", Selector.LLM_ALWAYS, root);
  }

  @Override
  public String id() {
    return armId;
  }

  @Override
  public TrialResult run(BoundIr ir, BenchContext ctx) throws Exception {
    long wall0 = System.currentTimeMillis();
    long planStart = System.currentTimeMillis();
    QueryEngine engine = ctx.newEngine(PlannerMode.RULE);
    Path art = ctx.planOnly ? null : ctx.artifactDir(id(), ir.query_id);

    long enumStart = System.currentTimeMillis();
    QueryEngine.RunResult pool = SharedCandidatePool.enumerate(engine, ir);
    long tEnum = Math.max(0L, System.currentTimeMillis() - enumStart);
    if (pool == null || pool.safe == null || pool.safe.isEmpty()) {
      TrialResult fail = TrialResult.fail("shared-pool: no SafePlans after PlanBuilder enum");
      long now = System.currentTimeMillis();
      fail.t_plan_ms = Long.valueOf(Math.max(0L, now - planStart));
      fail.extras.put("plan_start_ms", Long.valueOf(planStart));
      fail.extras.put("plan_end_ms", Long.valueOf(now));
      fail.extras.put("shared_pool", Boolean.TRUE);
      fail.extras.put("t_pool_enum_ms", Long.valueOf(tEnum));
      return fail;
    }

    String cboDefault = pool.selected != null && pool.selected.plan() != null
        ? pool.selected.plan().plan_id : null;
    boolean triggered = false;
    boolean usedLlm = false;
    String fallbackReason = null;
    String selectMode;
    LlmUsageAccumulator usage = new LlmUsageAccumulator();

    switch (selector) {
      case BAO_SURROGATE:
        SharedCandidatePool.selectBaoSurrogate(pool, familyWeight);
        selectMode = "bao_surrogate_on_shared_pool";
        break;
      case LLM_ALWAYS:
        triggered = true;
        selectMode = "llm_always_on_shared_pool";
        if (ctx.llm != null) {
          String pick = askLlm(ctx.llm, usage, ir, pool.costCards, cboDefault);
          if (pick != null && QueryEngine.reselectByPlanId(pool, pick)) {
            usedLlm = true;
          } else {
            SharedCandidatePool.selectArgminCost(pool);
            fallbackReason = pick == null
                ? (usage.failedAttempts() > 0 ? "http_failure" : "empty_plan_id")
                : "illegal_plan_id";
          }
        } else {
          SharedCandidatePool.selectArgminCost(pool);
          fallbackReason = "no_llm_client";
        }
        break;
      case CONDITIONAL_LLM:
        triggered = condHelper.shouldTrigger(pool.costCards);
        selectMode = "conditional_llm_on_shared_pool";
        if (triggered && ctx.llm != null) {
          String pick = askLlm(ctx.llm, usage, ir, pool.costCards, cboDefault);
          if (pick != null && QueryEngine.reselectByPlanId(pool, pick)) {
            usedLlm = true;
          } else {
            SharedCandidatePool.selectArgminCost(pool);
            fallbackReason = pick == null
                ? (usage.failedAttempts() > 0 ? "http_failure" : "empty_plan_id")
                : "illegal_plan_id";
          }
        } else if (triggered) {
          SharedCandidatePool.selectArgminCost(pool);
          fallbackReason = "no_llm_client";
        } else {
          SharedCandidatePool.selectArgminCost(pool);
        }
        break;
      case CBO_ARGMIN:
      default:
        SharedCandidatePool.selectArgminCost(pool);
        selectMode = "cbo_argmin_on_shared_pool";
        break;
    }

    long planEnd = System.currentTimeMillis();
    long tPlan = Math.max(0L, planEnd - planStart);
    pool.planStartEpochMs = planStart;
    pool.planEndEpochMs = planEnd;
    pool.t_plan_ms = Long.valueOf(tPlan);
    Double poolRegret = SharedCandidatePool.poolRegretMs(pool);
    if (poolRegret != null) {
      pool.plan_regret_ms = poolRegret;
    }

    QueryEngine.RunResult executed;
    if (ctx.planOnly) {
      executed = pool;
    } else {
      executed = engine.executeSelected(ir, art, pool);
      executed.planStartEpochMs = planStart;
      executed.planEndEpochMs = planEnd;
      executed.t_plan_ms = Long.valueOf(tPlan);
    }

    long wall = Math.max(0L, System.currentTimeMillis() - wall0);
    TrialResult tr = TrialResult.fromRun(executed, wall);
    tr.t_plan_ms = Long.valueOf(tPlan);
    tr.extras.put("plan_start_ms", Long.valueOf(planStart));
    tr.extras.put("plan_end_ms", Long.valueOf(planEnd));
    if (tr.t_exec_ms != null) {
      tr.t_e2e_ms = Long.valueOf(tPlan + tr.t_exec_ms.longValue());
    } else {
      tr.t_e2e_ms = Long.valueOf(tPlan);
    }
    tr.extras.put("shared_pool", Boolean.TRUE);
    tr.extras.put("candidate_search", Boolean.FALSE);
    tr.extras.put("selection", selectMode);
    tr.extras.put("plan_regret_scope", "shared_pool");
    tr.extras.put("t_pool_enum_ms", Long.valueOf(tEnum));
    tr.extras.put("n_safe", Integer.valueOf(pool.safe.size()));
    tr.extras.put("n_pool_built", Integer.valueOf(pool.candidates.size()));
    tr.extras.put("n_pool_rejected", Integer.valueOf(
        pool.rejections == null ? 0 : pool.rejections.size()));
    if (poolRegret != null) {
      tr.extras.put("pool_regret_ms", poolRegret);
    }
    tr.extras.put("shared_pool_catalog", SharedCandidatePool.catalogRow(pool));
    if (selector == Selector.CONDITIONAL_LLM || selector == Selector.LLM_ALWAYS) {
      tr.extras.put("conditional_llm_triggered", Boolean.valueOf(triggered));
      if (selector == Selector.LLM_ALWAYS) {
        stampPoolLlmOutcome(tr, usedLlm, fallbackReason);
      } else {
        ConditionalLlmArm.stampConditionalOutcome(tr, triggered, usedLlm, fallbackReason);
        tr.extras.put("conditional_rel_gap_threshold",
            Double.valueOf(condHelper.relGapThreshold()));
      }
      tr.extras.put("llm_calls", usage.callsAsLongOrNull());
      tr.extras.put("tokens_in", usage.promptTokensOrNull());
      tr.extras.put("tokens_out", usage.completionTokensOrNull());
      if (usage.failedAttempts() > 0) {
        tr.extras.put("llm_failed_attempts", Integer.valueOf(usage.failedAttempts()));
      }
    }
    return tr;
  }

  /** Always-on pool LLM: success vs fallback (no "not triggered" class). */
  static void stampPoolLlmOutcome(TrialResult tr, boolean usedLlm, String fallbackReason) {
    if (tr == null) {
      return;
    }
    if (usedLlm) {
      tr.extras.put("pool_llm_outcome", "pool_llm_success");
      tr.extras.put("llm_fallback", Boolean.FALSE);
      return;
    }
    tr.extras.put("pool_llm_outcome", "pool_llm_fallback");
    tr.extras.put("llm_fallback", Boolean.TRUE);
    tr.extras.put("fallback_reason",
        fallbackReason == null || fallbackReason.isEmpty() ? "llm_fallback_cbo" : fallbackReason);
  }

  private String askLlm(LlmClient llm, LlmUsageAccumulator usage, BoundIr ir,
                        List<CostCard> cards, String cboDefault) {
    StringBuilder sb = new StringBuilder();
    sb.append("Pick exactly one plan_id from the SHARED SafePlan pool.\n");
    sb.append("Reply with ONLY JSON: {\"plan_id\":\"P_...\",\"reason\":\"...\"}\n");
    sb.append("query_id=").append(ir != null ? ir.query_id : "?").append('\n');
    sb.append("cbo_default=").append(cboDefault).append('\n');
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
    msgs.add(LlmMessage.system("You select among a frozen shared SafePlan pool. Never invent ids."));
    msgs.add(LlmMessage.user(sb.toString()));
    try {
      LlmResponse resp = llm.chat(msgs, null, LlmOptions.defaults());
      if (usage != null) {
        usage.record(resp);
      }
      return ConditionalLlmArm.extractPlanId(resp != null ? resp.content : null);
    } catch (LlmException e) {
      if (usage != null) {
        usage.recordHttpFailure(e.httpStatus, 0L);
      }
      return null;
    }
  }

  private static Map<String, Double> loadWeights(Path path) {
    Map<String, Double> out = new HashMap<String, Double>();
    if (path == null || !Files.isRegularFile(path)) {
      return out;
    }
    try {
      com.fasterxml.jackson.databind.JsonNode n =
          new com.fasterxml.jackson.databind.ObjectMapper().readTree(path.toFile());
      if (n != null && n.isObject()) {
        java.util.Iterator<String> it = n.fieldNames();
        while (it.hasNext()) {
          String k = it.next();
          out.put(k, Double.valueOf(n.get(k).asDouble(1.0)));
        }
      }
    } catch (Exception ignore) {
      //
    }
    return out;
  }
}
