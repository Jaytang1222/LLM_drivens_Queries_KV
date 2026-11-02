package kart.bench;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import kart.cost.CostCard;
import kart.ir.BoundIr;
import kart.query.QueryEngine;
import kart.search.PlannerMode;
import kart.validation.SafePlanHandle;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Bao arm: enumerate KART SafePlans, then call upstream
 * {@code bao_server/main.py BaoModel.select_plan} via Python bridge.
 *
 * Decision logic (predict + argmin / PG_OPTIMIZER_INDEX fallback) is upstream.
 * The model stays in one process ({@code --serve}); per-query {@code t_plan} is
 * SafePlan enumeration plus one {@code select_plan}, not process startup.
 */
public final class BaoPlanArm implements Arm {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String WARMUP =
      "{\"arms\":[{\"plan_id\":\"P_T\",\"card\":{\"estimated_ms\":1.0,"
          + "\"features\":{\"estimated_index_rows\":10}}}]}";
  private final Path root;
  private final ResidentPyServer server = new ResidentPyServer();

  public BaoPlanArm(Path root) {
    this.root = root;
  }

  @Override
  public String id() {
    return "bao";
  }

  @Override
  public TrialResult run(BoundIr ir, BenchContext ctx) throws Exception {
    try {
      ensureServer();
    } catch (Exception e) {
      TrialResult fail = TrialResult.fail("bao server: " + e.getMessage());
      fail.t_plan_ms = Long.valueOf(0L);
      return fail;
    }
    long t0 = System.currentTimeMillis();
    QueryEngine engine = ctx.newEngine(PlannerMode.RULE);
    Path art = ctx.artifactDir(id(), ir.query_id);
    QueryEngine.RunResult probe = engine.run(ir, null, true, null);
    if (probe == null || probe.safe == null || probe.safe.isEmpty()) {
      return stampFail(t0, "bao: no safe plans");
    }

    List<String> arms = new ArrayList<String>();
    Map<String, SafePlanHandle> handleByPid = new HashMap<String, SafePlanHandle>();
    Map<String, CostCard> cardByPid = new HashMap<String, CostCard>();
    for (SafePlanHandle h : probe.safe) {
      if (h == null || h.plan() == null || h.plan().plan_id == null) {
        continue;
      }
      String pid = h.plan().plan_id;
      if (!arms.contains(pid)) {
        arms.add(pid);
      }
      handleByPid.put(pid, h);
    }
    Collections.sort(arms);
    if (probe.costCards != null) {
      for (CostCard c : probe.costCards) {
        if (c != null && c.plan_id != null) {
          cardByPid.put(c.plan_id, c);
        }
      }
    }
    if (arms.isEmpty()) {
      return stampFail(t0, "bao: empty arm list");
    }

    ObjectNode payload = MAPPER.createObjectNode();
    ArrayNode arr = payload.putArray("arms");
    for (String pid : arms) {
      ObjectNode a = arr.addObject();
      a.put("plan_id", pid);
      CostCard c = cardByPid.get(pid);
      ObjectNode card = a.putObject("card");
      if (c != null) {
        card.put("estimated_ms", c.estimated_ms);
        ObjectNode feats = card.putObject("features");
        if (c.features != null && c.features.get("estimated_index_rows") instanceof Number) {
          feats.put("estimated_index_rows",
              ((Number) c.features.get("estimated_index_rows")).doubleValue());
        }
      } else {
        card.put("estimated_ms", 1.0e12);
      }
    }
    long tEnum = Math.max(0L, System.currentTimeMillis() - t0);
    long rpc0 = System.currentTimeMillis();
    JsonNode rootNode;
    try {
      rootNode = server.rpc(MAPPER.writeValueAsString(payload), 60_000L);
    } catch (Exception e) {
      return stampFail(t0, "bao server rpc: " + e.getMessage());
    }
    long tModel = Math.max(0L, System.currentTimeMillis() - rpc0);
    if (!"OK".equals(rootNode.path("status").asText())) {
      return stampFail(t0, rootNode.path("error").asText("bao bridge failed"));
    }
    String chosen = rootNode.path("plan_id").asText(null);
    if (chosen == null || chosen.isEmpty() || !handleByPid.containsKey(chosen)) {
      return stampFail(t0, "bao: chosen plan missing from safe set: " + chosen);
    }

    SafePlanHandle chosenHandle = handleByPid.get(chosen);
    probe.selected = chosenHandle;
    if (probe.costCards != null) {
      for (CostCard c : probe.costCards) {
        if (c != null && chosen.equals(c.plan_id)) {
          probe.selectedCost = c;
          break;
        }
      }
    }
    long tPlanEnd = System.currentTimeMillis();
    long tPlan = Math.max(0L, tPlanEnd - t0);
    probe.t_plan_ms = Long.valueOf(tPlan);
    probe.planStartEpochMs = t0;
    probe.planEndEpochMs = tPlanEnd;
    probe.t_exec_ms = null;

    QueryEngine.RunResult rr = probe;
    long wall = tPlan;
    if (!ctx.planOnly) {
      rr = engine.executeSelected(ir, art, probe);
      wall = Math.max(0L, System.currentTimeMillis() - t0);
    }

    TrialResult tr = TrialResult.fromRun(rr, wall);
    tr.t_plan_ms = Long.valueOf(tPlan);
    tr.extras.put("plan_start_ms", Long.valueOf(t0));
    tr.extras.put("plan_end_ms", Long.valueOf(tPlanEnd));
    if (tr.t_exec_ms != null) {
      tr.t_e2e_ms = Long.valueOf(tPlan + tr.t_exec_ms.longValue());
    } else {
      tr.t_e2e_ms = Long.valueOf(tPlan);
    }
    tr.extras.put("bao_protocol", "upstream_BaoModel.select_plan");
    tr.extras.put("bao_chosen", chosen);
    tr.extras.put("bao_chosen_index", Integer.valueOf(rootNode.path("chosen_index").asInt()));
    tr.extras.put("bao_n_arms", Integer.valueOf(arms.size()));
    tr.extras.put("bao_model_loaded", Boolean.valueOf(rootNode.path("model_loaded").asBoolean()));
    tr.extras.put("bao_model_dir", baoModelDir().toString());
    tr.extras.put("t_enum_ms", Long.valueOf(tEnum));
    tr.extras.put("t_model_ms", Long.valueOf(tModel));
    tr.extras.put("t_model_load_ms", Long.valueOf(server.loadMs()));
    if (rootNode.has("infer_ms")) {
      tr.extras.put("infer_ms", Double.valueOf(rootNode.path("infer_ms").asDouble()));
    }
    tr.extras.put("bao_upstream", "experiments/adapters/upstream/bao/bao_server/main.py");
    tr.extras.put("bao_intentional_changes",
        "resident --serve; t_plan = enum + one select_plan; load excluded");
    return tr;
  }

  private void ensureServer() throws Exception {
    List<String> cmd = new ArrayList<String>();
    cmd.add(pythonBin());
    cmd.add("-u");
    cmd.add(root.resolve("experiments/adapters/bao/bao_bridge.py").toString());
    cmd.add("--serve");
    cmd.add("--model-dir");
    cmd.add(baoModelDir().toString());
    server.start(cmd, root, WARMUP, 180_000L, 60_000L);
  }

  /** Override with {@code KART_BAO_MODEL_DIR} (absolute or repo-relative). Default: T-Drive model. */
  private Path baoModelDir() {
    String env = System.getenv("KART_BAO_MODEL_DIR");
    if (env != null && !env.trim().isEmpty()) {
      Path p = Paths.get(env.trim());
      return p.isAbsolute() ? p : root.resolve(p);
    }
    return root.resolve("experiments/adapters/bao/kart_bao_model");
  }

  private static TrialResult stampFail(long t0, String msg) {
    TrialResult fail = TrialResult.fail(msg);
    long tPlan = Math.max(0L, System.currentTimeMillis() - t0);
    fail.t_plan_ms = Long.valueOf(tPlan);
    fail.applyWrapperPlanClock(t0, tPlan);
    return fail;
  }

  private static String pythonBin() {
    String env = System.getenv("KART_PYTHON");
    return env != null && !env.isEmpty() ? env : "python3";
  }

}
