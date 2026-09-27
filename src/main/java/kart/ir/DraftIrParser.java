package kart.ir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.ValidationMessage;
import kart.llm.LlmClient;
import kart.llm.LlmException;
import kart.llm.LlmMessage;
import kart.llm.LlmOptions;
import kart.llm.LlmResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extract DraftIR JSON, schema-validate, retry ≤2 times on failure (T3.4).
 */
public final class DraftIrParser {

  public static final String STATUS_OK = "OK";
  public static final String STATUS_INVALID_IR = "INVALID_IR";
  public static final String STATUS_UNSUPPORTED_QUERY = "UNSUPPORTED_QUERY";

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final Pattern JSON_BLOCK = Pattern.compile("\\{[\\s\\S]*}");

  private final IrSchemaValidator validator;
  private final LlmClient llm;
  private final LlmOptions options;
  private kart.llm.LlmUsageAccumulator usage;

  public DraftIrParser(IrSchemaValidator validator, LlmClient llm) {
    this(validator, llm, LlmOptions.defaults());
  }

  public DraftIrParser(IrSchemaValidator validator, LlmClient llm, LlmOptions options) {
    this.validator = validator;
    this.llm = llm;
    this.options = options == null ? LlmOptions.defaults() : options;
  }

  public void setUsageAccumulator(kart.llm.LlmUsageAccumulator usage) {
    this.usage = usage;
  }

  public static final class ParseResult {
    public String status = STATUS_OK;
    public String error;
    public DraftIr draft;
    public String rawJson;
    public int attempts;
  }

  public ParseResult parseWithRepair(List<LlmMessage> baseMessages) {
    ParseResult out = new ParseResult();
    List<LlmMessage> msgs = new ArrayList<LlmMessage>(baseMessages);
    String lastError = null;
    for (int attempt = 0; attempt < 3; attempt++) {
      out.attempts = attempt + 1;
      try {
        LlmResponse resp = llm.chat(msgs, null, options);
        if (usage != null) {
          usage.record(resp);
        }
        String json = extractJson(resp.content);
        out.rawJson = json;
        if (json == null) {
          lastError = "no JSON object in model output";
          msgs.add(LlmMessage.assistant(resp.content == null ? "" : resp.content));
          msgs.add(LlmMessage.user("Previous output was not valid JSON. Reply with ONLY a DraftIR JSON object. Error: "
              + lastError));
          continue;
        }
        if (looksLikeUnsupportedCount(json)) {
          out.status = STATUS_UNSUPPORTED_QUERY;
          out.error = "COUNT / aggregation queries are not supported";
          return out;
        }
        Set<ValidationMessage> errs = validator.validateDraftIr(json);
        if (!errs.isEmpty()) {
          lastError = errs.toString();
          msgs.add(LlmMessage.assistant(json));
          msgs.add(LlmMessage.user("DraftIR failed schema validation. Fix and reply with ONLY JSON. Errors: "
              + lastError));
          continue;
        }
        out.draft = DraftIr.fromJson(json);
        out.status = STATUS_OK;
        out.error = null;
        return out;
      } catch (LlmException e) {
        if (usage != null) {
          usage.recordHttpFailure(e.httpStatus, 0L);
        }
        out.status = STATUS_INVALID_IR;
        out.error = "LLM unavailable: " + e.getMessage();
        return out;
      } catch (Exception e) {
        lastError = e.getMessage();
        msgs.add(LlmMessage.user("Parse error: " + lastError + ". Reply with ONLY valid DraftIR JSON."));
      }
    }
    out.status = STATUS_INVALID_IR;
    out.error = lastError == null ? "repair exhausted" : lastError;
    out.draft = null;
    return out;
  }

  /** One-shot parse of raw model text (no LLM). */
  public ParseResult parseOnce(String content) {
    ParseResult out = new ParseResult();
    out.attempts = 1;
    try {
      String json = extractJson(content);
      out.rawJson = json;
      if (json == null) {
        out.status = STATUS_INVALID_IR;
        out.error = "no JSON object";
        return out;
      }
      if (looksLikeUnsupportedCount(json)) {
        out.status = STATUS_UNSUPPORTED_QUERY;
        out.error = "COUNT / aggregation queries are not supported";
        return out;
      }
      Set<ValidationMessage> errs = validator.validateDraftIr(json);
      if (!errs.isEmpty()) {
        out.status = STATUS_INVALID_IR;
        out.error = errs.toString();
        return out;
      }
      out.draft = DraftIr.fromJson(json);
      out.status = STATUS_OK;
      return out;
    } catch (Exception e) {
      out.status = STATUS_INVALID_IR;
      out.error = e.getMessage();
      return out;
    }
  }

  public static String extractJson(String content) {
    if (content == null) {
      return null;
    }
    String trimmed = content.trim();
    if (trimmed.startsWith("{")) {
      return trimmed;
    }
    Matcher m = JSON_BLOCK.matcher(trimmed);
    if (m.find()) {
      return m.group();
    }
    return null;
  }

  private static boolean looksLikeUnsupportedCount(String json) {
    try {
      JsonNode n = MAPPER.readTree(json);
      if (n.has("result") && n.get("result").has("mode")) {
        String mode = n.get("result").get("mode").asText("");
        if ("COUNT".equalsIgnoreCase(mode) || "AGGREGATE".equalsIgnoreCase(mode)) {
          return true;
        }
      }
      if (n.has("missing") && n.get("missing").isArray()) {
        for (JsonNode m : n.get("missing")) {
          String s = m.asText("");
          if (s.toLowerCase().contains("unsupported_count") || "count".equalsIgnoreCase(s)) {
            return true;
          }
        }
      }
    } catch (Exception ignored) {
      // fall through
    }
    return false;
  }
}
