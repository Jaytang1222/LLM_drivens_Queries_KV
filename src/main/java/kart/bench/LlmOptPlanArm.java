package kart.bench;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kart.ir.BoundIr;
import kart.query.QueryEngine;
import kart.search.PlannerMode;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * LLMOpt G→S bridge: Python proposes/selects plan_id; Java validates via forcePlanId.
 *
 * {@code t_plan} starts before temp BoundIR preparation and includes Python G→S
 * plus Java generate→select (forcePlanId). Coordinator execute and artifact IO
 * stay outside {@code t_plan}. Java search time is inside {@code t_plan}.
 */
public final class LlmOptPlanArm implements Arm {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private final Path root;

  public LlmOptPlanArm(Path root) {
    this.root = root;
  }

  @Override
  public String id() {
    return "llmopt";
  }

  @Override
  public TrialResult run(BoundIr ir, BenchContext ctx) throws Exception {
    // System-level plan clock includes adapter input preparation (temp BoundIR).
    long t0 = System.currentTimeMillis();
    Path tmp = Files.createTempFile(ctx.runDir, "llmopt-ir-", ".json");
    try {
      Files.write(tmp, MAPPER.writeValueAsBytes(ir));
      long prepEnd = System.currentTimeMillis();
      Path script = root.resolve("experiments/adapters/llmopt/llmopt_bridge.py");
      List<String> cmd = new ArrayList<String>();
      cmd.add(pythonBin());
      cmd.add(script.toString());
      cmd.add("--bound-ir");
      cmd.add(tmp.toString());

      ProcessBuilder pb = new ProcessBuilder(cmd);
      pb.directory(root.toFile());
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
      boolean ok = proc.waitFor(180, TimeUnit.SECONDS);
      if (!ok) {
        proc.destroyForcibly();
        return stampFail(t0, "llmopt bridge timeout");
      }
      String stdout = new String(bos.toByteArray(), StandardCharsets.UTF_8).trim();
      int brace = stdout.indexOf('{');
      JsonNode rootNode = MAPPER.readTree(brace >= 0 ? stdout.substring(brace) : stdout);
      if (!"OK".equals(rootNode.path("status").asText())) {
        return stampFail(t0, rootNode.path("error").asText("llmopt failed"));
      }
      String planId = rootNode.path("plan_id").asText(null);
      if (planId == null || planId.isEmpty()) {
        return stampFail(t0, "llmopt: empty plan_id");
      }
      long tBridge = Math.max(0L, System.currentTimeMillis() - t0);

      QueryEngine engine = ctx.newEngine(PlannerMode.RULE);
      QueryEngine.RunResult planned = engine.run(ir, null, true, planId);
      long planEnd = planned.planEndEpochMs > t0
          ? planned.planEndEpochMs : System.currentTimeMillis();
      long tPlan = Math.max(tBridge, planEnd - t0);
      planned.t_plan_ms = Long.valueOf(tPlan);
      planned.planStartEpochMs = t0;
      planned.planEndEpochMs = t0 + tPlan;

      QueryEngine.RunResult rr = planned;
      long wall = tPlan;
      if (!ctx.planOnly) {
        Path art = ctx.artifactDir(id(), ir.query_id);
        rr = engine.executeSelected(ir, art, planned);
        wall = Math.max(0L, System.currentTimeMillis() - t0);
      }
      TrialResult tr = TrialResult.fromRun(rr, wall);
      tr.t_plan_ms = Long.valueOf(tPlan);
      tr.extras.put("plan_start_ms", Long.valueOf(t0));
      tr.extras.put("plan_end_ms", Long.valueOf(t0 + tPlan));
      tr.extras.put("t_bridge_ms", Long.valueOf(tBridge));
      tr.extras.put("t_adapter_prepare_ms", Long.valueOf(Math.max(0L, prepEnd - t0)));
      tr.extras.put("t_plan_boundary", "includes_input_prepare_and_java_search");
      if (tr.t_exec_ms != null) {
        tr.t_e2e_ms = Long.valueOf(tPlan + tr.t_exec_ms.longValue());
      } else {
        tr.t_e2e_ms = Long.valueOf(tPlan);
      }
      tr.extras.put("llmopt_protocol", "G_then_S");
      tr.extras.put("llmopt_candidates", rootNode.path("candidates").toString());
      tr.extras.put("llmopt_chosen", planId);
      if (rootNode.has("provenance")) {
        tr.extras.put("provenance", rootNode.get("provenance").toString());
      } else {
        tr.extras.put("llmopt_upstream", "experiments/third_party/llmopt/README.md");
      }
      tr.extras.put("llmopt_intentional_changes",
          "KART plan_id family; Java validator/executor instead of pg_hint_plan; fair LLM not author checkpoint");
      tr.extras.put("llmopt_java_path",
          "beam_search_then_forcePlanId; search time is inside t_plan because that is how the SafePlan is validated");
      if (rootNode.has("usage")) {
        JsonNode u = rootNode.get("usage");
        if (u.has("calls")) {
          tr.extras.put("llm_calls", Long.valueOf(u.get("calls").asLong()));
        }
        if (u.has("prompt_tokens")) {
          tr.extras.put("tokens_in", Long.valueOf(u.get("prompt_tokens").asLong()));
        }
        if (u.has("completion_tokens")) {
          tr.extras.put("tokens_out", Long.valueOf(u.get("completion_tokens").asLong()));
        }
        if (u.has("json_mode_fallbacks")) {
          tr.extras.put("json_mode_fallbacks", Long.valueOf(u.get("json_mode_fallbacks").asLong()));
        }
        if (u.has("model")) {
          tr.extras.put("llm_model", u.get("model").asText());
        }
        if (u.has("temperature")) {
          tr.extras.put("llm_temperature", Double.valueOf(u.get("temperature").asDouble()));
        }
      }
      return tr;
    } finally {
      try {
        Files.deleteIfExists(tmp);
      } catch (Exception ignore) {
        //
      }
    }
  }

  private static TrialResult stampFail(long t0, String error) {
    TrialResult fail = TrialResult.fail(error);
    long plan = Math.max(0L, System.currentTimeMillis() - t0);
    fail.t_plan_ms = Long.valueOf(plan);
    fail.extras.put("plan_start_ms", Long.valueOf(t0));
    fail.extras.put("plan_end_ms", Long.valueOf(t0 + plan));
    return fail;
  }

  private static String pythonBin() {
    String env = System.getenv("KART_PYTHON");
    return env != null && !env.isEmpty() ? env : "python3";
  }

  private static void copyEnv(Map<String, String> env, String key) {
    String v = System.getenv(key);
    if (v != null) {
      env.put(key, v);
    }
  }
}
