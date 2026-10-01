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
      throw new LlmException("LLM_API_KEY is not set; configure .env or environment");
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
      if (o.responseSchemaFormat && responseSchemaHint != null) {
        ObjectNode rf = body.putObject("response_format");
        rf.put("type", "json_schema");
        ObjectNode schema = rf.putObject("json_schema");
        schema.put("name", "plan_choice");
        schema.put("strict", true);
        schema.set("schema", responseSchemaHint);
      } else if (o.jsonObjectFormat && jsonModeSupported) {
        ObjectNode rf = body.putObject("response_format");
        rf.put("type", "json_object");
      }
      if (o.maxTokens != null && o.maxTokens.intValue() > 0) {
        body.put("max_tokens", o.maxTokens.intValue());
      }

      byte[] payload = MAPPER.writeValueAsBytes(body);
      Posted posted = post(baseUrl + "/chat/completions", payload, o.timeoutMs, apiKey);
      int attempts = 1;
      boolean jsonFallback = false;
      // JSON-mode retry only on HTTP 400 (format rejection) — never on 401/402/403/429/5xx.
      // Disabled when {@link LlmOptions#allowJsonModeRetry} is false (one-shot arms).
      if (o.allowJsonModeRetry
          && posted.code == 400 && o.jsonObjectFormat && jsonModeSupported
          && body.has("response_format")) {
        jsonFallback = true;
        attempts = 2;
        body.remove("response_format");
        payload = MAPPER.writeValueAsBytes(body);
        Posted retry = post(baseUrl + "/chat/completions", payload, o.timeoutMs, apiKey);
        retry.latencyMs += posted.latencyMs;
        posted = retry;
      }
      if (posted.code >= 400) {
        throw new LlmException("HTTP " + posted.code + ": " + posted.raw,
            Integer.valueOf(posted.code));
      }

      JsonNode root = MAPPER.readTree(posted.raw);
      LlmResponse resp = new LlmResponse();
      resp.rawBody = posted.raw;
      resp.latencyMs = posted.latencyMs;
      resp.attempts = attempts;
      resp.jsonModeFallback = jsonFallback;
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

  private static Posted post(String urlStr, byte[] payload, int timeoutMs, String apiKey)
      throws Exception {
    URL url = new URL(urlStr);
    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
    conn.setRequestMethod("POST");
    conn.setConnectTimeout(timeoutMs);
    conn.setReadTimeout(timeoutMs);
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
    Posted p = new Posted();
    p.code = code;
    p.raw = raw;
    p.latencyMs = Math.max(0L, System.currentTimeMillis() - t0);
    return p;
  }

  private static final class Posted {
    int code;
    String raw;
    long latencyMs;
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
