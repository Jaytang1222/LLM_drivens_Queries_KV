package kart.llm;

/**
 * Per-call LLM options.
 */
public final class LlmOptions {

  /** Null means use OpenAiCompatibleClient / env LLM_MODEL default. */
  public String model = null;
  public double temperature = 0.0;
  public int timeoutMs = 60_000;
  public boolean jsonObjectFormat = true;

  public static LlmOptions defaults() {
    return new LlmOptions();
  }
}
