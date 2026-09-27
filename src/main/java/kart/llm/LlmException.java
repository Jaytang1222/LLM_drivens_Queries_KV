package kart.llm;

/**
 * LLM gateway failure.
 */
public final class LlmException extends Exception {

  /** HTTP status when the failure came from the chat API; null for local errors. */
  public final Integer httpStatus;

  public LlmException(String message) {
    this(message, null, null);
  }

  public LlmException(String message, Throwable cause) {
    this(message, cause, null);
  }

  public LlmException(String message, Integer httpStatus) {
    this(message, null, httpStatus);
  }

  public LlmException(String message, Throwable cause, Integer httpStatus) {
    super(message, cause);
    this.httpStatus = httpStatus;
  }
}
