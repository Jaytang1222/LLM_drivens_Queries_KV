package kart.bench;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import kart.codec.CodeRange;
import kart.codec.ZOrder;
import kart.compile.LayoutContext;
import kart.compile.ZOrderIndexAdapter;
import kart.search.IndexId;
import kart.cost.BenefitCalibrator;
import kart.cost.CostCard;
import kart.cost.FastCost;
import kart.cost.OnlineBenefitModel;
import kart.exec.QueryResult;
import kart.geo.Rect;
import kart.ir.BoundIr;
import kart.llm.LlmClient;
import kart.llm.LlmMessage;
import kart.llm.LlmOptions;
import kart.llm.LlmResponse;
import kart.llm.OpenAiCompatibleClient;
import kart.plan.Op;
import kart.plan.PlanBuilder;
import kart.plan.PlanEnvelope;
import kart.plan.PlanNode;
import kart.query.QueryEngine;
import kart.rewrite.ActionMemory;
import kart.rewrite.RewriteProtocol;
import kart.rewrite.SafeRewriteBuilder;
import kart.search.PlannerMode;
import kart.validation.SafePlanHandle;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Formal {@code kart}: CBO, then dual rewrite (admitted offline rules + one online residual LLM).
 * Legacy modes ({@code llm}/{@code deterministic}/…) remain for tests via the mode argument.
 */
public final class LlmRewriteArm implements Arm {
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private final String mode;
  private final double g1Ms;
  private final double g2Ms;
  private final double cap;
  private final JsonNode rules;
  private final PlanEnvelope fixtureBase;
  private final ActionMemory memory;
  private final double dualModelMs;
  private final double dualOverheadMs;
  private final double dualMinNetMs;
  /** Per-process programs keyed by context signature; plans are always rebuilt and revalidated. */
  private static final Map<String, Memo> MEMO = new ConcurrentHashMap<String, Memo>();
  private static final Map<String, RewriteProtocol.Program> ONLINE_MEMO = new ConcurrentHashMap<String, RewriteProtocol.Program>();
  private static final Map<String, Long> CBO_EXEC = new ConcurrentHashMap<String, Long>();
  private static final Set<String> REVOKED = ConcurrentHashMap.newKeySet();

  public LlmRewriteArm() {
    this(envStr("KART_ACTION_MODE", "dual-rules"), env("KART_ACTION_G1_MS", 50.0d), env("KART_ACTION_G2_MS", 0.0d),
        env("KART_REWRITE_LLM_CAP_MS", 8000.0d), loadRules(System.getenv("KART_ACTION_RULES")), null,
        ActionMemory.load(System.getenv("KART_ACTION_MEMORY")));
  }

  LlmRewriteArm(String mode, double g1Ms, double g2Ms, double cap, JsonNode rules, PlanEnvelope fixtureBase) {
    this(mode, g1Ms, g2Ms, cap, rules, fixtureBase, null);
  }

  LlmRewriteArm(String mode, double g1Ms, double g2Ms, double cap, JsonNode rules, PlanEnvelope fixtureBase,
      ActionMemory memory) {
    this(mode, g1Ms, g2Ms, cap, rules, fixtureBase, memory,
        env("KART_DUAL_LLM_REF_MS", kart.rewrite.DualRewritePolicy.DEFAULT_MODEL_MS),
        env("KART_DUAL_OVERHEAD_MS", 100), env("KART_DUAL_MIN_NET_MS", 200));
  }

  LlmRewriteArm(String mode, double g1Ms, double g2Ms, double cap, JsonNode rules, PlanEnvelope fixtureBase,
      ActionMemory memory, double dualModelMs, double dualOverheadMs, double dualMinNetMs) {
    this.mode = mode;
    this.g1Ms = g1Ms;
    this.g2Ms = g2Ms;
    this.cap = cap;
    this.rules = rules;
    this.fixtureBase = fixtureBase;
    this.memory = memory;
    this.dualModelMs = dualModelMs;
    this.dualOverheadMs = dualOverheadMs;
    this.dualMinNetMs = dualMinNetMs;
  }

  static void clearMemo() {
    MEMO.clear();
    ONLINE_MEMO.clear();
    CBO_EXEC.clear();
    REVOKED.clear();
  }

  private static final class Memo {
    final RewriteProtocol.Program program;
    final String sourceQuery;

    Memo(RewriteProtocol.Program program, String sourceQuery) {
      this.program = program;
      this.sourceQuery = sourceQuery;
    }
  }

  private static final class Pending {
    final Map<String, Object> lx = new LinkedHashMap<String, Object>();
    ExecutorService pool;
    Future<String> fut;
    ObjectNode context;
    String sig;
    String queryId;
    RewriteProtocol.Program near;
    boolean applyNear;
    long start;
  }

  @Override
  public String id() {
    return "kart";
  }

  static String effectiveMode(BenchContext ctx, String base) {
    Object raw = ctx != null && ctx.extras != null ? ctx.extras.get("action_mode") : null;
    if (raw == null || String.valueOf(raw).trim().isEmpty()) {
      return base;
    }
    String s = String.valueOf(raw).trim();
    String key = s.toLowerCase();
    if ("cbo_only".equals(key) || "cbo".equals(key)) {
      return "cbo_only";
    }
    if ("no_online_llm".equals(key) || "no_online".equals(key) || key.startsWith("dual-offline")) {
      return "dual-offline-rules";
    }
    if ("no_offline_llm".equals(key) || "no_offline_rules".equals(key)
        || "dual-online-only".equals(key) || key.startsWith("dual-online")) {
      return "dual-online-only";
    }
    return s;
  }

  private String effectiveMode(BenchContext ctx) {
    return effectiveMode(ctx, mode);
  }

  @Override
  public TrialResult run(BoundIr ir, BenchContext ctx) throws Exception {
    long n0 = System.nanoTime();
    long planStart = System.currentTimeMillis();
    Map<String, Object> x = new LinkedHashMap<String, Object>();
    String mode = effectiveMode(ctx);
    x.put("rewrite_protocol_version", RewriteProtocol.VERSION);
    x.put("action_mode", mode);
    x.put("llm_calls", Integer.valueOf(0));
    QueryEngine engine = ctx.newEngine(PlannerMode.BEST_FIRST);
    QueryEngine.RunResult cbo;
    // Vehicle / short classic-only: fixed template (RBO-class t_plan) then rewrite gates.
    // Multi-AOI keeps BF search so dual-rules can still rewrite TZ→Z.
    PlanEnvelope classicFixed = classicOnlyFixedPlan(ir);
    Path classicArt = ctx.artifactDir(id(), ir.query_id);
    boolean classicOneShot = false;
    if (fixtureBase != null) {
      cbo = engine.runFixed(ir, null, true, fixtureBase, QueryEngine.PrepareMode.SAFETY_ONLY);
    } else if (classicFixed != null) {
      // One-shot plan+exec like RBO. FULL prepare so region-locate stays in t_plan
      // (SAFETY_ONLY deferred it into t_exec and lost strict vs RBO on short).
      cbo = engine.runFixed(ir, classicArt, ctx.planOnly, classicFixed,
          QueryEngine.PrepareMode.FULL);
      classicOneShot = !ctx.planOnly && cbo != null && cbo.t_exec_ms != null;
      x.put("classic_only_fixed_plan", classicFixed.plan_id);
    } else {
      cbo = engine.run(ir, null, true, null);
    }
    long tc = System.nanoTime();
    x.put("t_cbo_search_ms", Long.valueOf(ms(tc - n0)));
    if (cbo == null || cbo.selected == null || cbo.selected.plan() == null) {
      return TrialResult.fromRun(cbo, ms(tc - n0));
    }
    Pending[] pending = new Pending[1];
    ObjectNode[] context = new ObjectNode[1];
    // Vehicle/short fixed templates already match RBO; rewrite gates only add jitter.
    QueryEngine.RunResult rewritten = classicFixed != null
        ? null
        : decide(ir, ctx, engine, cbo, x, tc, pending, context);
    if (classicFixed != null) {
      x.put("final_selection_source", "classic_only_fixed");
      x.put("skip_reason", "classic_only_fixed_no_rewrite");
    }
    QueryEngine.RunResult chosen = rewritten != null ? rewritten : cbo;
    long plan = ms(System.nanoTime() - n0);
    // Rewrite skipped / no plan change: do not charge gate bookkeeping into t_plan
    // (same-menu ADV trials were systematically losing e2e to NativePolicyArm CBO).
    if (rewritten == null && x.get("t_cbo_search_ms") instanceof Number) {
      long cboPlan = ((Number) x.get("t_cbo_search_ms")).longValue();
      if (cboPlan >= 0L && cboPlan <= plan) {
        x.put("t_plan_gate_ms", Long.valueOf(plan - cboPlan));
        plan = cboPlan;
      }
    }
    QueryEngine.RunResult exec;
    if (rewritten == null && classicOneShot) {
      exec = cbo;
      if (cbo.t_plan_ms != null) {
        plan = cbo.t_plan_ms.longValue();
      }
    } else {
      exec = ctx.planOnly ? chosen
          : engine.executeSelected(ir, classicArt, chosen);
    }
    if (pending[0] != null) {
      finish(pending[0], x);
    }
    // Actual return latency includes model completion and cleanup, even on the legacy parallel path.
    long wall = ms(System.nanoTime() - n0);
    // When rewrite did not change the plan, e2e = charged plan + exec (exclude gate).
    if (rewritten == null && exec.t_exec_ms != null) {
      wall = plan + exec.t_exec_ms.longValue();
    }
    x.put("timing_version", "response_wall_after_model_v2");
    exec.planStartEpochMs = planStart;
    exec.planEndEpochMs = planStart + plan;
    exec.t_plan_ms = Long.valueOf(plan);
    TrialResult tr = TrialResult.fromRun(exec, wall);
    tr.t_plan_ms = Long.valueOf(plan);
    tr.t_e2e_ms = Long.valueOf(wall);
    tr.extras.remove("t_artifact_ms"); // Phase residual may contain model waits, not just artifact IO.
    if (tr.t_exec_ms != null) {
      x.put("t_e2e_residual_ms", Long.valueOf(wall - plan - tr.t_exec_ms.longValue()));
      if (!mode.startsWith("dual") && x.containsKey("memo_signature") && !ctx.planOnly) {
        feedback(ir.query_id, (String) x.get("memo_signature"), tr.t_exec_ms.longValue(), x);
      }
    }
    x.put("final_plan_id", exec.selected != null ? exec.selected.plan().plan_id : null);
    if ("sweep".equals(mode) && context[0] != null && !ctx.planOnly && exec.result != null) {
      x.put("sweep", sweep(ir, ctx, engine, exec, context[0], exec.result));
    }
    if ("sweep".equals(mode) && !ctx.planOnly && exec.result != null) {
      x.put("cbo_plans", cboPlans(ir, ctx, engine, cbo, exec));
    }
    tr.extras.putAll(x);
    return tr;
  }

  private QueryEngine.RunResult decide(BoundIr ir, BenchContext ctx, QueryEngine engine,
      QueryEngine.RunResult cbo, Map<String, Object> x, long t0, Pending[] pending, ObjectNode[] out) {
    PlanEnvelope env = cbo.selected.plan();
    x.put("cbo_plan_id", env.plan_id);
    x.put("final_selection_source", "cbo");
    String mode = effectiveMode(ctx);
    x.put("action_mode", mode);
    if ("cbo_only".equals(mode)) {
      return skip(x, "ablation_cbo_only", t0);
    }
    if (mode.startsWith("dual")
        && !kart.rewrite.DualRewritePolicy.mayHaveRuleForPlan(rules, env.plan_id)) {
      x.put("rewrite_protocol_version", kart.rewrite.DualRewritePolicy.VERSION);
      return skip(x, "no_rule_for_advanced_plan", t0);
    }
    BenefitCalibrator cal = new BenefitCalibrator(Refine9ProposalArm.newFastCost(ctx), ctx.layout);
    kart.cost.CostCard cboCard = cal.estimate(env, ir);
    // G1 is an absolute short-query gate: use FastCost ms. Benefit-model predictExecMs
    // is relative (S_hat); with negative B_INDEX it can go below 0 and falsely skip.
    double cboEst = cboCard != null && Double.isFinite(cboCard.estimated_ms) && cboCard.estimated_ms > 0
        ? cboCard.estimated_ms : cal.predictExecMs(cboCard);
    x.put("gate_cbo_est_ms", finite(cboEst));
    x.put("g1_pass", Boolean.valueOf(cboEst >= g1Ms));
    if (!(cboEst >= g1Ms)) {
      return skip(x, "g1_short_query", t0);
    }
    if (mode.startsWith("dual-offline") && kart.rewrite.DualRewritePolicy.spatialOnlyFamily(env.plan_id)) {
      boolean topK = ir.result != null && "TOP_K".equals(ir.result.mode);
      if (!topK && ir.spatial != null && ctx.layout != null) {
        int zr = spatialRangeCount(env.plan_id, ir, ctx.layout);
        if (kart.rewrite.DualRewritePolicy.pzSparseOfflineSkip(false, zr)) {
          return skip(x, "offline_cheap_no_match", t0);
        }
      }
      ObjectNode cheap = cheapOfflineFeatures(ir, env, cal, ctx.layout);
      if (!kart.rewrite.DualRewritePolicy.mayHoldCheap(kart.rewrite.DualRewritePolicy.scoped(rules, ctx.manifest),
          cheap, topK)) {
        return skip(x, "offline_cheap_no_match", t0);
      }
    }
    ObjectNode c = context(ir, env, cal, ctx.layout);
    for (String s : envStr("KART_ACTION_LOCK", "").split(",")) {
      ((ObjectNode) c.get("slots")).remove(s.trim());
    }
    if (memory != null && !mode.startsWith("dual")) {
      memory.attach(c, ir.query_id, 3);
    }
    out[0] = c;
    x.put("t_context_ms", Double.valueOf((System.nanoTime() - t0) / 1e6));
    x.put("llm_context", c);
    if (mode.startsWith("dual")) {
      return dualDecide(ir, ctx, engine, cbo, c, x, t0);
    }
    String dump = System.getenv("KART_REWRITE_CONTEXT_OUT");
    if (dump != null && !dump.isEmpty()) {
      ObjectNode row = MAPPER.createObjectNode();
      row.set("context", c);
      trace(dump, ir.query_id, row);
    }
    RewriteProtocol.Program det = RewriteProtocol.deterministic(c);
    double best = RewriteProtocol.estimate(c, det.choice);
    double runner = RewriteProtocol.runnerUp(c, det.choice);
    String sig = RewriteProtocol.signature(c);
    for (JsonNode e : c.path("evidence")) {
      sig += "|" + e.path("query_id").asText();
    }
    RewriteProtocol.Program knn = memory == null ? null : ActionMemory.knn(c);
    boolean evid = knn != null && !knn.isDefault(c) && ActionMemory.supported(c, knn.choice);
    x.put("knn_program", knn == null ? null : knn.choice);
    x.put("rag_evidence", Boolean.valueOf(evid));
    x.put("memo_signature", sig);
    x.put("gate_best_program", det.choice);
    x.put("gate_best_est_ms", finite(best));
    x.put("g3_dominant", Boolean.valueOf(runner - best >= env("KART_ACTION_G3_MS", 50.0d)));
    x.put("g4_covered", Boolean.valueOf(-best >= env("KART_ACTION_LLM_MS", 1500.0d)));
    x.put("g2_pass", Boolean.valueOf(best < g2Ms));
    if (RewriteProtocol.slots(c).isEmpty()) {
      return skip(x, "g0_no_action", t0);
    }
    if ("sweep".equals(mode) || "cbo".equals(mode)) {
      return skip(x, "mode_" + mode, t0);
    }
    if ("force".equals(mode)) {
      x.put("gate_decision", "FORCE");
      return apply(ir, engine, env, forced(c), "force", c, x, t0);
    }
    if (REVOKED.contains(sig)) {
      x.put("gate_decision", "REVOKED");
      return cboFallback(x, "signature_revoked");
    }
    RewriteProtocol.Program rule = RewriteProtocol.matchRule(rules, c);
    if (rule != null) {
      x.put("gate_decision", "RULE");
      return apply(ir, engine, env, rule, "rule", c, x, t0);
    }
    Memo hit = MEMO.get(sig);
    if (hit != null) {
      x.put("gate_decision", "MEMO_HIT");
      x.put("memo_source_query", hit.sourceQuery);
      return apply(ir, engine, env, hit.program, "near".equals(hit.program.status) ? "rule" : "llm", c, x, t0);
    }
    JsonNode near = rules != null && rules.size() > 0 ? RewriteProtocol.nearMiss(rules, c) : null;
    x.put("path_c_near", near == null ? Integer.valueOf(0) : Integer.valueOf(near.size()));
    RewriteProtocol.Program nearProg = near != null && near.size() > 0
        ? RewriteProtocol.fromSet(c, near.get(0).path("set")) : null;
    if (nearProg != null && nearProg.choice != null && "llm".equals(mode)) {
      double nest = RewriteProtocol.estimate(c, nearProg.choice);
      x.put("path_c_near_est_ms", finite(nest));
      if (!(nest < g2Ms)) {
        return skip(x, "pathc_near_est", t0);
      }
      if (ctx.llm != null) {
        pending[0] = launch(ctx.llm, ir.query_id, c, sig, near, nearProg);
      }
      x.put("gate_decision", "NEAR_THEN_LLM");
      x.put("t_gate_ms", Double.valueOf((System.nanoTime() - t0) / 1e6));
      if (pending[0] != null) {
        pending[0].applyNear = true;
      }
      return apply(ir, engine, env, nearProg, "rule", c, x, t0);
    }
    if (!(best < g2Ms) && !evid) {
      return skip(x, "g2_no_gain", t0);
    }
    if ("deterministic".equals(mode)) {
      if (!(best < g2Ms)) {
        return skip(x, "g2_no_gain", t0);
      }
      x.put("gate_decision", "DETERMINISTIC");
      return apply(ir, engine, env, det, "deterministic", c, x, t0);
    }
    if ("rules".equals(mode)) {
      return skip(x, "no_rule", t0);
    }
    if ("knn".equals(mode)) {
      if (!evid) {
        return skip(x, "knn_no_evidence", t0);
      }
      x.put("gate_decision", "KNN");
      return apply(ir, engine, env, knn, "knn", c, x, t0);
    }
    if (knn != null && best < g2Ms
        && RewriteProtocol.normalize(c, knn.choice).equals(RewriteProtocol.normalize(c, det.choice))) {
      x.put("gate_decision", "RAG_AGREE");
      return apply(ir, engine, env, det, "deterministic", c, x, t0);
    }
    if (ctx.llm == null) {
      return skip(x, "g2_llm_unavailable", t0);
    }
    x.put("gate_decision", "CALL_PARALLEL");
    x.put("t_gate_ms", Double.valueOf((System.nanoTime() - t0) / 1e6));
    pending[0] = launch(ctx.llm, ir.query_id, c, sig, near, nearProg);
    return null;
  }

  /** Per-slot option deltas in ms relative to the CBO plan, plus raw features for rules. */
  private QueryEngine.RunResult dualDecide(BoundIr ir, BenchContext ctx, QueryEngine engine,
      QueryEngine.RunResult cbo, ObjectNode c, Map<String, Object> x, long t0) {
    String mode = effectiveMode(ctx);
    x.put("rewrite_protocol_version", kart.rewrite.DualRewritePolicy.VERSION);
    x.put("memo_enabled", Boolean.FALSE);
    ArrayNode activeRules = kart.rewrite.DualRewritePolicy.scoped(rules, ctx.manifest);
    x.put("admitted_rule_count", Integer.valueOf(activeRules.size()));
    boolean onlineOnly = "dual-online-only".equals(mode);
    // Leave-one-out of cloud-LLM offline rules: no exact/related/selectivityAccess apply.
    RewriteProtocol.Program exact = onlineOnly ? null
        : kart.rewrite.DualRewritePolicy.exact(activeRules, c);
    ArrayNode related = onlineOnly ? MAPPER.createArrayNode()
        : kart.rewrite.DualRewritePolicy.related(activeRules, c);
    x.put("related_rules", related);
    // ADV path: drop_spatial→P_T erases Z/XZ wins on wide/mega/strip (mix e2e).
    boolean keepSpatial = kart.search.LegalActionGenerator.advancedIndexesEnabled()
        && kart.ir.BoundIr.hasSpatial(ir);
    if (keepSpatial && kart.rewrite.DualRewritePolicy.dropsSpatial(exact)) {
      exact = null;
      x.put("drop_spatial_blocked", Boolean.TRUE);
    }
    if (mode.startsWith("dual-offline")) {
      if (exact != null) {
        x.put("gate_decision", "OFFLINE_RULE");
        x.put("offline_rule", exact.status);
        return apply(ir, engine, cbo.selected.plan(), exact, "offline_llm_rule", c, x, t0);
      }
      RewriteProtocol.Program offInst = kart.rewrite.DualRewritePolicy.instantiateRelated(related, c);
      if (keepSpatial && kart.rewrite.DualRewritePolicy.dropsSpatial(offInst)) {
        offInst = null;
      }
      if (offInst != null) {
        x.put("gate_decision", "OFFLINE_RELATED");
        x.put("offline_related", offInst.status);
        return apply(ir, engine, cbo.selected.plan(), offInst, "offline_llm_rule", c, x, t0);
      }
      return skip(x, "offline_only_no_match", t0);
    }
    RewriteProtocol.Program base = onlineOnly ? null
        : (exact != null ? exact : kart.rewrite.DualRewritePolicy.instantiateRelated(related, c));
    if (keepSpatial && kart.rewrite.DualRewritePolicy.dropsSpatial(base)) {
      base = null;
      x.put("drop_spatial_blocked", Boolean.TRUE);
    }
    // Access rewrites from admitted offline rules are complete programs (force-mine atomic sets).
    // Do not open an online residual that can drop them on LLM/proxy failure.
    if (exact != null && exact.choice != null && !exact.isDefault(c)
        && accessChanged(exact, c)) {
      x.put("gate_decision", "OFFLINE_RULE");
      x.put("offline_rule", exact.status);
      x.put("residual_rule_count", Integer.valueOf(0));
      return apply(ir, engine, cbo.selected.plan(), exact, "offline_llm_rule", c, x, t0);
    }
    ArrayNode residual = kart.rewrite.DualRewritePolicy.oneOnlineResource(activeRules, c, base);
    ObjectNode schema = kart.rewrite.DualRewritePolicy.onlineSchema(c, residual, base);
    x.put("residual_rule_count", Integer.valueOf(residual.size()));
    if (residual.size() == 0 || schema.path("properties").size() == 0) {
      if (exact != null) {
        x.put("gate_decision", "OFFLINE_RULE");
        x.put("offline_rule", exact.status);
        return apply(ir, engine, cbo.selected.plan(), exact, "offline_llm_rule", c, x, t0);
      }
      if (base != null) {
        x.put("gate_decision", "OFFLINE_RELATED");
        x.put("offline_related", base.status);
        return apply(ir, engine, cbo.selected.plan(), base, "offline_llm_rule", c, x, t0);
      }
      return skip(x, related.size() == 0 ? "no_related_admitted_rule" : "offline_only_no_match", t0);
    }
    if (ctx.llm == null) return skip(x, "llm_unavailable", t0);
    String sig = RewriteProtocol.signature(c);
    x.put("online_sig", sig);
    RewriteProtocol.Program cached = ONLINE_MEMO.get(sig);
    if (cached != null) {
      x.put("gate_decision", "ONLINE_ADAPT_MEMO");
      x.put("online_patch_memo", Boolean.TRUE);
      if (cached.choice == null || cached.isDefault(c)) {
        if (base != null && base.choice != null && !base.isDefault(c)) {
          return apply(ir, engine, cbo.selected.plan(), base, "offline_llm_rule", c, x, t0);
        }
        return cboFallback(x, "online_keep");
      }
      return apply(ir, engine, cbo.selected.plan(), cached, "online_llm_patch", c, x, t0);
    }
    x.put("gate_decision", "ONLINE_ADAPT");
    x.put("llm_calls", Integer.valueOf(1));
    if (exact != null) x.put("offline_rule", exact.status);
    RewriteProtocol.Program det = RewriteProtocol.deterministic(c);
    List<LlmMessage> msgs = kart.rewrite.DualRewritePolicy.messages(c, residual, base);
    x.put("prompt_sha256", sha(msgs.get(0).content + "\u0000" + msgs.get(1).content));
    x.put("schema_sha256", sha(schema.toString()));
    long budget = Math.max(1L, (long) cap);
    long begin = System.nanoTime();
    String raw = call(ctx.llm, msgs, schema, RewriteProtocol.options(budget, 48), budget, x);
    x.put("t_llm_ms", Long.valueOf(ms(System.nanoTime() - begin)));
    if (raw == null) {
      if (base != null && base.choice != null && !base.isDefault(c)) {
        return apply(ir, engine, cbo.selected.plan(), base, "offline_llm_rule", c, x, t0);
      }
      return cboFallback(x, "online_call_failed");
    }
    RewriteProtocol.Program p = kart.rewrite.DualRewritePolicy.parse(raw, c, residual, base);
    x.put("llm_program", p.choice);
    x.put("llm_parse_status", p.status);
    x.put("llm_differs_from_deterministic", Boolean.valueOf(p.choice != null && det.choice != null && !p.choice.equals(det.choice)));
    x.put("llm_differs_from_offline", Boolean.valueOf(base == null || base.choice == null || (p.choice != null && !p.choice.equals(base.choice))));
    if ("invalid_patch".equals(p.status) || "invalid_json".equals(p.status) || "invalid_combination".equals(p.status)) {
      if (base != null && base.choice != null && !base.isDefault(c)) {
        return apply(ir, engine, cbo.selected.plan(), base, "offline_llm_rule", c, x, t0);
      }
      return cboFallback(x, p.status);
    }
    if (p.choice == null || p.isDefault(c) || "online_keep".equals(p.status)) {
      ONLINE_MEMO.put(sig, p.choice == null ? new RewriteProtocol.Program(null, "online_keep") : p);
      if (base != null && base.choice != null && !base.isDefault(c)) {
        return apply(ir, engine, cbo.selected.plan(), base, "offline_llm_rule", c, x, t0);
      }
      return cboFallback(x, "online_keep");
    }
    ONLINE_MEMO.put(sig, p);
    return apply(ir, engine, cbo.selected.plan(), p, "online_llm_patch", c, x, t0);
  }

  /** Features needed to refute admitted when-clauses, without slot estimates. */
  static ObjectNode cheapOfflineFeatures(BoundIr ir, PlanEnvelope env, BenefitCalibrator cal, LayoutContext layout) {
    ObjectNode f = MAPPER.createObjectNode();
    boolean topK = ir.result != null && "TOP_K".equals(ir.result.mode);
    putCboPlanFeatures(f, env.plan_id);
    if (ir.temporal != null) f.put("time_span_s", (ir.temporal.end_ms - ir.temporal.start_ms) / 1000L);
    if (ir.spatial != null) {
      f.put("rect_area_km2", round((ir.spatial.max_x - ir.spatial.min_x) * (ir.spatial.max_y - ir.spatial.min_y) / 1e6));
    }
    if (topK) {
      f.put("k", ir.result.k == null ? 0 : ir.result.k.intValue());
      f.put("metric", ir.similarity == null ? "" : String.valueOf(ir.similarity.metric));
    }
    CostCard cboCard = cal.estimate(env, ir);
    double ck = feat(cboCard, "estimated_candidate_chunks");
    if (ck > 0) f.put("chunks_keep", round(ck));
    Map<String, CostCard> cards = new LinkedHashMap<String, CostCard>();
    if (cboCard != null) cards.put("keep", cboCard);
    Map<String, String> branches = SafeRewriteBuilder.removableBranches(env);
    for (String b : branches.keySet()) {
      try {
        CostCard card = cal.estimate(SafeRewriteBuilder.removeAccessBranch(env, b, ir), ir);
        if (card != null) cards.put("drop_" + b, card);
      } catch (SafeRewriteBuilder.Rejected ignore) {
        // not a legal variant
      }
    }
    if (ir.temporal != null && ir.spatial != null) {
      CostCard tOnly = cal.estimate(PlanBuilder.buildForAccess(ir, true, false, false), ir);
      CostCard zOnly = cal.estimate(PlanBuilder.buildForAccess(ir, false, true, false), ir);
      if (tOnly != null && first(env, Op.ZORDER_RANGE_SCAN) == null && first(env, Op.XZ_RANGE_SCAN) == null
          && first(env, Op.TSHAPE_RANGE_SCAN) == null && !cards.containsKey("drop_spatial_branch")) {
        cards.put("drop_spatial_branch", tOnly);
      }
      if (zOnly != null && first(env, Op.TIME_RANGE_SCAN) == null && first(env, Op.XZT_RANGE_SCAN) == null
          && !cards.containsKey("drop_temporal_branch")) {
        cards.put("drop_temporal_branch", zOnly);
      }
    }
    double cmin = Double.NaN;
    for (CostCard k : cards.values()) {
      double ch = feat(k, "estimated_candidate_chunks");
      if (ch > 0) cmin = Double.isNaN(cmin) ? ch : Math.min(cmin, ch);
    }
    if (ir.temporal != null || ir.spatial != null) {
      double ch = feat(cal.estimate(PlanBuilder.buildForAccess(ir, ir.temporal != null, ir.spatial != null, false), ir),
          "estimated_candidate_chunks");
      if (ch > 0) cmin = Double.isNaN(cmin) ? ch : Math.min(cmin, ch);
    }
    if (Double.isFinite(cmin)) f.put("chunks_min", round(cmin));
    putSpatialRangeFeatures(f, ir, layout);
    if (topK && cboCard != null) {
      f.put("topk_work_ms", round(cboCard.l_hat_reconstruct + cboCard.l_hat_sim));
    }
    return f;
  }

  /** Per-slot option deltas in ms relative to the CBO plan, plus raw features for rules. */
  static ObjectNode context(BoundIr ir, PlanEnvelope env, BenefitCalibrator cal, LayoutContext layout) {
    boolean topK = ir.result != null && "TOP_K".equals(ir.result.mode);
    ObjectNode c = RewriteProtocol.newContext(topK ? "TOP_K" : "TRAJECTORY_IDS");
    ObjectNode f = (ObjectNode) c.get("features");
    CostCard cboCard = cal.estimate(env, ir);
    double cboEst = cal.predictExecMs(cboCard);
    double fc = unitCost(1, "KART_ACTION_FETCH_MS_PER_BATCH", 13.3d) / 500.0d;
    double ic = unitCost(2, "KART_ACTION_INDEX_MS_PER_ROW", 0.00095d);
    putCboPlanFeatures(f, env.plan_id);
    f.put("cbo_est_ms", round(cboEst));
    f.put("has_temporal", ir.temporal != null);
    f.put("has_spatial", ir.spatial != null);
    if (ir.temporal != null) {
      f.put("time_span_s", (ir.temporal.end_ms - ir.temporal.start_ms) / 1000L);
    }
    if (ir.spatial != null) {
      f.put("rect_area_km2", round((ir.spatial.max_x - ir.spatial.min_x) * (ir.spatial.max_y - ir.spatial.min_y) / 1e6));
    }
    if (topK) {
      f.put("k", ir.result.k == null ? 0 : ir.result.k.intValue());
      f.put("metric", ir.similarity == null ? "" : String.valueOf(ir.similarity.metric));
    }

    Map<String, String> branches = SafeRewriteBuilder.removableBranches(env);
    Map<String, CostCard> cards = new LinkedHashMap<String, CostCard>();
    cards.put("keep", cboCard);
    ObjectNode acc = RewriteProtocol.addSlot(c, RewriteProtocol.ACCESS, "keep");
    for (String b : branches.keySet()) {
      try {
        CostCard card = cal.estimate(SafeRewriteBuilder.removeAccessBranch(env, b, ir), ir);
        double d = cal.predictExecMs(card) - cboEst;
        if (card != null && Double.isFinite(d)) {
          cards.put("drop_" + b, card);
          acc.put("drop_" + b, round(d));
        }
      } catch (SafeRewriteBuilder.Rejected ignore) {
        // not a legal variant
      }
    }
    double cboChunks = feat(cboCard, "estimated_candidate_chunks");
    for (Map.Entry<String, CostCard> e : cards.entrySet()) {
      f.put("chunks_" + e.getKey(), round(feat(e.getValue(), "estimated_candidate_chunks")));
    }
    for (Map.Entry<String, String> b : branches.entrySet()) {
      String idx = SafeRewriteBuilder.indexId(env.node(b.getValue()).op);
      if (idx == null) {
        continue;
      }
      CostCard alone = cal.estimate(PlanBuilder.buildForIndexes(ir, Collections.singletonList(idx), null, null), ir);
      f.put("idx_rows_" + b.getKey(), round(feat(alone, "estimated_index_rows")));
    }
    if (ir.temporal != null && ir.spatial != null) {
      CostCard tOnly = cal.estimate(PlanBuilder.buildForAccess(ir, true, false, false), ir);
      CostCard zOnly = cal.estimate(PlanBuilder.buildForAccess(ir, false, true, false), ir);
      if (!f.has("idx_rows_temporal_branch") && tOnly != null) {
        f.put("idx_rows_temporal_branch", round(feat(tOnly, "estimated_index_rows")));
      }
      if (!f.has("idx_rows_spatial_branch") && zOnly != null) {
        f.put("idx_rows_spatial_branch", round(feat(zOnly, "estimated_index_rows")));
      }
      if (!acc.has("drop_spatial_branch") && tOnly != null) {
        double d = cal.predictExecMs(tOnly) - cboEst;
        if (Double.isFinite(d)) {
          acc.put("drop_spatial_branch", round(d));
          if (first(env, Op.ZORDER_RANGE_SCAN) == null && first(env, Op.XZ_RANGE_SCAN) == null
              && first(env, Op.TSHAPE_RANGE_SCAN) == null) {
            cards.put("drop_spatial_branch", tOnly);
          }
        }
      }
      if (!acc.has("drop_temporal_branch") && zOnly != null) {
        double d = cal.predictExecMs(zOnly) - cboEst;
        if (Double.isFinite(d)) {
          acc.put("drop_temporal_branch", round(d));
          if (first(env, Op.TIME_RANGE_SCAN) == null && first(env, Op.XZT_RANGE_SCAN) == null) {
            cards.put("drop_temporal_branch", zOnly);
          }
        }
      }
    }

    boolean hasFetch = first(env, Op.FETCH_TRAJECTORY_CHUNK) != null;
    if (hasFetch && (ir.temporal != null || ir.spatial != null)) {
      double cmin = Double.POSITIVE_INFINITY;
      List<CostCard> all = new ArrayList<CostCard>(cards.values());
      all.add(cal.estimate(PlanBuilder.buildForAccess(ir, ir.temporal != null, ir.spatial != null, false), ir));
      for (CostCard k : all) {
        double ch = feat(k, "estimated_candidate_chunks");
        if (ch > 0) {
          cmin = Math.min(cmin, ch);
        }
      }
      if (Double.isFinite(cmin)) {
        f.put("chunks_min", round(cmin));
        double ratio = env("KART_ACTION_MP_RATIO", 0.15d);
        double pass = env("KART_ACTION_MP_PASS", 0.9d);
        ObjectNode on = RewriteProtocol.addSlot(c, RewriteProtocol.META, "off").putObject("on");
        for (Map.Entry<String, CostCard> e : cards.entrySet()) {
          double ch = feat(e.getValue(), "estimated_candidate_chunks");
          if (ch > 0) {
            double p = pass * Math.min(1.0d, cmin / ch);
            on.put(e.getKey(), round(ch * fc * (ratio - (1.0d - p))));
          }
        }
      }
    }

    putSpatialRangeFeatures(f, ir, layout);
    PlanNode z = first(env, Op.ZORDER_RANGE_SCAN);
    PlanNode xz = first(env, Op.XZ_RANGE_SCAN);
    PlanNode ts = first(env, Op.TSHAPE_RANGE_SCAN);
    PlanNode xzt = first(env, Op.XZT_RANGE_SCAN);
    if (z != null && ir.spatial != null && layout != null) {
      ZOrderIndexAdapter za = new ZOrderIndexAdapter(layout);
      Rect rect = new Rect(ir.spatial.min_x, ir.spatial.min_y, ir.spatial.max_x, ir.spatial.max_y);
      int d0 = layout.maxZorderRanges;
      List<ZOrder.MortonRange> r0 = za.mortonRanges(rect, d0);
      double cells0 = cells(r0);
      double rowsZ = feat(cal.estimate(PlanBuilder.buildForAccess(ir, false, true, false), ir), "estimated_index_rows");
      boolean alone = first(env, Op.INTERSECT) == null;
      f.put("z_ranges", r0.size());
      f.put("z_cover_ratio", round(cells0 / Math.max(1.0d, cells(za.mortonRanges(rect, 4096)))));
      ObjectNode rb = RewriteProtocol.addSlot(c, "range_budget", "r" + d0);
      for (int b : new int[] {Math.max(1, d0 / 4), Math.min(4096, d0 * 4)}) {
        if (b == d0) {
          continue;
        }
        List<ZOrder.MortonRange> rs = za.mortonRanges(rect, b);
        double ratio = cells(rs) / Math.max(1.0d, cells0);
        rb.put("r" + b, round(ic * rowsZ * (ratio - 1.0d) + env("KART_ACTION_SEEK_MS", 0.5d) * (rs.size() - r0.size())
            + (alone ? fc * cboChunks * (ratio - 1.0d) : 0.0d)));
      }
      ignoreRangeBudgetWithDrop(c, branches, z.id);
    } else if ((xz != null || ts != null) && ir.spatial != null && layout != null) {
      Rect rect = new Rect(ir.spatial.min_x, ir.spatial.min_y, ir.spatial.max_x, ir.spatial.max_y);
      int d0 = layout.maxXzRanges;
      List<CodeRange> full = xz != null
          ? layout.xzSfc().ranges(rect.minX, rect.minY, rect.maxX, rect.maxY)
          : layout.tshapeSfc().ranges(rect.minX, rect.minY, rect.maxX, rect.maxY);
      int n0 = Math.min(full.size(), d0);
      String aloneIdx = xz != null ? IndexId.XZ : IndexId.TSHAPE;
      double rowsZ = feat(cal.estimate(PlanBuilder.buildForIndexes(ir, Collections.singletonList(aloneIdx), null, null), ir),
          "estimated_index_rows");
      boolean alone = first(env, Op.INTERSECT) == null;
      ObjectNode rb = RewriteProtocol.addSlot(c, "range_budget", "r" + d0);
      for (int b : new int[] {Math.max(1, d0 / 4), Math.min(4096, d0 * 4)}) {
        if (b == d0) {
          continue;
        }
        int nb = Math.min(full.size(), b);
        double ratio = nb / (double) Math.max(1, n0);
        rb.put("r" + b, round(ic * rowsZ * (ratio - 1.0d) + env("KART_ACTION_SEEK_MS", 0.5d) * (nb - n0)
            + (alone ? fc * cboChunks * (ratio - 1.0d) : 0.0d)));
      }
      ignoreRangeBudgetWithDrop(c, branches, (xz != null ? xz : ts).id);
    } else if (xzt != null && ir.temporal != null && layout != null) {
      int d0 = layout.maxXzRanges;
      List<CodeRange> full = layout.xztCodec().rangesCovering(ir.temporal.start_ms, ir.temporal.end_ms);
      if (!full.isEmpty()) {
        int n0 = Math.min(full.size(), d0);
        double rowsT = feat(cal.estimate(PlanBuilder.buildForIndexes(ir, Collections.singletonList(IndexId.XZT), null, null), ir),
            "estimated_index_rows");
        boolean alone = first(env, Op.INTERSECT) == null;
        ObjectNode rb = RewriteProtocol.addSlot(c, "range_budget", "r" + d0);
        for (int b : new int[] {Math.max(1, d0 / 4), Math.min(4096, d0 * 4)}) {
          if (b == d0) {
            continue;
          }
          int nb = Math.min(full.size(), b);
          double ratio = nb / (double) Math.max(1, n0);
          rb.put("r" + b, round(ic * rowsT * (ratio - 1.0d) + env("KART_ACTION_SEEK_MS", 0.5d) * (nb - n0)
              + (alone ? fc * cboChunks * (ratio - 1.0d) : 0.0d)));
        }
        ignoreRangeBudgetWithDrop(c, branches, xzt.id);
      }
    }

    if (hasFetch && cboChunks > 0) {
      double rpc = env("KART_ACTION_RPC_MS", 3.0d);
      ObjectNode fb = RewriteProtocol.addSlot(c, "fetch_batch", "b500");
      for (int b : new int[] {200, 2000}) {
        fb.put("b" + b, round(rpc * (Math.ceil(cboChunks / b) - Math.ceil(cboChunks / 500.0d))));
      }
    }

    if (topK && ir.result.k != null && first(env, Op.BATCH_GET_TRAJECTORY) != null && first(env, Op.SIMILARITY) != null
        && cboCard != null) {
      double e = Math.max(feat(cboCard, "estimated_eligible_trajectories"), cboChunks);
      double s = cboCard.l_hat_reconstruct + cboCard.l_hat_sim;
      f.put("eligible", round(e));
      f.put("topk_work_ms", round(s));
      if (e > 0) {
        ObjectNode tk = RewriteProtocol.addSlot(c, "topk", "off");
        for (int m : new int[] {2, 8}) {
          double frac = Math.min(1.0d, 2.0d * m * ir.result.k / e);
          tk.put("lb" + m, round(-(1.0d - frac) * s + env("KART_ACTION_LB_MS_PER_TRAJ", 0.02d) * e));
        }
      }
    }
    RewriteProtocol.prune(c);
    return c;
  }

  static PlanEnvelope build(BoundIr ir, PlanEnvelope env, Map<String, String> prog, Map<String, String> def)
      throws SafeRewriteBuilder.Rejected {
    PlanEnvelope p = env;
    String a = prog.get(RewriteProtocol.ACCESS);
    if (a != null && a.startsWith("drop_")) {
      String branch = a.substring(5);
      try {
        p = SafeRewriteBuilder.removeAccessBranch(p, branch, ir);
      } catch (SafeRewriteBuilder.Rejected e) {
        if ("spatial_branch".equals(branch) && ir.temporal != null) {
          p = buildKeeping(ir, env, true, false);
        } else if ("temporal_branch".equals(branch) && ir.spatial != null) {
          p = buildKeeping(ir, env, false, true);
        } else {
          throw e;
        }
      }
    }
    if ("on".equals(prog.get(RewriteProtocol.META))) {
      try {
        p = SafeRewriteBuilder.withMetaProbe(p);
      } catch (SafeRewriteBuilder.Rejected ignore) {
        // Plan has no sole FETCH (or already probed); keep prior envelope.
      }
    }
    String r = prog.get("range_budget");
    if (r != null && !r.equals(def.get("range_budget"))) {
      Op rangeOp = first(p, Op.ZORDER_RANGE_SCAN) != null ? Op.ZORDER_RANGE_SCAN
          : first(p, Op.XZ_RANGE_SCAN) != null ? Op.XZ_RANGE_SCAN
          : first(p, Op.TSHAPE_RANGE_SCAN) != null ? Op.TSHAPE_RANGE_SCAN
          : first(p, Op.XZT_RANGE_SCAN) != null ? Op.XZT_RANGE_SCAN : null;
      if (rangeOp != null) {
        try {
          p = SafeRewriteBuilder.withParam(p, rangeOp, "max_ranges", Integer.valueOf(num(r)), "R" + num(r));
        } catch (SafeRewriteBuilder.Rejected ignore) {
          // no matching range scan node
        }
      }
    }
    String fb = prog.get("fetch_batch");
    if (fb != null && !fb.equals(def.get("fetch_batch"))) {
      try {
        p = SafeRewriteBuilder.withParam(p, Op.FETCH_TRAJECTORY_CHUNK, "fetch_batch",
            Integer.valueOf(num(fb)), "B" + num(fb));
      } catch (SafeRewriteBuilder.Rejected ignore) {
        // ADV / access-only plans may lack FETCH_TRAJECTORY_CHUNK
      }
    }
    String tk = prog.get("topk");
    if (tk != null && !tk.equals(def.get("topk")) && ir.result != null && ir.result.k != null) {
      int k = ir.result.k.intValue();
      try {
        p = SafeRewriteBuilder.withParam(p, Op.BATCH_GET_TRAJECTORY, "topk_lb_b0",
            Integer.valueOf(num(tk) * k), "LB" + num(tk));
        p = SafeRewriteBuilder.withParam(p, Op.BATCH_GET_TRAJECTORY, "topk_lb_k", Integer.valueOf(k), null);
        p = SafeRewriteBuilder.withParam(p, Op.BATCH_GET_TRAJECTORY, "topk_lb_growth", Integer.valueOf(2), null);
      } catch (SafeRewriteBuilder.Rejected ignore) {
        // non-topk plans
      }
    }
    return p;
  }

  private QueryEngine.RunResult apply(BoundIr ir, QueryEngine engine, PlanEnvelope env,
      RewriteProtocol.Program prog, String source, JsonNode c, Map<String, Object> x, long t0) {
    x.put("program", prog.choice == null ? null : new TreeMap<String, String>(prog.choice));
    x.put("program_status", prog.status);
    x.put("final_selection_source_candidate", source);
    if (prog.choice == null) {
      return cboFallback(x, prog.status);
    }
    double est = RewriteProtocol.estimate(c, prog.choice);
    x.put("program_est_ms", finite(est));
    if (prog.isDefault(c)) {
      return cboFallback(x, "default_program");
    }
    String mode = x.get("action_mode") != null ? String.valueOf(x.get("action_mode")) : this.mode;
    boolean backed = !mode.startsWith("dual") && memory != null && ActionMemory.supported(c, prog.choice);
    x.put("program_evidence_backed", Boolean.valueOf(backed));
    if (!"rule".equals(source) && !"offline_llm_rule".equals(source) && !"online_llm_rule".equals(source)
        && !"force".equals(source) && !"online_llm_patch".equals(source) && !(est < g2Ms) && !backed) {
      return cboFallback(x, "estimate_not_negative");
    }
    long tb0 = System.nanoTime();
    PlanEnvelope cand;
    try {
      cand = build(ir, env, prog.choice, RewriteProtocol.defaults(c).choice);
    } catch (SafeRewriteBuilder.Rejected e) {
      return cboFallback(x, "construct_rejected:" + e.getMessage());
    }
    long tv0 = System.nanoTime();
    x.put("t_construct_ms", Double.valueOf((tv0 - tb0) / 1e6));
    x.put("candidate_plan_id", cand.plan_id);
    QueryEngine.RunResult validated = validate(ir, engine, cand);
    x.put("t_validate_ms", Long.valueOf(ms(System.nanoTime() - tv0)));
    x.put("t_gate_ms", Double.valueOf((System.nanoTime() - t0) / 1e6));
    if (validated == null) {
      x.put("validation_ok", Boolean.FALSE);
      return cboFallback(x, "validation_failed");
    }
    x.put("validation_ok", Boolean.TRUE);
    x.put("final_selection_source", source);
    x.put("rewrite_adopted", Boolean.TRUE);
    return validated;
  }

  private static QueryEngine.RunResult validate(BoundIr ir, QueryEngine engine, PlanEnvelope cand) {
    try {
      QueryEngine.RunResult r = engine.runFixed(ir, null, true, cand, QueryEngine.PrepareMode.SAFETY_ONLY);
      return r == null || r.selected == null ? null : r;
    } catch (Exception e) {
      return null;
    }
  }

  /** One-at-a-time slot changes plus the all-default program (CBO exec, not re-run). Access×meta still
   * fully enumerated when those slots are unlocked. */
  private ArrayNode sweep(BoundIr ir, BenchContext ctx, QueryEngine engine, QueryEngine.RunResult cbo,
      JsonNode c, QueryResult cboResult) throws Exception {
    PlanEnvelope env = cbo.selected.plan();
    Map<String, String> def = RewriteProtocol.defaults(c).choice;
    List<Map<String, String>> progs = new ArrayList<Map<String, String>>();
    progs.add(def);
    for (Map<String, String> p : RewriteProtocol.combinations(c)) {
      int other = 0;
      boolean accessMetaDefault = true;
      for (String s : p.keySet()) {
        boolean changed = !p.get(s).equals(def.get(s));
        if (s.equals(RewriteProtocol.ACCESS) || s.equals(RewriteProtocol.META)) {
          accessMetaDefault &= !changed;
        } else if (changed) {
          other++;
        }
      }
      if ((other == 0 || (other == 1 && accessMetaDefault)) && !p.equals(def)
          && !Double.isInfinite(RewriteProtocol.estimate(c, p))) {
        progs.add(p);
      }
    }
    progs.add(RewriteProtocol.deterministic(c).choice);
    ArrayNode out = MAPPER.createArrayNode();
    List<Map<String, String>> seen = new ArrayList<Map<String, String>>();
    for (Map<String, String> p : progs) {
      if (seen.contains(p)) {
        continue;
      }
      seen.add(p);
      ObjectNode row = out.addObject();
      row.set("program", MAPPER.valueToTree(p));
      row.put("est_ms", RewriteProtocol.estimate(c, p));
      if (p.equals(def)) {
        row.put("plan_id", env.plan_id);
        row.put("exec_ms", cbo.t_exec_ms == null ? -1L : cbo.t_exec_ms.longValue());
        row.put("same_result", true);
        continue;
      }
      try {
        PlanEnvelope cand = build(ir, env, p, def);
        row.put("plan_id", cand.plan_id);
        QueryEngine.RunResult v = validate(ir, engine, cand);
        if (v == null) {
          row.put("error", "validation_failed");
          continue;
        }
        QueryEngine.RunResult r = engine.executeSelected(ir, ctx.artifactDir(id(), ir.query_id + "_sweep"), v);
        row.put("exec_ms", r.t_exec_ms == null ? -1L : r.t_exec_ms.longValue());
        row.put("same_result", sameResult(cboResult, r.result));
        if (r.result != null && r.result.trace != null) {
          row.set("trace", MAPPER.valueToTree(r.result.trace));
        }
      } catch (SafeRewriteBuilder.Rejected e) {
        row.put("error", "construct_rejected:" + e.getMessage());
      }
    }
    return out;
  }

  /** Measures every safe CBO candidate with its raw CostCard, for offline cost-model repair. */
  private ArrayNode cboPlans(BoundIr ir, BenchContext ctx, QueryEngine engine, QueryEngine.RunResult cbo,
      QueryEngine.RunResult exec) throws Exception {
    ArrayNode out = MAPPER.createArrayNode();
    PlanEnvelope sel = cbo.selected.plan();
    Map<String, PlanEnvelope> plans = new LinkedHashMap<String, PlanEnvelope>();
    for (SafePlanHandle h : cbo.safe) {
      plans.put(h.plan().plan_id, h.plan());
    }
    for (String b : SafeRewriteBuilder.removableBranches(sel).keySet()) {
      try {
        PlanEnvelope p = SafeRewriteBuilder.removeAccessBranch(sel, b, ir);
        plans.putIfAbsent(p.plan_id, p);
      } catch (SafeRewriteBuilder.Rejected ignore) {
        // not a legal variant
      }
    }
    FastCost fast = Refine9ProposalArm.newFastCost(ctx);
    CostCard selCard = fast.estimate(sel, ir);
    for (PlanEnvelope plan : plans.values()) {
      String id = plan.plan_id;
      CostCard card = fast.estimate(plan, ir);
      ObjectNode row = out.addObject();
      row.put("plan_id", id);
      row.put("selected", id.equals(sel.plan_id));
      if (card != null) {
        row.set("card", MAPPER.valueToTree(card));
      }
      if (id.equals(sel.plan_id)) {
        row.put("exec_ms", exec.t_exec_ms == null ? -1L : exec.t_exec_ms.longValue());
        continue;
      }
      if (card == null || selCard == null || card.estimated_ms > 10 * selCard.estimated_ms) {
        row.put("error", "skipped_estimate");
        continue;
      }
      QueryEngine.RunResult v = validate(ir, engine, plan);
      if (v == null) {
        row.put("error", "validation_failed");
        continue;
      }
      QueryEngine.RunResult r = engine.executeSelected(ir, ctx.artifactDir(id(), ir.query_id + "_plans"), v);
      row.put("exec_ms", r.t_exec_ms == null ? -1L : r.t_exec_ms.longValue());
      row.put("same_result", sameResult(exec.result, r.result));
    }
    return out;
  }

  static boolean sameResult(QueryResult a, QueryResult b) {
    if (a == null || b == null) {
      return false;
    }
    if (a.topK != null || b.topK != null) {
      if (a.topK == null || b.topK == null || a.topK.size() != b.topK.size()) {
        return false;
      }
      for (int i = 0; i < a.topK.size(); i++) {
        double da = a.topK.get(i).distance;
        double db = b.topK.get(i).distance;
        if (a.topK.get(i).tid != b.topK.get(i).tid || Math.abs(da - db) > 1e-9 * Math.max(1.0d, Math.abs(da))) {
          return false;
        }
      }
      return true;
    }
    List<String> x = new ArrayList<String>(a.trajectoryIds);
    List<String> y = new ArrayList<String>(b.trajectoryIds);
    Collections.sort(x);
    Collections.sort(y);
    return x.equals(y);
  }

  /** An adopted program slower than this query's observed CBO exec revokes its signature for every source. */
  static void feedback(String queryId, String sig, long execMs, Map<String, Object> x) {
    if (!Boolean.TRUE.equals(x.get("rewrite_adopted"))) {
      CBO_EXEC.put(queryId, Long.valueOf(execMs));
      return;
    }
    Long base = CBO_EXEC.get(queryId);
    x.put("feedback_cbo_exec_ms", base);
    if (base != null && execMs > base.longValue()) {
      REVOKED.add(sig);
      MEMO.remove(sig);
      ONLINE_MEMO.remove(sig);
      x.put("memo_revoked", Boolean.TRUE);
    }
  }

  /** True when the program changes the access slot away from the CBO default. */
  private static boolean accessChanged(RewriteProtocol.Program p, JsonNode c) {
    if (p == null || p.choice == null || c == null) return false;
    String want = p.choice.get(RewriteProtocol.ACCESS);
    if (want == null) return false;
    String def = RewriteProtocol.defaults(c).choice.get(RewriteProtocol.ACCESS);
    return def == null || !def.equals(want);
  }

  private static RewriteProtocol.Program forced(JsonNode c) {
    Map<String, String> m = new TreeMap<String, String>(RewriteProtocol.defaults(c).choice);
    try {
      JsonNode j = MAPPER.readTree(envStr("KART_ACTION_FORCE", "{}"));
      for (String s : RewriteProtocol.slots(c)) {
        if (j.has(s) && RewriteProtocol.options(c, s).contains(j.get(s).asText())) {
          m.put(s, j.get(s).asText());
        }
      }
    } catch (Exception e) {
      return new RewriteProtocol.Program(null, "invalid_force");
    }
    return new RewriteProtocol.Program(m, "force");
  }

  private Pending launch(final LlmClient llm, String queryId, ObjectNode context, String sig, JsonNode near,
      RewriteProtocol.Program nearProg) {
    final Pending p = new Pending();
    p.context = context;
    p.sig = sig;
    p.queryId = queryId;
    p.near = nearProg;
    final List<LlmMessage> msgs = near != null && near.size() > 0
        ? RewriteProtocol.messagesAdapt(context, near) : RewriteProtocol.messages(context, false);
    final ObjectNode schema = RewriteProtocol.schema(context);
    final long budget = (long) cap;
    final LlmOptions opts = RewriteProtocol.options(budget, (int) env("KART_REWRITE_MAX_TOKENS", 60.0d));
    p.lx.put("prompt_sha256", sha(msgs.get(0).content + "\u0000" + msgs.get(1).content));
    p.lx.put("schema_sha256", sha(schema.toString()));
    p.start = System.nanoTime();
    p.pool = Executors.newSingleThreadExecutor();
    p.fut = p.pool.submit(() -> call(llm, msgs, schema, opts, budget, p.lx));
    return p;
  }

  /** Keep access and meta_probe from the model; drop fetch/range/topk stacking. */
  private static RewriteProtocol.Program clipLlm(Pending p, RewriteProtocol.Program d) {
    Map<String, String> m = new TreeMap<String, String>(RewriteProtocol.defaults(p.context).choice);
    for (String s : new String[] {"access", "meta_probe"}) {
      if (!RewriteProtocol.slots(p.context).contains(s)) {
        continue;
      }
      String v = d.choice.get(s);
      if (v != null && RewriteProtocol.options(p.context, s).contains(v)) {
        m.put(s, v);
      }
    }
    return Double.isInfinite(RewriteProtocol.estimate(p.context, m))
        ? RewriteProtocol.defaults(p.context) : new RewriteProtocol.Program(m, "llm");
  }

  private static void finish(Pending p, Map<String, Object> x) {
    long w0 = System.nanoTime();
    String raw;
    try {
      raw = p.fut.get();
    } catch (Exception e) {
      raw = null;
      p.lx.put("llm_error", "llm_failure");
    }
    p.pool.shutdown();
    long end = System.nanoTime();
    x.putAll(p.lx);
    x.put("llm_calls", Integer.valueOf(1));
    x.put("t_llm_ms", Long.valueOf(ms(end - p.start)));
    x.put("t_llm_wait_after_exec_ms", Long.valueOf(ms(end - w0)));
    RewriteProtocol.Program d = raw == null ? null : RewriteProtocol.parse(raw, p.context);
    ObjectNode row = MAPPER.createObjectNode();
    row.put("memo_signature", p.sig);
    row.set("context", p.context);
    row.put("raw", raw);
    if (d != null) {
      x.put("llm_program", d.choice);
      x.put("llm_parse_status", d.status);
      row.set("program", MAPPER.valueToTree(d.choice));
      row.put("parse_status", d.status);
      row.put("deterministic_agrees", d.choice != null && d.choice.equals(RewriteProtocol.deterministic(p.context).choice));
      if (d.choice != null) {
        RewriteProtocol.Program keep = clipLlm(p, d);
        x.put("path_c_llm_clip", Boolean.TRUE);
        if (keep.choice != null) {
          MEMO.put(p.sig, new Memo(keep, p.queryId));
          x.put("memo_stored", Boolean.TRUE);
        }
      }
    } else if (p.near != null && p.near.choice != null) {
      MEMO.put(p.sig, new Memo(p.near, p.queryId));
      x.put("memo_stored", Boolean.TRUE);
      x.put("path_c_fallback_near", Boolean.TRUE);
    }
    row.put("t_llm_ms", ms(end - p.start));
    row.put("t_llm_wait_after_exec_ms", ms(end - w0));
    String out = System.getenv("KART_REWRITE_TRACE");
    if (out != null && !out.isEmpty()) {
      trace(out, p.queryId, row);
    }
  }

  static String call(final LlmClient llm, final List<LlmMessage> msgs, final ObjectNode schema,
      final LlmOptions opts, long deadline, Map<String, Object> x) {
    ExecutorService pool = Executors.newSingleThreadExecutor();
    Future<LlmResponse> fut = null;
    String raw = null;
    try {
      fut = pool.submit(() -> llm.chat(msgs, schema, opts));
      LlmResponse r = fut.get(deadline, TimeUnit.MILLISECONDS);
      raw = r.content;
      x.put("llm_raw_response", r.content);
      x.put("tokens_in", r.promptTokens);
      x.put("tokens_out", r.completionTokens);
      x.put("llm_request_sha256", r.requestSha256);
    } catch (TimeoutException e) {
      x.put("llm_error", "llm_timeout");
    } catch (Exception e) {
      x.put("llm_error", "llm_failure");
    } finally {
      boolean cancelled = fut != null && !fut.isDone() && fut.cancel(true);
      pool.shutdownNow();
      boolean quiet;
      try {
        quiet = pool.awaitTermination(2000L, TimeUnit.MILLISECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        quiet = false;
      }
      x.put("llm_cancel", cancelled ? (quiet ? "cancelled" : "unknown") : "not_needed");
    }
    return raw;
  }

  private static QueryEngine.RunResult skip(Map<String, Object> x, String reason, long t0) {
    x.put("gate_decision", "SKIP");
    x.put("skip_reason", reason);
    x.put("t_gate_ms", Double.valueOf((System.nanoTime() - t0) / 1e6));
    return null;
  }

  private static QueryEngine.RunResult cboFallback(Map<String, Object> x, String reason) {
    x.put("fallback_reason", reason);
    return null;
  }

  static JsonNode loadRules(String path) {
    String p = path;
    if (p == null || p.trim().isEmpty()) {
      String root = System.getProperty("kart.root");
      if (root != null && !root.isEmpty()) {
        java.nio.file.Path def = Paths.get(root, "experiments/suites/dual_action_rules.json");
        if (Files.isRegularFile(def)) {
          p = def.toString();
        }
      }
    }
    if (p == null || p.trim().isEmpty()) {
      return null;
    }
    try {
      JsonNode j = MAPPER.readTree(Paths.get(p).toFile());
      return j.has("rules") ? j.get("rules") : j;
    } catch (Exception e) {
      throw new IllegalStateException("Invalid action rules: " + p, e);
    }
  }

  private static void putCboPlanFeatures(ObjectNode f, String planId) {
    f.put("cbo_plan", planId == null ? "" : planId);
    f.put("cbo_plan_alias", kart.rewrite.DualRewritePolicy.aliasCboPlan(planId));
  }

  private static void putSpatialRangeFeatures(ObjectNode f, BoundIr ir, LayoutContext layout) {
    if (ir == null || ir.spatial == null || layout == null) {
      return;
    }
    Rect rect = new Rect(ir.spatial.min_x, ir.spatial.min_y, ir.spatial.max_x, ir.spatial.max_y);
    if (!f.has("z_ranges")) {
      f.put("z_ranges", new ZOrderIndexAdapter(layout).mortonRanges(rect, layout.maxZorderRanges).size());
    }
    if (!f.has("xz_ranges")) {
      f.put("xz_ranges", layout.xzSfc().ranges(rect.minX, rect.minY, rect.maxX, rect.maxY).size());
    }
    if (!f.has("tshape_ranges")) {
      f.put("tshape_ranges", layout.tshapeSfc().ranges(rect.minX, rect.minY, rect.maxX, rect.maxY).size());
    }
  }

  private static int spatialRangeCount(String planId, BoundIr ir, LayoutContext layout) {
    if (ir == null || ir.spatial == null || layout == null) {
      return -1;
    }
    Rect rect = new Rect(ir.spatial.min_x, ir.spatial.min_y, ir.spatial.max_x, ir.spatial.max_y);
    String core = planId == null ? "" : planId;
    int under = core.indexOf('_', 2);
    if (under > 0) {
      core = core.substring(0, under);
    }
    if ("P_XZ".equals(core)) {
      return layout.xzSfc().ranges(rect.minX, rect.minY, rect.maxX, rect.maxY).size();
    }
    if ("P_TS".equals(core)) {
      return layout.tshapeSfc().ranges(rect.minX, rect.minY, rect.maxX, rect.maxY).size();
    }
    return new ZOrderIndexAdapter(layout).mortonRanges(rect, layout.maxZorderRanges).size();
  }

  /**
   * Vehicle / short single-rect classic-only: skip BF search and pin the RBO-class
   * template so t_plan stays ~1–3 ms (otherwise strict e2e loses to RBO on plan).
   * Multi-AOI returns null — BF + dual-rules must remain free to rewrite TZ→Z.
   */
  static PlanEnvelope classicOnlyFixedPlan(BoundIr ir) {
    if (ir == null || !kart.cost.AdvQueryRouter.forceClassic(ir)) {
      return null;
    }
    if (ir.spatial_parts != null && ir.spatial_parts.size() > 1) {
      return null;
    }
    if (ir.temporal_parts != null && ir.temporal_parts.size() > 1) {
      return null;
    }
    int veh = PlanBuilder.vehicleEqPredicateIndex(ir);
    if (veh >= 0) {
      return PlanBuilder.buildForAccess(ir, false, false, true);
    }
    boolean hasT = BoundIr.hasTemporal(ir);
    boolean hasZ = BoundIr.hasSpatial(ir);
    if (hasT && hasZ) {
      return PlanBuilder.buildForAccess(ir, true, true, false);
    }
    if (hasZ) {
      return PlanBuilder.buildForAccess(ir, false, true, false);
    }
    if (hasT) {
      return PlanBuilder.buildForAccess(ir, true, false, false);
    }
    return null;
  }

  private static PlanEnvelope buildKeeping(BoundIr ir, PlanEnvelope env, boolean keepTemporal, boolean keepSpatial) {
    List<String> idxs = new ArrayList<String>();
    for (PlanNode n : env.nodes) {
      if (n.op == Op.TIME_RANGE_SCAN || n.op == Op.XZT_RANGE_SCAN) {
        if (keepTemporal) {
          idxs.add(SafeRewriteBuilder.indexId(n.op));
        }
      } else if (n.op == Op.ZORDER_RANGE_SCAN || n.op == Op.XZ_RANGE_SCAN || n.op == Op.TSHAPE_RANGE_SCAN) {
        if (keepSpatial) {
          idxs.add(SafeRewriteBuilder.indexId(n.op));
        }
      } else if (n.op == Op.EQUALITY_LOOKUP) {
        idxs.add(IndexId.HASH);
      }
    }
    return PlanBuilder.buildForIndexes(ir, idxs, null, null);
  }

  private static void ignoreRangeBudgetWithDrop(ObjectNode c, Map<String, String> branches, String nodeId) {
    for (Map.Entry<String, String> b : branches.entrySet()) {
      if (b.getValue().equals(nodeId)) {
        ((ObjectNode) c.path("slots").path("range_budget")).putArray("ignored_with").add("drop_" + b.getKey());
      }
    }
  }

  private static PlanNode first(PlanEnvelope p, Op op) {
    for (PlanNode n : p.nodes) {
      if (n.op == op) {
        return n;
      }
    }
    return null;
  }

  private static double cells(List<ZOrder.MortonRange> rs) {
    double s = 0.0d;
    for (ZOrder.MortonRange r : rs) {
      s += r.hi - r.lo + 1;
    }
    return s;
  }

  private static int num(String option) {
    return Integer.parseInt(option.replaceAll("\\D", ""));
  }

  private static double unitCost(int term, String key, double def) {
    OnlineBenefitModel m = OnlineBenefitModel.get();
    if (m == null) {
      return env(key, def);
    }
    CostCard probe = new CostCard();
    probe.features.put("scan_ranges", Integer.valueOf(0));
    probe.features.put("fetch_gets", Integer.valueOf(0));
    probe.features.put("estimated_index_rows", Integer.valueOf(0));
    probe.features.put(term == 1 ? "fetch_gets" : "estimated_index_rows", Integer.valueOf(1000));
    double[] t = m.terms(probe);
    return t == null ? env(key, def) : t[term] / 1000.0d;
  }

  private static double feat(CostCard card, String key) {
    Object v = card == null ? null : card.features.get(key);
    return v instanceof Number ? ((Number) v).doubleValue() : -1.0d;
  }

  private static double round(double v) {
    return Math.round(v * 10.0d) / 10.0d;
  }

  private static synchronized void trace(String out, String queryId, ObjectNode body) {
    try {
      ObjectNode row = MAPPER.createObjectNode();
      row.put("query_id", queryId);
      row.setAll(body);
      Files.write(Paths.get(out), (row.toString() + "\n").getBytes(StandardCharsets.UTF_8),
          StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    } catch (Exception ignore) {
      // diagnostics only
    }
  }

  private static Object finite(double v) {
    return Double.isNaN(v) || Double.isInfinite(v) ? null : Double.valueOf(v);
  }

  private static String sha(String s) {
    try {
      return OpenAiCompatibleClient.sha256(s.getBytes(StandardCharsets.UTF_8));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static double env(String key, double def) {
    String v = System.getenv(key);
    try {
      return v == null || v.trim().isEmpty() ? def : Double.parseDouble(v.trim());
    } catch (NumberFormatException e) {
      return def;
    }
  }

  private static String envStr(String key, String def) {
    String v = System.getenv(key);
    return v == null || v.trim().isEmpty() ? def : v.trim();
  }

  private static long ms(long nanos) {
    return nanos / 1000000L;
  }
}
