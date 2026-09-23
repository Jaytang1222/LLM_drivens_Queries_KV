package kart.llm;

/**
 * Chat message role + content.
 */
public final class LlmMessage {

  public final String role;
  public final String content;

  public LlmMessage(String role, String content) {
    this.role = role;
    this.content = content;
  }

  public static LlmMessage system(String content) {
    return new LlmMessage("system", content);
  }

  public static LlmMessage user(String content) {
    return new LlmMessage("user", content);
  }

  public static LlmMessage assistant(String content) {
    return new LlmMessage("assistant", content);
  }
}
