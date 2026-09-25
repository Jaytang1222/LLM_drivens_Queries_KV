package kart.llm;

/**
 * Aggregates LLM call counts and tokens across NL parse + plan search (NFR-3).
 */
public final class LlmUsageAccumulator {

  private int calls;
  private long tokens;
  private long promptTokens;
  private long completionTokens;
  private long latencyMs;
  private int jsonModeFallbacks;
  private boolean anyTokenObserved;

  public void record(LlmResponse resp) {
    int attempts = 1;
    if (resp != null && resp.attempts > 0) {
      attempts = resp.attempts;
    }
    calls += attempts;
    if (resp != null && resp.jsonModeFallback) {
      jsonModeFallbacks++;
    }
    if (resp != null) {
      latencyMs += resp.latencyMs;
    }
    if (resp == null) {
      return;
    }
    boolean saw = false;
    if (resp.promptTokens != null) {
      promptTokens += resp.promptTokens.intValue();
      tokens += resp.promptTokens.intValue();
      saw = true;
    }
    if (resp.completionTokens != null) {
      completionTokens += resp.completionTokens.intValue();
      tokens += resp.completionTokens.intValue();
      saw = true;
    }
    if (saw) {
      anyTokenObserved = true;
    }
  }

  /** Count a failed HTTP attempt that had no parseable response (JSON-mode fallback). */
  public void recordFailedAttempt(long latencyMsAdd) {
    calls++;
    latencyMs += Math.max(0L, latencyMsAdd);
  }

  public int calls() {
    return calls;
  }

  public long latencyMs() {
    return latencyMs;
  }

  public int jsonModeFallbacks() {
    return jsonModeFallbacks;
  }

  /** Null when no provider reported token usage. */
  public Long tokensOrNull() {
    return anyTokenObserved ? Long.valueOf(tokens) : null;
  }

  public Long promptTokensOrNull() {
    return anyTokenObserved ? Long.valueOf(promptTokens) : null;
  }

  public Long completionTokensOrNull() {
    return anyTokenObserved ? Long.valueOf(completionTokens) : null;
  }

  public Integer callsOrNull() {
    return calls > 0 ? Integer.valueOf(calls) : null;
  }

  public Long callsAsLongOrNull() {
    return calls > 0 ? Long.valueOf(calls) : null;
  }
}
