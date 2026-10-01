package kart.llm;

/**
 * Per-call LLM options.
 */
public final class LlmOptions {

  /** Null means use OpenAiCompatibleClient / env LLM_MODEL default. */
  public String model = null;
  public double temperature = 0.0;
  public int timeoutMs = 30_000;
  public boolean jsonObjectFormat = true;
  /** Opt-in structured decoding; legacy callers retain their existing format. */
  public boolean responseSchemaFormat = false;
  /**
   * When false, {@link OpenAiCompatibleClient} must not retry after HTTP 400
   * JSON-mode rejection (one HTTP attempt only). Used by {@code cbo-llm-proposal}.
   */
  public boolean allowJsonModeRetry = true;
  /**
   * When non-null and &gt; 0, sent as OpenAI-compatible {@code max_tokens}
   * (caps completion length — critical for local small models on plan_id JSON).
   */
  public Integer maxTokens = null;

  public static LlmOptions defaults() {
    LlmOptions o = new LlmOptions();
    String t = System.getenv("KART_LLM_TEMPERATURE");
    if (t != null && !t.trim().isEmpty()) {
      try {
        o.temperature = Double.parseDouble(t.trim());
      } catch (NumberFormatException ignore) {
        o.temperature = 0.0;
      }
    }
    return o;
  }

  /** Defaults with JSON-mode retry disabled and a hard per-call timeout. */
  public static LlmOptions oneShot(int timeoutMs) {
    LlmOptions o = defaults();
    o.allowJsonModeRetry = false;
    o.jsonObjectFormat = false; // prompt requires JSON; avoid format-rejection retry path
    o.timeoutMs = Math.max(1, timeoutMs);
    o.maxTokens = resolveMaxTokensEnv();
    return o;
  }

  /** {@code KART_CBO_LLM_MAX_TOKENS} (default 96) for action/plan_id JSON. */
  public static Integer resolveMaxTokensEnv() {
    String raw = System.getenv("KART_CBO_LLM_MAX_TOKENS");
    if (raw == null || raw.trim().isEmpty()) {
      return Integer.valueOf(96);
    }
    try {
      int v = Integer.parseInt(raw.trim());
      return v > 0 ? Integer.valueOf(v) : null;
    } catch (NumberFormatException e) {
      return Integer.valueOf(96);
    }
  }
}
