package kart.llm;

/**
 * Aggregates LLM call counts and tokens across NL parse + plan search (NFR-3).
 */
public final class LlmUsageAccumulator {

  private int calls;
  private long tokens;
  private boolean anyTokenObserved;

  public void record(LlmResponse resp) {
    calls++;
    if (resp == null) {
      return;
    }
    int add = 0;
    boolean saw = false;
    if (resp.promptTokens != null) {
      add += resp.promptTokens.intValue();
      saw = true;
    }
    if (resp.completionTokens != null) {
      add += resp.completionTokens.intValue();
      saw = true;
    }
    if (saw) {
      tokens += add;
      anyTokenObserved = true;
    }
  }

  public int calls() {
    return calls;
  }

  /** Null when no provider reported token usage. */
  public Long tokensOrNull() {
    return anyTokenObserved ? Long.valueOf(tokens) : null;
  }

  public Integer callsOrNull() {
    return calls > 0 ? Integer.valueOf(calls) : null;
  }

  public Long callsAsLongOrNull() {
    return calls > 0 ? Long.valueOf(calls) : null;
  }
}
