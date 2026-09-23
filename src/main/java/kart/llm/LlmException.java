package kart.llm;

/**
 * LLM gateway failure.
 */
public final class LlmException extends Exception {

  public LlmException(String message) {
    super(message);
  }

  public LlmException(String message, Throwable cause) {
    super(message, cause);
  }
}
