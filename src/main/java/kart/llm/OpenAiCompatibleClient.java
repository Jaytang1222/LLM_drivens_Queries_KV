package kart.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * OpenAI-compatible chat/completions client via HttpURLConnection (T3.1).
 * Secrets only from env: LLM_API_KEY, LLM_BASE_URL, LLM_MODEL.
 */
public final class OpenAiCompatibleClient implements LlmClient {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final String baseUrl;
  private final String apiKey;
  private final String defaultModel;
  private final boolean jsonModeSupported;

  public OpenAiCompatibleClient() {
    this(
        env("LLM_BASE_URL", "https://api.openai.com/v1"),
        env("LLM_API_KEY", ""),
        env("LLM_MODEL", "gpt-4o-mini"),
        !"false".equalsIgnoreCase(env("LLM_JSON_MODE", "true")));
  }

  public OpenAiCompatibleClient(String baseUrl, String apiKey, String defaultModel,
                                boolean jsonModeSupported) {
    this.baseUrl = trimSlash(baseUrl);
    this.apiKey = apiKey == null ? "" : apiKey;
    this.defaultModel = defaultModel;
    this.jsonModeSupported = jsonModeSupported;
  }

  @Override
  public LlmResponse chat(List<LlmMessage> messages, JsonNode responseSchemaHint, LlmOptions opts)
      throws LlmException {
    if (apiKey.isEmpty()) {
      throw new LlmException("LLM_API_KEY is not set; use --mock or set env");
    }
    LlmOptions o = opts == null ? LlmOptions.defaults() : opts;
    String model = o.model != null ? o.model : defaultModel;
    try {
      ObjectNode body = MAPPER.createObjectNode();
      body.put("model", model);
      body.put("temperature", o.temperature);
      ArrayNode msgs = body.putArray("messages");
      for (LlmMessage m : messages) {
        ObjectNode msg = msgs.addObject();
        msg.put("role", m.role);
        msg.put("content", m.content);
      }
      if (o.jsonObjectFormat && jsonModeSupported) {
        ObjectNode rf = body.putObject("response_format");
        rf.put("type", "json_object");
      }

      byte[] payload = MAPPER.writeValueAsBytes(body);
      URL url = new URL(baseUrl + "/chat/completions");
      HttpURLConnection conn = (HttpURLConnection) url.openConnection();
      conn.setRequestMethod("POST");
      conn.setConnectTimeout(o.timeoutMs);
      conn.setReadTimeout(o.timeoutMs);
      conn.setDoOutput(true);
      conn.setRequestProperty("Content-Type", "application/json");
      conn.setRequestProperty("Authorization", "Bearer " + apiKey);

      long t0 = System.currentTimeMillis();
      OutputStream os = conn.getOutputStream();
      try {
        os.write(payload);
      } finally {
        os.close();
      }

      int code = conn.getResponseCode();
      InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
      String raw = readAll(in);
      long latency = System.currentTimeMillis() - t0;

      if (code >= 400) {
        throw new LlmException("HTTP " + code + ": " + raw);
      }

      JsonNode root = MAPPER.readTree(raw);
      LlmResponse resp = new LlmResponse();
      resp.rawBody = raw;
      resp.latencyMs = latency;
      resp.model = text(root, "model", model);
      JsonNode usage = root.get("usage");
      if (usage != null) {
        if (usage.has("prompt_tokens")) {
          resp.promptTokens = usage.get("prompt_tokens").asInt();
        }
        if (usage.has("completion_tokens")) {
          resp.completionTokens = usage.get("completion_tokens").asInt();
        }
      }
      JsonNode choices = root.get("choices");
      if (choices == null || !choices.isArray() || choices.size() == 0) {
        throw new LlmException("no choices in response");
      }
      JsonNode message = choices.get(0).get("message");
      resp.content = message == null ? null : text(message, "content", null);
      if (resp.content == null) {
        throw new LlmException("empty assistant content");
      }
      return resp;
    } catch (LlmException e) {
      throw e;
    } catch (Exception e) {
      throw new LlmException("LLM call failed: " + e.getMessage(), e);
    }
  }

  /** OI-1: probe whether response_format=json_object is accepted. */
  public boolean probeJsonMode() {
    return jsonModeSupported;
  }

  private static String env(String key, String def) {
    String v = System.getenv(key);
    return v == null || v.trim().isEmpty() ? def : v.trim();
  }

  private static String trimSlash(String u) {
    if (u == null) {
      return "";
    }
    while (u.endsWith("/")) {
      u = u.substring(0, u.length() - 1);
    }
    return u;
  }

  private static String text(JsonNode n, String field, String def) {
    JsonNode v = n.get(field);
    return v == null || v.isNull() ? def : v.asText();
  }

  private static String readAll(InputStream in) throws Exception {
    if (in == null) {
      return "";
    }
    ByteArrayOutputStream buf = new ByteArrayOutputStream();
    byte[] tmp = new byte[4096];
    int n;
    while ((n = in.read(tmp)) >= 0) {
      buf.write(tmp, 0, n);
    }
    return new String(buf.toByteArray(), StandardCharsets.UTF_8);
  }
}
