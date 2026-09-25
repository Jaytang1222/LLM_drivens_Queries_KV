package kart.bench;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kart.config.AppConfig;
import kart.ir.DraftIr;
import kart.ir.IrBinder;
import kart.ir.IrSchemaValidator;
import kart.query.QueryEngine;
import kart.search.PlannerMode;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * ParseArm that shells out to a Python adapter bridge (DIN / SAG).
 * Bridge stdout: JSON {@code {status, draft?, error?, usage?}}.
 */
public final class ProcessParseArm implements ParseArm {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final String id;
  private final List<String> commandPrefix;

  public ProcessParseArm(String id, List<String> commandPrefix) {
    this.id = id;
    this.commandPrefix = new ArrayList<String>(commandPrefix);
  }

  public static ProcessParseArm dinSpider(Path root) {
    Path script = root.resolve("experiments/adapters/din/din_bridge.py");
    List<String> cmd = new ArrayList<String>();
    cmd.add(pythonBin());
    cmd.add(script.toString());
    cmd.add("--mode");
    cmd.add("spider");
    return new ProcessParseArm("din-spider", cmd);
  }

  public static ProcessParseArm dinBird(Path root) {
    Path script = root.resolve("experiments/adapters/din/din_bridge.py");
    Path knowledge = root.resolve("experiments/adapters/din/bird_knowledge.txt");
    List<String> cmd = new ArrayList<String>();
    cmd.add(pythonBin());
    cmd.add(script.toString());
    cmd.add("--mode");
    cmd.add("bird");
    cmd.add("--knowledge");
    cmd.add(knowledge.toString());
    return new ProcessParseArm("din-bird", cmd);
  }

  public static ProcessParseArm sag(Path root) {
    Path script = root.resolve("experiments/adapters/sag/sag_bridge.py");
    List<String> cmd = new ArrayList<String>();
    cmd.add(pythonBin());
    cmd.add(script.toString());
    return new ProcessParseArm("sag", cmd);
  }

  private static String pythonBin() {
    String env = System.getenv("KART_PYTHON");
    return env != null && !env.isEmpty() ? env : "python3";
  }

  @Override
  public String id() {
    return id;
  }

  @Override
  public ParseTrialResult parse(NlItem item, BenchContext ctx) throws Exception {
    ParseTrialResult out = new ParseTrialResult();
    out.reject_expected = item != null && item.reject_expected;
    if (item == null || item.utterance == null || item.utterance.trim().isEmpty()) {
      out.error = "empty utterance";
      out.ok_ex = Boolean.FALSE;
      out.ok_ir_valid = Boolean.FALSE;
      return out;
    }
    String early = ParseFairness.earlyReject(item.utterance.trim());
    if (early != null) {
      return ParseFairness.rejectedTrial(item, early);
    }

    List<String> cmd = new ArrayList<String>(commandPrefix);
    cmd.add("--utterance");
    cmd.add(item.utterance.trim());

    long t0 = System.currentTimeMillis();
    ProcessBuilder pb = new ProcessBuilder(cmd);
    pb.directory(ctx.root.toFile());
    pb.redirectErrorStream(true);
    Map<String, String> env = pb.environment();
    copyEnv(env, "LLM_API_KEY");
    copyEnv(env, "LLM_BASE_URL");
    copyEnv(env, "LLM_MODEL");
    copyEnv(env, "OPENAI_API_KEY");
    copyEnv(env, "KART_LLM_TEMPERATURE");

    Process proc = pb.start();
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    InputStream in = proc.getInputStream();
    byte[] buf = new byte[4096];
    int n;
    while ((n = in.read(buf)) >= 0) {
      bos.write(buf, 0, n);
    }
    boolean finished = proc.waitFor(180, TimeUnit.SECONDS);
    long procMs = Math.max(0L, System.currentTimeMillis() - t0);
    if (!finished) {
      proc.destroyForcibly();
      out.t_parse_ms = Long.valueOf(procMs);
      out.t_parse_total_ms = Long.valueOf(procMs);
      out.t_parse_model_ms = Long.valueOf(0L);
      out.t_adapter_startup_ms = Long.valueOf(procMs);
      out.error = "bridge timeout";
      out.ok_ex = Boolean.FALSE;
      out.ok_ir_valid = Boolean.FALSE;
      return out;
    }
    String stdout = new String(bos.toByteArray(), StandardCharsets.UTF_8).trim();
    String jsonLine = lastJsonObject(stdout);
    JsonNode root;
    try {
      root = MAPPER.readTree(jsonLine);
    } catch (Exception e) {
      out.t_parse_ms = Long.valueOf(procMs);
      out.t_parse_total_ms = Long.valueOf(procMs);
      out.error = "bridge bad JSON: " + e.getMessage() + " raw=" + truncate(stdout, 400);
      out.ok_ex = Boolean.FALSE;
      out.ok_ir_valid = Boolean.FALSE;
      return out;
    }
    long modelMs = 0L;
    if (root.has("usage")) {
      JsonNode u = root.get("usage");
      if (u.has("calls")) {
        out.llm_calls = Long.valueOf(u.get("calls").asLong());
      }
      if (u.has("prompt_tokens")) {
        out.tokens_in = Long.valueOf(u.get("prompt_tokens").asLong());
      }
      if (u.has("completion_tokens")) {
        out.tokens_out = Long.valueOf(u.get("completion_tokens").asLong());
      }
      if (u.has("t_model_ms")) {
        modelMs = u.get("t_model_ms").asLong();
      }
      if (u.has("model")) {
        out.extras.put("llm_model", u.get("model").asText());
      }
      if (u.has("temperature")) {
        out.extras.put("llm_temperature", Double.valueOf(u.get("temperature").asDouble()));
      }
      if (u.has("json_mode_fallbacks")) {
        out.extras.put("json_mode_fallbacks", Long.valueOf(u.get("json_mode_fallbacks").asLong()));
      }
    }
    if (root.has("timing") && root.get("timing").has("t_model_ms")) {
      modelMs = root.get("timing").get("t_model_ms").asLong();
    }
    if (root.has("provenance")) {
      out.extras.put("provenance", root.get("provenance").toString());
    }

    String status = root.path("status").asText("");
    boolean unsupported = "UNSUPPORTED_QUERY".equals(status);
    boolean needClarify = "NEED_CLARIFICATION".equals(status);
    out.reject_actual = unsupported;
    out.clarify_expected = item.clarify_expected;
    out.clarify_actual = needClarify;

    long bindStart = System.currentTimeMillis();
    // Bind (to BoundIR) is part of t_parse_total; EX execution is not.
    if (item.clarify_expected) {
      // Binder may still be needed if the model emitted a draft.
      if (!needClarify && root.has("draft")) {
        try {
          DraftIr draft = DraftIr.fromJson(root.get("draft").toString());
          AppConfig cfg = AppConfig.load(ctx.root);
          IrBinder binder = new IrBinder(
              cfg.regions(), ctx.kv, ctx.layout.tableMeta, ctx.layout.shardCount,
              ctx.manifest.manifest_id, "point_similarity_v2");
          IrBinder.BindResult br = binder.bind(draft, System.currentTimeMillis());
          if (IrBinder.STATUS_NEED_CLARIFICATION.equals(br.status)) {
            needClarify = true;
            out.clarify_actual = true;
          }
        } catch (Exception ignore) {
          //
        }
      }
      stampParseTimes(out, t0, procMs, modelMs, bindStart);
      out.ok_ir_valid = needClarify;
      out.ok_ex = needClarify;
      out.ok = needClarify;
      if (!needClarify) {
        out.error = "expected NEED_CLARIFICATION but status=" + status;
      }
      return out;
    }

    if (item.reject_expected) {
      stampParseTimes(out, t0, procMs, modelMs, bindStart);
      out.ok_ir_valid = unsupported;
      out.ok_ex = unsupported;
      out.ok = unsupported;
      if (!unsupported) {
        out.error = "expected reject but status=" + status;
      }
      return out;
    }

    if (unsupported) {
      stampParseTimes(out, t0, procMs, modelMs, bindStart);
      out.ok_ir_valid = false;
      out.ok_ex = false;
      out.ok = false;
      out.error = root.path("error").asText("unsupported");
      return out;
    }
    if (needClarify) {
      stampParseTimes(out, t0, procMs, modelMs, bindStart);
      out.ok_ir_valid = false;
      out.ok_ex = false;
      out.ok = false;
      out.error = root.path("error").asText("NEED_CLARIFICATION");
      return out;
    }
    if (!"OK".equals(status) || !root.has("draft")) {
      stampParseTimes(out, t0, procMs, modelMs, bindStart);
      out.ok_ir_valid = false;
      out.ok_ex = false;
      out.ok = false;
      out.error = root.path("error").asText("status=" + status);
      return out;
    }

    String draftJson = root.get("draft").toString();
    out.draft_ir_json = draftJson;
    AppConfig cfg = AppConfig.load(ctx.root);
    IrSchemaValidator validator = new IrSchemaValidator(ctx.root.resolve("schemas"));
    try {
      if (!validator.validateDraftIr(draftJson).isEmpty()) {
        stampParseTimes(out, t0, procMs, modelMs, bindStart);
        out.ok_ir_valid = false;
        out.ok_ex = false;
        out.error = "DraftIR schema invalid: " + validator.validateDraftIr(draftJson);
        return out;
      }
    } catch (Exception e) {
      // continue to bind attempt
    }

    DraftIr draft = DraftIr.fromJson(draftJson);
    IrBinder binder = new IrBinder(
        cfg.regions(), ctx.kv, ctx.layout.tableMeta, ctx.layout.shardCount,
        ctx.manifest.manifest_id, "point_similarity_v2");
    IrBinder.BindResult br = binder.bind(draft, System.currentTimeMillis());
    if (IrBinder.STATUS_NEED_CLARIFICATION.equals(br.status)) {
      out.clarify_actual = true;
      stampParseTimes(out, t0, procMs, modelMs, bindStart);
      out.ok_ir_valid = false;
      out.ok_ex = false;
      out.error = br.error != null ? br.error : "NEED_CLARIFICATION";
      return out;
    }
    if (IrBinder.STATUS_UNSUPPORTED_QUERY.equals(br.status)) {
      out.reject_actual = true;
      stampParseTimes(out, t0, procMs, modelMs, bindStart);
      out.ok_ir_valid = false;
      out.ok_ex = Boolean.valueOf(item.reject_expected);
      out.error = br.error;
      return out;
    }
    if (!IrBinder.STATUS_OK.equals(br.status) || br.bound == null) {
      stampParseTimes(out, t0, procMs, modelMs, bindStart);
      out.ok_ir_valid = false;
      out.ok_ex = false;
      out.error = br.error != null ? br.error : br.status;
      return out;
    }
    out.ok_ir_valid = true;
    out.bound = br.bound;
    stampParseTimes(out, t0, procMs, modelMs, bindStart);

    QueryEngine engine = ctx.newEngine(PlannerMode.RULE);
    Path art = ctx.artifactDir(id + "-ex", item.query_id);
    QueryEngine.RunResult rr = engine.run(br.bound, art, false, null);
    out.queryResult = rr == null ? null : rr.result;
    String mismatch = KartParseArm.compareGold(item, out.queryResult);
    out.ok_ex = mismatch == null;
    out.ok = out.ok_ex;
    if (mismatch != null) {
      out.error = mismatch;
    }
    return out;
  }

  private static void stampParseTimes(ParseTrialResult out, long t0, long procMs, long modelMs,
                                      long bindStart) {
    long bindMs = Math.max(0L, System.currentTimeMillis() - bindStart);
    long total = Math.max(0L, System.currentTimeMillis() - t0);
    if (modelMs < 0L) {
      modelMs = 0L;
    }
    if (modelMs > procMs) {
      modelMs = procMs;
    }
    out.t_parse_model_ms = Long.valueOf(modelMs);
    out.t_adapter_startup_ms = Long.valueOf(Math.max(0L, procMs - modelMs));
    out.t_parse_total_ms = Long.valueOf(total);
    out.t_parse_ms = Long.valueOf(total);
    out.extras.put("t_bind_ms", Long.valueOf(bindMs));
  }

  private static void copyEnv(Map<String, String> env, String key) {
    String v = System.getenv(key);
    if (v != null) {
      env.put(key, v);
    }
  }

  private static String lastJsonObject(String s) {
    int idx = s.indexOf('{');
    if (idx < 0) {
      return s;
    }
    return s.substring(idx);
  }

  private static String truncate(String s, int n) {
    if (s == null) {
      return "";
    }
    return s.length() <= n ? s : s.substring(0, n);
  }
}
