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
 * Inference is label-blind; gold reject/clarify labels are applied only via {@link ParseScore}.
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

  /** Deep DIN: logical SQL → DraftIR (Spider stages). */
  public static ProcessParseArm dinSqlSpider(Path root) {
    Path script = root.resolve("experiments/adapters/din/din_sql_bridge.py");
    List<String> cmd = new ArrayList<String>();
    cmd.add(pythonBin());
    cmd.add(script.toString());
    cmd.add("--mode");
    cmd.add("spider");
    return new ProcessParseArm("din-sql-spider", cmd);
  }

  /** Deep DIN: logical SQL → DraftIR (BIRD templates). */
  public static ProcessParseArm dinSqlBird(Path root) {
    Path script = root.resolve("experiments/adapters/din/din_sql_bridge.py");
    Path knowledge = root.resolve("experiments/adapters/din/bird_knowledge.txt");
    List<String> cmd = new ArrayList<String>();
    cmd.add(pythonBin());
    cmd.add(script.toString());
    cmd.add("--mode");
    cmd.add("bird");
    cmd.add("--knowledge");
    cmd.add(knowledge.toString());
    return new ProcessParseArm("din-sql-bird", cmd);
  }

  /** Deep SAG: logical MQL → DraftIR with WorldAccess feedback. */
  public static ProcessParseArm sagMql(Path root) {
    Path script = root.resolve("experiments/adapters/sag/sag_mql_bridge.py");
    List<String> cmd = new ArrayList<String>();
    cmd.add(pythonBin());
    cmd.add(script.toString());
    cmd.add("--feedback");
    cmd.add("on");
    return new ProcessParseArm("sag-mql", cmd);
  }

  /** SAG MQL without execution feedback (ablation). */
  public static ProcessParseArm sagMqlNoFeedback(Path root) {
    Path script = root.resolve("experiments/adapters/sag/sag_mql_bridge.py");
    List<String> cmd = new ArrayList<String>();
    cmd.add(pythonBin());
    cmd.add(script.toString());
    cmd.add("--feedback");
    cmd.add("off");
    return new ProcessParseArm("sag-mql-nofeedback", cmd);
  }

  /** Same-model direct DraftIR baseline. */
  public static ProcessParseArm directDraftIr(Path root) {
    Path script = root.resolve("experiments/adapters/direct/direct_draft_bridge.py");
    List<String> cmd = new ArrayList<String>();
    cmd.add(pythonBin());
    cmd.add(script.toString());
    return new ProcessParseArm("direct-draftir", cmd);
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
    if (item == null || item.utterance == null || item.utterance.trim().isEmpty()) {
      out.error = "empty utterance";
      out.ok_ex = Boolean.FALSE;
      out.ok_ir_valid = Boolean.FALSE;
      ParseScore.attachGoldLabels(out, item);
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
      out.extras.put("infrastructure_failure", Boolean.TRUE);
      ParseScore.scoreAgainstGold(out, item);
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
      ParseScore.scoreAgainstGold(out, item);
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
      if (u.has("failed_attempts")) {
        out.extras.put("llm_failed_attempts", Long.valueOf(u.get("failed_attempts").asLong()));
      }
      if (u.has("http_status")) {
        out.extras.put("http_status", Integer.valueOf(u.get("http_status").asInt()));
      }
      if (u.has("infrastructure_failure") && u.get("infrastructure_failure").asBoolean()) {
        out.extras.put("infrastructure_failure", Boolean.TRUE);
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
    out.reject_actual = Boolean.valueOf(unsupported);
    out.clarify_actual = Boolean.valueOf(needClarify);

    long bindStart = System.currentTimeMillis();

    if ("INFRASTRUCTURE_FAILURE".equals(status)) {
      out.extras.put("infrastructure_failure", Boolean.TRUE);
      stampParseTimes(out, t0, procMs, modelMs, bindStart);
      out.error = root.path("error").asText("INFRASTRUCTURE_FAILURE");
      ParseScore.scoreAgainstGold(out, item);
      return out;
    }
    if ("FAILED".equals(status)) {
      // May be infra (legacy) or adapter crash — mark infra when HTTP status present.
      if (out.extras.containsKey("http_status")
          || Boolean.TRUE.equals(out.extras.get("infrastructure_failure"))) {
        out.extras.put("infrastructure_failure", Boolean.TRUE);
      }
      stampParseTimes(out, t0, procMs, modelMs, bindStart);
      out.error = root.path("error").asText("FAILED");
      ParseScore.scoreAgainstGold(out, item);
      return out;
    }

    if (unsupported || needClarify) {
      stampParseTimes(out, t0, procMs, modelMs, bindStart);
      out.error = root.path("error").asText(status);
      ParseScore.scoreAgainstGold(out, item);
      return out;
    }

    if (!"OK".equals(status) || !root.has("draft")) {
      stampParseTimes(out, t0, procMs, modelMs, bindStart);
      out.error = root.path("error").asText("status=" + status);
      ParseScore.scoreAgainstGold(out, item);
      return out;
    }

    String draftJson = root.get("draft").toString();
    out.draft_ir_json = draftJson;
    AppConfig cfg = AppConfig.load(ctx.root);
    IrSchemaValidator validator = new IrSchemaValidator(ctx.root.resolve("schemas"));
    try {
      if (!validator.validateDraftIr(draftJson).isEmpty()) {
        stampParseTimes(out, t0, procMs, modelMs, bindStart);
        out.error = "DraftIR schema invalid: " + validator.validateDraftIr(draftJson);
        ParseScore.scoreAgainstGold(out, item);
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
      out.clarify_actual = Boolean.TRUE;
      stampParseTimes(out, t0, procMs, modelMs, bindStart);
      out.error = br.error != null ? br.error : "NEED_CLARIFICATION";
      ParseScore.scoreAgainstGold(out, item);
      return out;
    }
    if (IrBinder.STATUS_UNSUPPORTED_QUERY.equals(br.status)) {
      out.reject_actual = Boolean.TRUE;
      stampParseTimes(out, t0, procMs, modelMs, bindStart);
      out.error = br.error;
      ParseScore.scoreAgainstGold(out, item);
      return out;
    }
    if (!IrBinder.STATUS_OK.equals(br.status) || br.bound == null) {
      stampParseTimes(out, t0, procMs, modelMs, bindStart);
      out.error = br.error != null ? br.error : br.status;
      ParseScore.scoreAgainstGold(out, item);
      return out;
    }
    out.ok_ir_valid = Boolean.TRUE;
    out.bound = br.bound;
    stampParseTimes(out, t0, procMs, modelMs, bindStart);

    QueryEngine engine = ctx.newEngine(PlannerMode.RULE);
    Path art = ctx.artifactDir(id + "-ex", item.query_id);
    QueryEngine.RunResult rr = engine.run(br.bound, art, false, null);
    out.queryResult = rr == null ? null : rr.result;
    String mismatch = KartParseArm.compareGold(item, out.queryResult);
    out.ok_ex = Boolean.valueOf(mismatch == null);
    out.ok = Boolean.TRUE.equals(out.ok_ex);
    if (mismatch != null) {
      out.error = mismatch;
    }
    // Gold reject/clarify expected on a successful bind+EX path still scores via gold.
    if (item.reject_expected || item.clarify_expected) {
      ParseScore.scoreAgainstGold(out, item);
    } else {
      ParseScore.attachGoldLabels(out, item);
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
