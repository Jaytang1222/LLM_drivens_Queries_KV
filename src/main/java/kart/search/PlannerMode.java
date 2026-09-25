package kart.search;

/**
 * Plan-search policy mode (IMPLEMENTATION_PLAN §11 / §19.1 baselines).
 *
 * <ul>
 *   <li>{@link #RULE} — fixed-order {@link RulePolicy}</li>
 *   <li>{@link #BEST_FIRST} — FastCost-ordered {@link BestFirstPolicy} (§19.1 #4)</li>
 *   <li>{@link #LLM} — frontier {@code proposals[]} via {@link LlmProposalPolicy} (§11.4)</li>
 *   <li>{@link #LLM_DIRECT} — one-shot full plan from constructor family, same validator (§19.1 #5)</li>
 * </ul>
 */
public enum PlannerMode {
  RULE,
  BEST_FIRST,
  LLM,
  LLM_DIRECT;

  public static PlannerMode parse(String raw) {
    if (raw == null || raw.trim().isEmpty()) {
      return null;
    }
    String s = raw.trim().toLowerCase().replace('-', '_');
    if ("rule".equals(s) || "rbo".equals(s)) {
      return RULE;
    }
    if ("best_first".equals(s) || "bestfirst".equals(s) || "bf".equals(s)) {
      return BEST_FIRST;
    }
    if ("llm".equals(s) || "llm_action".equals(s) || "action".equals(s)) {
      return LLM;
    }
    if ("llm_direct".equals(s) || "direct".equals(s) || "llm_full".equals(s)) {
      return LLM_DIRECT;
    }
    throw new IllegalArgumentException(
        "unknown planner policy '" + raw + "' (use rule|best_first|llm|llm_direct)");
  }

  public String wireName() {
    switch (this) {
      case RULE:
        return "rule";
      case BEST_FIRST:
        return "best_first";
      case LLM:
        return "llm";
      case LLM_DIRECT:
        return "llm_direct";
      default:
        return name().toLowerCase();
    }
  }
}
