package kart.llm;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Ordered-response LLM stub for scripted dialog tests.
 */
public final class ScriptedLlmClient implements LlmClient {

  private final List<String> responses;
  private int index;

  public ScriptedLlmClient(String... responses) {
    this.responses = new ArrayList<String>(Arrays.asList(responses));
  }

  public ScriptedLlmClient(List<String> responses) {
    this.responses = new ArrayList<String>(responses);
  }

  @Override
  public LlmResponse chat(List<LlmMessage> messages, JsonNode responseSchemaHint, LlmOptions opts)
      throws LlmException {
    if (index >= responses.size()) {
      throw new LlmException("ScriptedLlmClient exhausted after " + responses.size()
          + " responses; next messages hash=" + MockLlmClient.messagesHash(messages));
    }
    String content = responses.get(index++);
    LlmResponse r = new LlmResponse(content);
    r.model = "scripted";
    r.latencyMs = 0;
    return r;
  }

  public int calls() {
    return index;
  }
}
