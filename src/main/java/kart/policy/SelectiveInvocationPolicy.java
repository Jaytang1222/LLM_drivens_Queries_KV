package kart.policy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Selective invocation policy (comparative_refine_8 §3 / §4).
 * Receives only pre-execution context; never measured winner labels.
 */
public final class SelectiveInvocationPolicy {
  public static final String VERSION = "v12-selective";

  /** Suggested short-query CBO exec estimate band (ms). Not a guarantee. */
  public static long shortExecThresholdMs() {
    return envLong("KART_SELECTIVE_SHORT_EXEC_MS", 500L);
  }

  public static long wideTemporalMs() {
    return envLong("KART_SELECTIVE_WIDE_TEMPORAL_MS", 7L * 24L * 3600L * 1000L);
  }

  public static long estLlmMs() {
    // High-percentile same-config estimate; override from refine_7 resource runs.
    return envLong("KART_CBO_LLM_EST_CRITICAL_MS", 900L);
  }

  public static long estOverheadMs() {
    return envLong("KART_SELECTIVE_OVERHEAD_MS", 50L);
  }

  public static long minNetMs() {
    return envLong("KART_SELECTIVE_MIN_NET_MS", 200L);
  }

  public static boolean selectiveMode() {
    String mode = System.getenv("KART_CBO_LLM_MODE");
    if (mode != null) {
      String v = mode.trim().toLowerCase();
      if ("always".equals(v) || "force".equals(v)) {
        return false;
      }
      if ("selective".equals(v) || "v12".equals(v)) {
        return true;
      }
    }
    String proto = System.getenv("KART_CBO_LLM_PROTOCOL");
    if (proto != null) {
      String p = proto.trim().toLowerCase();
      if ("v12".equals(p) || "selective".equals(p) || "v12-selective".equals(p)) {
        return true;
      }
    }
    return false;
  }

  /**
   * G0: cheap skip using CBO + IR only. No candidate compile / HBase.
   */
  public InvocationDecision decideG0(QueryOpportunityFeatures f) {
    Map<String, Object> ev = f == null
        ? new LinkedHashMap<String, Object>()
        : f.toMap();
    long L = estLlmMs();
    long H = estOverheadMs();
    String cbo = f == null ? null : f.cboPlanId;

    if (f == null) {
      return skip("insufficient_evidence", cbo, L, H, ev);
    }

    // Explicit short / low remaining work: do not pay model tax.
    boolean shortBand = f.cboEstimatedMs != null
        && Double.isFinite(f.cboEstimatedMs.doubleValue())
        && f.cboEstimatedMs.doubleValue() < shortExecThresholdMs();
    boolean wideTime = f.temporalSpanMs >= wideTemporalMs();
    boolean complexSim = f.similarity && (f.topK || wideTime);

    if (shortBand && !complexSim) {
      return skip("short_query", cbo, L, H, ev);
    }
    if (f.singleIndexSelected && !complexSim) {
      return skip("no_candidate_opportunity", cbo, L, H, ev);
    }
    // Complexity alone is not enough (doc §3.2); require joint+tradeoff shape.
    if (f.jointSelected && (complexSim || (f.hasTemporal && f.hasSpatial && wideTime))) {
      return continueG0("joint_tradeoff_candidate", cbo, L, H, null, null, ev);
    }
    if (f.jointSelected && f.cboEstimatedMs != null
        && f.cboEstimatedMs.doubleValue() >= shortExecThresholdMs() * 2
        && f.hasTemporal && f.hasSpatial) {
      return continueG0("long_joint_access", cbo, L, H, null, null, ev);
    }
    if (!f.hasTemporal && !f.hasSpatial) {
      return skip("no_supported_action", cbo, L, H, ev);
    }
    return skip("no_candidate_opportunity", cbo, L, H, ev);
  }

  /**
   * G1: after limited ranking — call only if conservative net may cover L+H.
   * {@code admissible} may be broader than {@code confident}.
   */
  public InvocationDecision decideG1(InvocationDecision g0,
                                     String deterministicAction,
                                     Double preferSHat,
                                     Double conservativeNet,
                                     List<String> admissible,
                                     List<String> confident) {
    Map<String, Object> ev = new LinkedHashMap<String, Object>(g0.evidence);
    if (admissible != null) {
      ev.put("admissible_n", Integer.valueOf(admissible.size()));
    }
    if (confident != null) {
      ev.put("confident_n", Integer.valueOf(confident.size()));
    }
    long L = g0.estLlmMs > 0 ? g0.estLlmMs : estLlmMs();
    long H = g0.estOverheadMs > 0 ? g0.estOverheadMs : estOverheadMs();
    long minNet = minNetMs();

    if (g0.g0 == InvocationDecision.Gate.SKIP) {
      return g0;
    }
    if (admissible == null || admissible.isEmpty()) {
      return new InvocationDecision(VERSION, InvocationDecision.Gate.CALL,
          InvocationDecision.Gate.SKIP, "no_supported_action", null,
          deterministicAction != null ? deterministicAction : "KEEP",
          g0.cboPlanId, 0L, L, H, preferSHat, conservativeNet, ev);
    }
    // If D(q) already picks a confident plan and there is no uncertain
    // alternative beyond that set, calling LLM cannot expand the decision
    // space — take deterministic adopt (system gain without claiming LLM gain).
    boolean dIsConfident = deterministicAction != null
        && !"KEEP".equals(deterministicAction)
        && confident != null && confident.contains(deterministicAction);
    int uncertainN = 0;
    if (admissible != null) {
      for (String a : admissible) {
        if (confident == null || !confident.contains(a)) {
          uncertainN++;
        }
      }
    }
    ev.put("uncertain_n", Integer.valueOf(uncertainN));
    // Goal separation (§1.1): if deterministic calibrator already has a
    // confident adopt, take it without paying LLM. LLM is reserved for cases
    // where D(q)=KEEP but admissible actions remain within budget.
    if (dIsConfident) {
      return new InvocationDecision(VERSION, InvocationDecision.Gate.CALL,
          InvocationDecision.Gate.SKIP, "deterministic_sufficient", null,
          deterministicAction, g0.cboPlanId, 0L, L, H, preferSHat, conservativeNet, ev);
    }
    double cons = conservativeNet == null || !Double.isFinite(conservativeNet.doubleValue())
        ? Double.NEGATIVE_INFINITY : conservativeNet.doubleValue();
    // Expected remaining benefit must cover estimated LLM critical path + overhead.
    if (cons < (L + H + minNet)) {
      return new InvocationDecision(VERSION, InvocationDecision.Gate.CALL,
          InvocationDecision.Gate.SKIP, "budget_exhausted", null,
          deterministicAction != null ? deterministicAction : "KEEP",
          g0.cboPlanId, 0L, L, H, preferSHat, Double.valueOf(cons), ev);
    }
    long budget = Math.max(200L, Math.min(L * 3L,
        Math.max(L, (long) Math.floor(cons - H - minNet))));
    // Cap far below the historical 120s development interrupt ceiling.
    long hardCap = envLong("KART_SELECTIVE_LLM_HARD_CAP_MS", 8000L);
    if (budget > hardCap) {
      budget = hardCap;
    }
    if (budget < Math.max(200L, L / 2L)) {
      // Below a completable call — zero-call rather than fire-and-timeout.
      return new InvocationDecision(VERSION, InvocationDecision.Gate.CALL,
          InvocationDecision.Gate.SKIP, "budget_exhausted", null,
          deterministicAction != null ? deterministicAction : "KEEP",
          g0.cboPlanId, 0L, L, H, preferSHat, Double.valueOf(cons), ev);
    }
    return new InvocationDecision(VERSION, InvocationDecision.Gate.CALL,
        InvocationDecision.Gate.CALL, null, "expected_net_covers_llm",
        deterministicAction != null ? deterministicAction : "KEEP",
        g0.cboPlanId, budget, L, H, preferSHat, Double.valueOf(cons), ev);
  }

  private static InvocationDecision skip(String reason, String cbo, long L, long H,
                                         Map<String, Object> ev) {
    return new InvocationDecision(VERSION, InvocationDecision.Gate.SKIP,
        InvocationDecision.Gate.SKIP, reason, null, "KEEP", cbo, 0L, L, H,
        null, null, ev);
  }

  private static InvocationDecision continueG0(String why, String cbo, long L, long H,
                                               Double prefer, Double cons,
                                               Map<String, Object> ev) {
    return new InvocationDecision(VERSION, InvocationDecision.Gate.CALL,
        InvocationDecision.Gate.CALL, null, why, "KEEP", cbo, 0L, L, H,
        prefer, cons, ev);
  }

  private static long envLong(String key, long def) {
    String raw = System.getenv(key);
    if (raw == null || raw.trim().isEmpty()) {
      return def;
    }
    try {
      return Long.parseLong(raw.trim());
    } catch (NumberFormatException e) {
      return def;
    }
  }
}
