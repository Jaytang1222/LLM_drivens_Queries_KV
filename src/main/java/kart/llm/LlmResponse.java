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

  public LlmResponse() {}

  public LlmResponse(String content) {
    this.content = content;
  }
}
