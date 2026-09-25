package kart.llm;

/**
 * LLM response plus call metadata for traces.
 */
public final class LlmResponse {

  public String content;
  public String model;
  public Integer promptTokens;
  public Integer completionTokens;
  public long latencyMs;
  public String rawBody;
  /** HTTP posts used for this logical chat (2 when JSON-mode is retried without response_format). */
  public int attempts = 1;
  public boolean jsonModeFallback;

  public LlmResponse() {}

  public LlmResponse(String content) {
    this.content = content;
  }
}
