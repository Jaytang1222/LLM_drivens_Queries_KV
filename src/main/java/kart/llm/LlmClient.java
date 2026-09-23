package kart.llm;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * LLM chat gateway (T3.1).
 */
public interface LlmClient {

  LlmResponse chat(List<LlmMessage> messages, JsonNode responseSchemaHint, LlmOptions opts)
      throws LlmException;
}
