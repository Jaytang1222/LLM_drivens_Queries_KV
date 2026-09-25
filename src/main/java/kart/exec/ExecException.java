package kart.exec;

/**
 * Executor failure carrying a machine-readable error code.
 */
public final class ExecException extends RuntimeException {

  public enum Code {
    RESOURCE_EXHAUSTED,
    DATA_INTEGRITY_ERROR,
    FAILED
  }

  private final Code code;

  public ExecException(Code code, String message) {
    super(message);
    this.code = code;
  }

  public ExecException(Code code, String message, Throwable cause) {
    super(message, cause);
    this.code = code;
  }

  public Code code() {
    return code;
  }
}
