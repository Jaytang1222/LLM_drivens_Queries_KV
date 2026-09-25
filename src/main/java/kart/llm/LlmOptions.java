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
}
