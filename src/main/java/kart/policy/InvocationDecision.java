package kart.policy;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Immutable invocation decision captured before any LLM response
 * (comparative_refine_8 §3 / §6.1). Must not be overwritten after the model returns.
 */
public final class InvocationDecision {
  public enum Gate { SKIP, CALL }

  public final String policyVersion;
  public final Gate g0;
  public final Gate g1;
  public final String skipReason;
  public final String callReason;
  public final String deterministicAction; // KEEP or plan_id = D(q)
  public final String cboPlanId;
  public final long budgetLlmMs;
  public final long estLlmMs;
  public final long estOverheadMs;
  public final Double preferSHatMs;
  public final Double conservativeNetMs;
  public final Map<String, Object> evidence;

  public InvocationDecision(String policyVersion, Gate g0, Gate g1, String skipReason,
                            String callReason, String deterministicAction, String cboPlanId,
                            long budgetLlmMs, long estLlmMs, long estOverheadMs,
                            Double preferSHatMs, Double conservativeNetMs,
                            Map<String, Object> evidence) {
    this.policyVersion = policyVersion;
    this.g0 = g0;
    this.g1 = g1;
    this.skipReason = skipReason;
    this.callReason = callReason;
    this.deterministicAction = deterministicAction;
    this.cboPlanId = cboPlanId;
    this.budgetLlmMs = budgetLlmMs;
    this.estLlmMs = estLlmMs;
    this.estOverheadMs = estOverheadMs;
    this.preferSHatMs = preferSHatMs;
    this.conservativeNetMs = conservativeNetMs;
    this.evidence = evidence == null
        ? Collections.<String, Object>emptyMap()
        : Collections.unmodifiableMap(new LinkedHashMap<String, Object>(evidence));
  }

  public boolean shouldCallLlm() {
    return g0 == Gate.CALL && g1 == Gate.CALL;
  }

  public Map<String, Object> toExtras() {
    Map<String, Object> m = new LinkedHashMap<String, Object>();
    m.put("policy_version", policyVersion);
    m.put("g0_decision", g0 == null ? null : g0.name());
    m.put("g1_decision", g1 == null ? null : g1.name());
    if (skipReason != null) {
      m.put("gate_skip_reason", skipReason);
    }
    if (callReason != null) {
      m.put("gate_call_reason", callReason);
    }
    m.put("deterministic_action_before_llm", deterministicAction);
    m.put("cbo_plan", cboPlanId);
    m.put("llm_budget_ms", Long.valueOf(budgetLlmMs));
    m.put("est_llm_ms", Long.valueOf(estLlmMs));
    m.put("est_overhead_ms", Long.valueOf(estOverheadMs));
    if (preferSHatMs != null) {
      m.put("prefer_s_hat_ms", preferSHatMs);
    }
    if (conservativeNetMs != null) {
      m.put("conservative_net_ms", conservativeNetMs);
    }
    for (Map.Entry<String, Object> e : evidence.entrySet()) {
      m.put("feat_" + e.getKey(), e.getValue());
    }
    return m;
  }
}
