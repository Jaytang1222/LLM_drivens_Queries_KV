package kart.bench;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import kart.catalog.CatalogStore;
import kart.catalog.Manifest;
import kart.catalog.StatsSnapshot;
import kart.compile.LayoutContext;
import kart.config.AppConfig;
import kart.exec.ExecutionTrace;
import kart.exec.HBaseBackend;
import kart.exec.QueryResult;
import kart.ir.BoundIr;
import kart.llm.LlmClient;
import kart.llm.OpenAiCompatibleClient;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/** Expand suite × arms/factors/grid and collect trials (E1/E2/E3 + ablation/param). */
public final class SuiteRunner {

  private static final ObjectMapper MAPPER = new ObjectMapper()
      .enable(SerializationFeature.INDENT_OUTPUT);

  /** Formal BoundIR cardinality for {@code bound_ir_v1.json} (spec / audit gate). */
  public static final int FORMAL_BOUND_IR_COUNT = 65;

  /** E1/E2/E3 arm rotation: {@code shift = (trial-1 + queryIndex) % nArm}. */
  static int armShift(int trial1Based, int queryIndex, int nArm) {
    if (nArm <= 0) {
      return 0;
    }
    int t = Math.max(1, trial1Based) - 1;
    int q = Math.max(0, queryIndex);
    return (t + q) % nArm;
  }

  static int armIndex(int position, int shift, int nArm) {
    if (nArm <= 0) {
      return 0;
    }
    int pos = Math.max(0, position);
    int sh = Math.max(0, shift);
    return (pos + sh) % nArm;
  }

  public static final class Options {
    public Path root;
    public Path suitePath;
    public String runId;
    public Set<String> armFilter = new LinkedHashSet<String>();
    public Set<String> factorFilter = new LinkedHashSet<String>();
    public boolean allowUnsafe;
    /** cold | warm — recorded in meta; cold is primary. */
    public String cache = "cold";
    /** Optional override for suite.limit. */
    public Integer limit;
    public Path workloadOverride;
    public Path oracleOverride;
    public boolean keepArtifacts;
    /**
     * Ablation: when {@code --factor} names a safe leave-one-out, still include {@code full}
     * so paired deltas are defined. Risk-only filters ({@code no_coverage}) stay unpaired.
     */
    public boolean noPairFull;
    /** Optional override for suite.trials. */
    public Integer trials;
  }

  public static final class Result {
    public int exitCode;
    public Path resultDir;
    public int trials;
    public int oracleFail;
  }

  public Result run(Options opt) throws Exception {
    SuiteSpec suite = SuiteSpec.load(opt.suitePath);
    Path root = opt.root;
    Path resultDir = root.resolve("experiments/results").resolve(opt.runId);
    Files.createDirectories(resultDir);

    Path workloadPath = opt.workloadOverride != null
        ? (opt.workloadOverride.isAbsolute() ? opt.workloadOverride : root.resolve(opt.workloadOverride))
        : resolve(root, suite.workload);
    Path oraclePath = opt.oracleOverride != null
        ? (opt.oracleOverride.isAbsolute() ? opt.oracleOverride : root.resolve(opt.oracleOverride))
        : resolve(root, suite.oracle);
    if (opt.workloadOverride != null) {
      suite.workload = workloadPath.toString();
    }
    if (opt.oracleOverride != null) {
      suite.oracle = oraclePath.toString();
    }
    Path catalogPath = resolve(root, suite.catalog);

    boolean hasParse = false;
    boolean hasBound = false;
    for (String st : suite.stages) {
      if ("parse".equalsIgnoreCase(st)) {
        hasParse = true;
      } else {
        hasBound = true;
      }
    }

    Integer qLimit = opt.limit != null ? opt.limit : suite.limit;
    int trialCount = opt.trials != null ? opt.trials.intValue() : suite.trials;
    if (trialCount < 1) {
      throw new IllegalArgumentException("trials must be >= 1");
    }
    List<NlItem> nlItems = hasParse ? loadNlItems(workloadPath, qLimit)
        : Collections.<NlItem>emptyList();
    String workloadSeed = readWorkloadSeed(workloadPath);
    if (hasParse && !nlItems.isEmpty()) {
      nlItems = shuffleDeterministic(nlItems, workloadSeed);
    }
    List<BoundIr> queries = hasBound ? loadQueries(workloadPath, qLimit)
        : Collections.<BoundIr>emptyList();
    Map<String, OracleChecker.Answer> oracle = Collections.emptyMap();
    if (hasBound && Files.isRegularFile(oraclePath)) {
      try {
        oracle = OracleChecker.load(oraclePath);
      } catch (Exception e) {
        System.err.println("BENCH warn: oracle load failed: " + e.getMessage());
      }
    }

    CatalogStore store = new CatalogStore(catalogPath);
    Manifest manifest = store.loadManifest(suite.manifest).orElse(null);
    if (manifest == null) {
      throw new IllegalStateException("manifest not found: " + suite.manifest);
    }
    LayoutContext layout = LayoutContext.from(manifest);
    StatsSnapshot stats = store.loadStats(suite.manifest).orElse(null);
    AppConfig cfg = AppConfig.load(root);
    AppConfig.PlannerConfig basePlanner = cfg.planner().copy();

    ArmRegistry registry = new ArmRegistry(root);
    List<RunCell> cells = expandCells(suite, registry, opt, hasParse);
    List<String> selectedArms = new ArrayList<String>();
    for (RunCell c : cells) {
      selectedArms.add(c.armId);
    }
    ThirdPartyAudit.requireEntriesForArms(root, selectedArms);

    boolean needLlm = hasParse;
    for (RunCell c : cells) {
      if ("llm".equals(c.armId) || "llm_direct".equals(c.armId)
          || "kart".equals(c.armId) || "llmopt".equals(c.armId)
          || "cbo-llm-proposal".equals(c.armId)
          || "cbo-llm-proposal-cached".equals(c.armId)
          || "kart-conditional-llm".equals(c.armId)
          || "fixed-plan".equals(c.armId)) {
        needLlm = true;
        break;
      }
    }
    LlmClient llm = null;
    if (needLlm) {
      String key = System.getenv("LLM_API_KEY");
      if (key == null || key.trim().isEmpty()) {
        key = System.getenv("OPENAI_API_KEY");
      }
      if (key == null || key.trim().isEmpty()) {
        throw new IllegalStateException(
            "LLM_API_KEY (or OPENAI_API_KEY) is required for selected benchmark arms; "
                + "refusing to silently fall back to RulePolicy");
      }
      try {
        llm = new OpenAiCompatibleClient();
      } catch (Exception e) {
        throw new IllegalStateException("LLM configuration required for selected benchmark arms: "
            + e.getMessage(), e);
      }
    }

    Path site = root.resolve("config/hbase/hbase-site.xml");
    HBaseBackend kv = new HBaseBackend(HBaseBackend.open(site));

    CacheProtocol cacheProtocol = CacheProtocol.from(opt.cache);
    if ("cold".equals(cacheProtocol.mode) && trialCount > 1) {
      System.err.println("BENCH warn: cache=cold with trials=" + trialCount
          + " is mixed-cache smoke; formal first-run uses --cache cold --trials 1");
    }
    Map<String, Map<String, Object>> catalog = ThirdPartyAudit.loadCatalog(workloadPath);
    Path hbaseEvidence = root.resolve("experiments/results/hbase_version_evidence.txt");
    Map<String, Object> hbaseEnv = HBaseEnvProbe.collect(kv, hbaseEvidence);

    writeMeta(resultDir, opt, suite, cells, queries.size(), nlItems.size(), cfg,
        workloadPath, oraclePath, trialCount, cacheProtocol, hbaseEnv, nlItems);

    List<Map<String, Object>> allTrials = new ArrayList<Map<String, Object>>();
    int oracleFail = 0;

    Path parsePath = resultDir.resolve("parse.jsonl");
    Path planPath = resultDir.resolve("plan.jsonl");
    Path e2ePath = resultDir.resolve("e2e.jsonl");
    Path ablationPath = resultDir.resolve("ablation.jsonl");

    try {
      TrialWriter parseWriter = hasParse ? new TrialWriter(parsePath, true) : null;
      TrialWriter planWriter = null;
      TrialWriter e2eWriter = null;
      TrialWriter ablationWriter = null;
      try {
        boolean suiteHasParse = false;
        for (String stage : suite.stages) {
          if ("parse".equalsIgnoreCase(stage)) {
            suiteHasParse = true;
            break;
          }
        }
        List<RunCell> parseCells = new ArrayList<RunCell>();
        List<ParseArm> parseArms = new ArrayList<ParseArm>();
        List<BenchContext> parseCtxs = new ArrayList<BenchContext>();
        if (suiteHasParse) {
          for (RunCell cell : cells) {
            if (cell.unsafe && !opt.allowUnsafe) {
              System.out.println("BENCH skip unsafe cell " + cell.label);
              continue;
            }
            ParseArm parm = registry.resolveParse(cell.armId);
            if (parm == null) {
              System.err.println("BENCH unknown parse arm: " + cell.armId);
              continue;
            }
            AppConfig.PlannerConfig planner = PlannerOverlay.apply(basePlanner, cell.overrides, opt.root);
            parseCells.add(cell);
            parseArms.add(parm);
            parseCtxs.add(newCtx(opt, suite, "parse", true, suite.require_oracle,
                resultDir, manifest, layout, stats, planner, kv, llm, oracle, queries, cell));
          }
        }
        int nParse = parseCells.size();
        for (int trial = 1; trial <= trialCount && nParse > 0; trial++) {
          for (int qi = 0; qi < nlItems.size(); qi++) {
            NlItem item = nlItems.get(qi);
            int shift = armShift(trial, qi, nParse);
            for (int pos = 0; pos < nParse; pos++) {
              int ai = armIndex(pos, shift, nParse);
              RunCell cell = parseCells.get(ai);
              ParseArm parm = parseArms.get(ai);
              BenchContext ctx = parseCtxs.get(ai);
                  Map<String, Object> row = new LinkedHashMap<String, Object>();
                  row.put("run_id", opt.runId);
                  row.put("query_id", item.query_id);
                  row.put("arm", cell.label);
                  row.put("trial", Integer.valueOf(trial));
                  row.put("arm_position", Integer.valueOf(pos));
                  row.put("arm_order_shift", Integer.valueOf(shift));
                  row.put("cache", opt.cache == null ? "cold" : opt.cache);

                  ParseTrialResult tr;
                  try {
                    tr = parm.parse(item, ctx);
                  } catch (Exception ex) {
                    tr = new ParseTrialResult();
                    tr.error = ex.toString();
                    tr.ok = false;
                    tr.ok_ex = Boolean.FALSE;
                    tr.ok_ir_valid = Boolean.FALSE;
                  }
                  row.put("ok_ex", tr.ok_ex);
                  row.put("ok_ir_valid", tr.ok_ir_valid);
                  row.put("reject_expected", tr.reject_expected);
                  row.put("reject_actual", tr.reject_actual);
                  row.put("t_parse_ms", tr.t_parse_ms);
                  row.put("t_parse_total_ms", tr.t_parse_total_ms != null
                      ? tr.t_parse_total_ms : tr.t_parse_ms);
                  row.put("t_parse_model_ms", tr.t_parse_model_ms);
                  row.put("t_adapter_startup_ms", tr.t_adapter_startup_ms);
                  row.put("llm_calls", tr.llm_calls);
                  row.put("tokens_in", tr.tokens_in);
                  row.put("tokens_out", tr.tokens_out);
                  row.put("error", tr.error);
                  row.put("stage", "parse");
                  row.put("clarify_expected", tr.clarify_expected);
                  row.put("clarify_actual", tr.clarify_actual);
                  if (item.layer != null) {
                    row.put("layer", item.layer);
                  }
                  String qClass = item.clarify_expected ? "clarify"
                      : (item.reject_expected ? "reject" : "supported");
                  row.put("query_class", qClass);
                  if (Boolean.TRUE.equals(tr.extras.get("infrastructure_failure"))) {
                    row.put("fail_class", "infrastructure");
                  } else if (Boolean.TRUE.equals(tr.extras.get("early_reject"))) {
                    row.put("fail_class", Boolean.TRUE.equals(tr.ok_ex) ? "ok" : "method");
                    row.put("reject_source", "shared_early_gate");
                  }
                  if (tr.extras != null) {
                    for (Map.Entry<String, Object> extra : tr.extras.entrySet()) {
                      if (extra.getKey() != null && extra.getValue() != null
                          && !row.containsKey(extra.getKey())) {
                        row.put(extra.getKey(), extra.getValue());
                      }
                    }
                  }

                  boolean failGate = suite.require_oracle
                      && (tr.ok_ex == null || !tr.ok_ex.booleanValue());
                  if (failGate) {
                    oracleFail++;
                  }
                  if (parseWriter != null) {
                    parseWriter.write(row);
                  }
                  allTrials.add(row);
                  System.out.println("BENCH parse cell=" + cell.label
                      + " arm_position=" + pos
                      + " q=" + item.query_id
                      + " ok_ex=" + tr.ok_ex + " t_parse_ms=" + tr.t_parse_ms
                      + (tr.error != null ? (" err=" + tr.error) : ""));
            }
          }
        }

        for (String stage : suite.stages) {
          if ("parse".equalsIgnoreCase(stage)) {
            continue;
          }
          boolean planOnly = "plan".equalsIgnoreCase(stage);
          boolean requireOracle = suite.require_oracle && !planOnly;
          List<RunCell> runnable = new ArrayList<RunCell>();
          List<Arm> arms = new ArrayList<Arm>();
          List<BenchContext> ctxs = new ArrayList<BenchContext>();
          List<BenchContext> warmCtxs = new ArrayList<BenchContext>();
          for (RunCell cell : cells) {
            if (cell.unsafe && !opt.allowUnsafe) {
              continue;
            }
            Arm arm = registry.resolve(cell.armId);
            if (arm == null) {
              System.err.println("BENCH unknown arm: " + cell.armId);
              continue;
            }
            AppConfig.PlannerConfig planner = PlannerOverlay.apply(basePlanner, cell.overrides, opt.root);
            runnable.add(cell);
            arms.add(arm);
            ctxs.add(newCtx(opt, suite, stage, planOnly, requireOracle,
                resultDir, manifest, layout, stats, planner, kv, llm, oracle, queries, cell));
            warmCtxs.add(newCtxKeepArtifacts(opt, suite, stage, planOnly, requireOracle,
                resultDir, manifest, layout, stats, planner, kv, llm, oracle, queries, cell, false));
          }
          if (runnable.isEmpty()) {
            continue;
          }
          if (planOnly && planWriter == null) {
            planWriter = new TrialWriter(planPath, true);
          }
          if (!planOnly && e2eWriter == null) {
            e2eWriter = new TrialWriter(e2ePath, true);
            if (isAblation(suite)) {
              ablationWriter = new TrialWriter(ablationPath, true);
            }
          }
          int nArm = runnable.size();
          cacheProtocol.armOrderPolicy = "rotate_by_query_and_trial";
          cacheProtocol.warmupArmOrderPolicy = (!planOnly && cacheProtocol.warmupPasses > 0)
              ? "rotate_by_query_and_warmup_pass"
              : "n/a";
          if (!planOnly && cacheProtocol.warmupPasses > 0) {
            for (int w = 0; w < cacheProtocol.warmupPasses; w++) {
              for (int qi = 0; qi < queries.size(); qi++) {
                BoundIr ir = queries.get(qi);
                int shift = armShift(w + 1, qi, nArm);
                for (int pos = 0; pos < nArm; pos++) {
                  int ai = armIndex(pos, shift, nArm);
                  try {
                    arms.get(ai).run(ir, warmCtxs.get(ai));
                  } catch (Exception ignore) {
                    // discarded untimed warmup
                  }
                }
              }
            }
          }
          for (int trial = 1; trial <= trialCount; trial++) {
            for (int qi = 0; qi < queries.size(); qi++) {
              BoundIr ir = queries.get(qi);
              int shift = armShift(trial, qi, nArm);
              for (int pos = 0; pos < nArm; pos++) {
                int ai = armIndex(pos, shift, nArm);
                RunCell cell = runnable.get(ai);
                Map<String, Object> row = new LinkedHashMap<String, Object>();
                row.put("run_id", opt.runId);
                row.put("query_id", ir.query_id);
                row.put("arm", cell.label);
                row.put("planner_mode", cell.armId);
                if (cell.extras.get("factor") != null) {
                  row.put("factor", cell.extras.get("factor"));
                }
                row.put("unsafe", Boolean.valueOf(cell.unsafe));
                row.put("trial", Integer.valueOf(trial));
                row.put("stage", stage);
                row.put("cache", opt.cache == null ? "cold" : opt.cache);
                row.put("warmup_passes", Integer.valueOf(planOnly ? 0 : cacheProtocol.warmupPasses));
                if (catalog.containsKey(ir.query_id)) {
                  row.putAll(catalog.get(ir.query_id));
                }
                putQueryStrata(row, ir);
                row.put("arm_position", Integer.valueOf(pos));
                row.put("arm_order_shift", Integer.valueOf(shift));
                if (!planOnly && "cold".equals(cacheProtocol.mode)) {
                  cacheProtocol.flushBeforeTrial(kv, layout);
                }
                TrialResult tr;
                try {
                  tr = arms.get(ai).run(ir, ctxs.get(ai));
                } catch (Exception ex) {
                  tr = TrialResult.fail(ex.toString());
                }
                row.put("plan_id", tr.plan_id);
                row.put("t_plan_ms", tr.t_plan_ms);
                row.put("t_exec_ms", tr.t_exec_ms);
                row.put("t_e2e_ms", tr.t_e2e_ms);
                row.put("error", tr.error);
                row.put("n_candidates", tr.run == null || tr.run.candidates == null
                    ? null : Integer.valueOf(tr.run.candidates.size()));
                row.put("n_validation_reject", tr.run == null || tr.run.rejections == null
                    ? null : Integer.valueOf(tr.run.rejections.size()));
                row.put("n_cost_cards", tr.run == null || tr.run.costCards == null
                    ? null : Integer.valueOf(tr.run.costCards.size()));
                if (planOnly && tr.run != null && tr.run.safe != null) {
                  java.util.List<String> safeIds = new java.util.ArrayList<String>();
                  for (kart.validation.SafePlanHandle handle : tr.run.safe) {
                    if (handle != null && handle.plan() != null
                        && handle.plan().plan_id != null && !safeIds.contains(handle.plan().plan_id)) {
                      safeIds.add(handle.plan().plan_id);
                    }
                  }
                  java.util.Collections.sort(safeIds);
                  row.put("safe_plan_ids", safeIds);
                }
                enrichTrace(row, tr);
                if (tr.extras != null) {
                  for (Map.Entry<String, Object> extra : tr.extras.entrySet()) {
                    if (extra.getKey() == null) {
                      continue;
                    }
                    if (extra.getValue() != null || isLlmSchemaKey(extra.getKey())) {
                      row.put(extra.getKey(), extra.getValue());
                    }
                  }
                  if (Boolean.TRUE.equals(tr.extras.get("llm_fallback"))
                      && splitsOnLlmFallback(cell.armId)) {
                    row.put("arm_requested", cell.label);
                    row.put("arm", fallbackArmName(cell.armId, cell.label, isAblation(suite)));
                  }
                }
                ensureLlmSchema(row);
                if (isAblation(suite)) {
                  AppConfig.PlannerConfig cellPlanner = ctxs.get(ai).planner;
                  if (cellPlanner != null && cellPlanner.cost != null) {
                    row.put("cost_calibrated", Boolean.valueOf(cellPlanner.cost.calibrated));
                    row.put("cost_model_version", cellPlanner.cost.model_version);
                  } else {
                    row.put("cost_calibrated", null);
                  }
                }
                row.put("cache_enforced", Boolean.valueOf(cacheProtocol.cacheEnforced()));
                row.put("cache_protocol", cacheProtocol.protocolLabel());
                Boolean okOracle;
                if (planOnly) {
                  Boolean planOk = Boolean.valueOf(tr.error == null
                      && tr.run != null && tr.run.selected != null);
                  okOracle = planOk;
                  row.put("plan_ok", planOk);
                } else if (tr.error != null && tr.queryResult == null) {
                  okOracle = Boolean.FALSE;
                } else {
                  OracleChecker.Answer ans = oracle.get(ir.query_id);
                  String mismatch = OracleChecker.compare(ans, tr.queryResult);
                  if (mismatch != null) {
                    okOracle = Boolean.FALSE;
                    if (tr.error == null) {
                      row.put("error", mismatch);
                    }
                  } else {
                    okOracle = Boolean.TRUE;
                  }
                }
                row.put("ok_oracle", okOracle);
                row.put("fail_class", failClass(tr, row));
                if (requireOracle && !okOracle.booleanValue()) {
                  oracleFail++;
                }
                if (planOnly && planWriter != null) {
                  planWriter.write(row);
                }
                if (!planOnly && e2eWriter != null) {
                  e2eWriter.write(row);
                }
                if (!planOnly && ablationWriter != null) {
                  ablationWriter.write(row);
                }
                allTrials.add(row);
                System.out.println("BENCH " + suite.id + " stage=" + stage
                    + " cell=" + cell.label + " q=" + ir.query_id
                    + " arm_position=" + pos
                    + " ok_oracle=" + okOracle
                    + " t_e2e_ms=" + tr.t_e2e_ms
                    + (tr.error != null ? (" err=" + tr.error) : ""));
              }
            }
          }
        }
      } finally {
        if (parseWriter != null) {
          parseWriter.close();
        }
        if (planWriter != null) {
          planWriter.close();
        }
        if (e2eWriter != null) {
          e2eWriter.close();
        }
        if (ablationWriter != null) {
          ablationWriter.close();
        }
      }
    } finally {
      kv.closeConnection();
    }

    writeMeta(resultDir, opt, suite, cells, queries.size(), nlItems.size(), cfg,
        workloadPath, oraclePath, trialCount, cacheProtocol, hbaseEnv, nlItems);

    SummaryMd.write(resultDir, opt.runId, allTrials, suite, cacheProtocol, trialCount);

    Result r = new Result();
    r.resultDir = resultDir;
    r.trials = allTrials.size();
    r.oracleFail = oracleFail;
    r.exitCode = (suite.require_oracle && oracleFail > 0) ? 1 : 0;
    System.out.println("BENCH_SUMMARY kind=" + suite.kind + " id=" + suite.id
        + " trials=" + r.trials + " oracle_fail=" + oracleFail + " dir=" + resultDir);
    return r;
  }

  private static BenchContext newCtx(Options opt, SuiteSpec suite, String stage,
                                     boolean planOnly, boolean requireOracle,
                                     Path resultDir, Manifest manifest, LayoutContext layout,
                                     StatsSnapshot stats, AppConfig.PlannerConfig planner,
                                     HBaseBackend kv, LlmClient llm,
                                     Map<String, OracleChecker.Answer> oracle,
                                     List<BoundIr> queries, RunCell cell) {
    return newCtxKeepArtifacts(opt, suite, stage, planOnly, requireOracle, resultDir,
        manifest, layout, stats, planner, kv, llm, oracle, queries, cell, opt.keepArtifacts);
  }

  private static BenchContext newCtxKeepArtifacts(Options opt, SuiteSpec suite, String stage,
                                                  boolean planOnly, boolean requireOracle,
                                                  Path resultDir, Manifest manifest,
                                                  LayoutContext layout, StatsSnapshot stats,
                                                  AppConfig.PlannerConfig planner,
                                                  HBaseBackend kv, LlmClient llm,
                                                  Map<String, OracleChecker.Answer> oracle,
                                                  List<BoundIr> queries, RunCell cell,
                                                  boolean keepArtifacts) {
    return new BenchContext(opt.root, resultDir, opt.runId, suite.kind, suite.id,
        stage, planOnly, requireOracle, keepArtifacts, opt.cache,
        manifest, layout, stats, planner, kv, llm, oracle, queries, cell.extras);
  }

  static String failClass(TrialResult tr, Map<String, Object> row) {
    String err = tr != null ? tr.error : null;
    if (err == null && row != null) {
      Object e = row.get("error");
      err = e == null ? null : String.valueOf(e);
    }
    if (err == null || err.isEmpty()) {
      if (Boolean.TRUE.equals(row.get("ok_oracle")) || Boolean.TRUE.equals(row.get("plan_ok"))) {
        return "ok";
      }
      if (Boolean.FALSE.equals(row.get("ok_oracle"))) {
        return "oracle_mismatch";
      }
      return "unknown";
    }
    String u = err.toUpperCase();
    if (u.contains("TIMEOUT") || u.contains("TIMED_OUT")) {
      return "timeout";
    }
    if (u.contains("RESOURCE_EXHAUSTED")) {
      return "resource_exhausted";
    }
    if (u.contains("NO_SAFE_PLAN")) {
      return "no_safe_plan";
    }
    if (u.contains("ORACLE") || u.contains("MISMATCH") || u.contains("ID MISMATCH")) {
      return "oracle_mismatch";
    }
    return "error";
  }

  private static void enrichTrace(Map<String, Object> row, TrialResult tr) {
    QueryResult qr = tr.queryResult;
    if (qr == null || qr.trace == null) {
      return;
    }
    ExecutionTrace t = qr.trace;
    row.put("n_ranges", t.client_ops_count);
    row.put("index_rows", t.totalRows > 0 ? Long.valueOf(t.totalRows) : null);
    row.put("fetch_chunks", t.fetched_chunks);
    row.put("bytes", t.totalBytes > 0 ? Long.valueOf(t.totalBytes) : null);
    row.put("dtw_cells", t.dtw_cells);
    if (!row.containsKey("llm_calls") && t.llm_calls != null) {
      row.put("llm_calls", t.llm_calls);
    }
    if (!row.containsKey("tokens_out") && t.llm_tokens != null) {
      row.put("tokens_out", t.llm_tokens);
    }
  }

  private static void writeMeta(Path resultDir, Options opt, SuiteSpec suite,
                                List<RunCell> cells, int boundCount, int nlCount,
                                AppConfig cfg, Path workloadPath, Path oraclePath,
                                int trialCount, CacheProtocol cacheProtocol,
                                Map<String, Object> hbaseEnv, List<NlItem> nlItems)
      throws Exception {
    Map<String, Object> meta = new LinkedHashMap<String, Object>();
    Path metaPath = resultDir.resolve("meta.json");
    if (Files.isRegularFile(metaPath)) {
      try {
        @SuppressWarnings("unchecked")
        Map<String, Object> prev = MAPPER.readValue(metaPath.toFile(), Map.class);
        if (prev != null) {
          meta.putAll(prev);
        }
      } catch (Exception ignore) {
        // replace
      }
    }
    meta.put("run_id", opt.runId);
    String cache = opt.cache == null ? "cold" : opt.cache.trim().toLowerCase();
    meta.put("cache", cache);
    if (cacheProtocol != null) {
      meta.put("cache_protocol", cacheProtocol.toMeta(trialCount));
      meta.put("cache_enforced", Boolean.valueOf(cacheProtocol.cacheEnforced()));
      String warn = cacheProtocol.formalWarning(trialCount);
      if (warn != null) {
        meta.put("cache_warning", warn);
      }
    } else {
      meta.put("cache_enforced", Boolean.FALSE);
    }
    meta.put("conclusion_boundary",
        "single-node mixed HBase classpath; relative results only; do not extrapolate to multi-RS");
    meta.put("external_arms_are_transplants", Boolean.TRUE);
    meta.put("manifest_id", suite.manifest);
    meta.put("git_commit", gitCommit(opt.root));
    meta.put("git_worktree_dirty", Boolean.valueOf(gitDirty(opt.root)));
    meta.put("java_version", System.getProperty("java.version"));
    meta.put("java_vendor", System.getProperty("java.vendor"));
    meta.put("os_name", System.getProperty("os.name"));
    meta.put("os_arch", System.getProperty("os.arch"));
    try {
      meta.put("host_name", java.net.InetAddress.getLocalHost().getHostName());
    } catch (Exception ignored) {
      meta.put("host_name", null);
    }
    Path hbaseSite = opt.root.resolve("config/hbase/hbase-site.xml");
    meta.put("hbase_site_sha256", sha256(hbaseSite));
    Path hbaseEvidence = opt.root.resolve("experiments/results/hbase_version_evidence.txt");
    if (Files.isRegularFile(hbaseEvidence)) {
      meta.put("hbase_evidence_path", hbaseEvidence.toString());
      meta.put("hbase_evidence_sha256", sha256(hbaseEvidence));
    }
    meta.put("suite_" + suite.id + "_kind", suite.kind);
    meta.put("suite_" + suite.id + "_path", opt.suitePath.toString());
    meta.put("suite_" + suite.id + "_workload", suite.workload);
    meta.put("suite_" + suite.id + "_workload_sha256", sha256(workloadPath));
    meta.put("suite_" + suite.id + "_oracle", suite.oracle);
    if (Files.isRegularFile(oraclePath)) {
      meta.put("suite_" + suite.id + "_oracle_sha256", sha256(oraclePath));
    }
    meta.put("suite_" + suite.id + "_stages", suite.stages);
    meta.put("suite_" + suite.id + "_cells", cellSummaries(cells));
    meta.put("suite_" + suite.id + "_bound_query_count", Integer.valueOf(boundCount));
    meta.put("suite_" + suite.id + "_nl_query_count", Integer.valueOf(nlCount));
    meta.put("trials", Integer.valueOf(trialCount));
    if (cfg.planner() != null && cfg.planner().cost != null) {
      meta.put("cost_calibrated", Boolean.valueOf(cfg.planner().cost.calibrated));
      meta.put("cost_calibrated_scope",
          "base planner.yaml; per-cell cost_calibrated is in factors[]");
    }
    if (isAblation(suite)) {
      meta.put("experiment_kind", "ablation");
      meta.put("base_arm", suite.base_arm);
      meta.put("allow_unsafe", Boolean.valueOf(opt.allowUnsafe));
      // Actual expanded cells, not the --no-pair-full switch. Default main table includes full.
      boolean pairedFull = cellsIncludeFull(cells);
      meta.put("ablation_pair_full", Boolean.valueOf(pairedFull));
      meta.put("ablation_no_pair_full", Boolean.valueOf(opt.noPairFull));
      if (opt.limit != null) {
        meta.put("query_limit", opt.limit);
      }
      meta.put("formal_bound_ir_count", Integer.valueOf(FORMAL_BOUND_IR_COUNT));
      meta.put("formal_workload_complete", Boolean.valueOf(boundCount >= FORMAL_BOUND_IR_COUNT));
      Map<String, Object> budget = new LinkedHashMap<String, Object>();
      if (cfg.planner() != null) {
        budget.put("beam_width", Integer.valueOf(cfg.planner().beam_width));
        budget.put("max_llm_calls", Integer.valueOf(cfg.planner().max_llm_calls));
        budget.put("max_candidates", Integer.valueOf(cfg.planner().max_candidates));
        budget.put("max_plan_ms", Long.valueOf(cfg.planner().max_plan_ms));
        budget.put("stagnation_steps", Integer.valueOf(cfg.planner().stagnation_steps));
      }
      meta.put("search_budget", budget);
      Path uncal = PlannerOverlay.uncalibratedPath(opt.root);
      meta.put("cost_uncalibrated_path", PlannerOverlay.UNCALIBRATED_COEFFS);
      if (Files.isRegularFile(uncal)) {
        meta.put("cost_uncalibrated_sha256", sha256(uncal));
      }
      if (cfg.planner() != null && cfg.planner().cost != null) {
        meta.put("cost_model_version", cfg.planner().cost.model_version);
      }
      meta.put("factors", factorAudit(opt.root, cfg.planner(), cells));
    }
    String model = System.getenv("LLM_MODEL");
    String base = System.getenv("LLM_BASE_URL");
    String temp = System.getenv("KART_LLM_TEMPERATURE");
    if (model != null) {
      meta.put("LLM_MODEL", model);
    }
    if (base != null) {
      meta.put("LLM_BASE_URL", base);
    }
    meta.put("LLM_TEMPERATURE", temp == null || temp.isEmpty() ? "0" : temp);
    Map<String, Object> fairness = new LinkedHashMap<String, Object>();
    fairness.put("same_endpoint", Boolean.TRUE);
    fairness.put("temperature", meta.get("LLM_TEMPERATURE"));
    fairness.put("early_reject_shared", "ParseFairness/Dialog.earlyUnsupportedReason");
    fairness.put("e3_t_e2e", "t_plan_ms + t_exec_ms excluding artifact IO");
    fairness.put("e1_query_order", "deterministic shuffle of NL items with workload seed; same order for all arms");
    if (isAblation(suite)) {
      fairness.put("ablation_pair_full", Boolean.valueOf(cellsIncludeFull(cells)));
      fairness.put("ablation_llm_schema",
          "llm_calls/tokens_in/tokens_out always present; JSON null if no LLM");
      fairness.put("ablation_warmup_rotation",
          "warm untimed passes rotate by query and warmup pass, matching timed-trial family");
    }
    meta.put("fairness", fairness);
    if (hbaseEnv != null) {
      meta.put("hbase_env", hbaseEnv);
    }
    if (nlItems != null && !nlItems.isEmpty()) {
      List<String> order = new ArrayList<String>();
      for (NlItem it : nlItems) {
        if (it != null && it.query_id != null) {
          order.add(it.query_id);
        }
      }
      meta.put("nl_query_order", order);
    }
    meta.put("third_party_audit", ThirdPartyAudit.collect(opt.root));
    if (Files.isRegularFile(workloadPath)) {
      try {
        JsonNode wl = MAPPER.readTree(workloadPath.toFile());
        if (wl.has("semantics_version")) {
          meta.put("semantics_version", wl.get("semantics_version").asText());
        }
        if (wl.has("workload_id")) {
          meta.put("workload_id", wl.get("workload_id").asText());
        }
        if (wl.has("seed")) {
          meta.put("workload_seed", wl.get("seed").asText());
        }
      } catch (Exception ignore) {
        //
      }
    }
    Files.write(metaPath, MAPPER.writeValueAsString(meta).getBytes(StandardCharsets.UTF_8),
        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
  }

  private static String gitCommit(Path root) {
    try {
      Process p = new ProcessBuilder("git", "rev-parse", "HEAD")
          .directory(root.toFile())
          .redirectErrorStream(true)
          .start();
      byte[] out = readAll(p.getInputStream());
      p.waitFor();
      String s = new String(out, StandardCharsets.UTF_8).trim();
      return s.isEmpty() ? null : s;
    } catch (Exception e) {
      return null;
    }
  }

  private static boolean gitDirty(Path root) {
    try {
      Process p = new ProcessBuilder("git", "status", "--porcelain")
          .directory(root.toFile())
          .redirectErrorStream(true)
          .start();
      byte[] out = readAll(p.getInputStream());
      p.waitFor();
      return new String(out, StandardCharsets.UTF_8).trim().length() > 0;
    } catch (Exception e) {
      return true;
    }
  }

  private static String sha256(Path file) {
    try {
      java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
      byte[] dig = md.digest(Files.readAllBytes(file));
      StringBuilder sb = new StringBuilder();
      for (byte b : dig) {
        sb.append(String.format("%02x", Byte.valueOf(b)));
      }
      return sb.toString();
    } catch (Exception e) {
      return null;
    }
  }

  private static byte[] readAll(java.io.InputStream in) throws java.io.IOException {
    java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
    byte[] buf = new byte[4096];
    int n;
    while ((n = in.read(buf)) >= 0) {
      bos.write(buf, 0, n);
    }
    return bos.toByteArray();
  }

  static final class RunCell {
    String armId;
    String label;
    Map<String, Object> overrides = new LinkedHashMap<String, Object>();
    Map<String, Object> extras = new LinkedHashMap<String, Object>();
    boolean unsafe;
  }

  static List<RunCell> expandCells(SuiteSpec suite, ArmRegistry registry, Options opt,
                                           boolean parseStage) {
    List<RunCell> cells = new ArrayList<RunCell>();
    String kind = suite.kind == null ? "compare" : suite.kind.trim().toLowerCase();

    if ("ablation".equals(kind)) {
      String baseArm = suite.base_arm != null ? suite.base_arm : "kart";
      RunCell base = new RunCell();
      base.armId = baseArm;
      base.label = "full";
      base.extras.put("factor", "full");
      boolean wantFull = passArmFilter(base.armId, opt)
          && (shouldPairFull(suite, opt)
              || passFactorFilter("full", opt)
              || passFactorFilter("base", opt));
      if (wantFull) {
        cells.add(base);
      }
      if (suite.factors != null) {
        for (SuiteSpec.Factor f : suite.factors) {
          if (f == null || !f.enabled || f.id == null) {
            continue;
          }
          if (f.unsafe && !opt.allowUnsafe) {
            System.out.println("BENCH skip unsafe factor " + f.id + " (pass --allow-unsafe)");
            continue;
          }
          if (!passFactorFilter(f.id, opt)) {
            continue;
          }
          RunCell c = new RunCell();
          String policy = overridePolicy(f.overrides);
          c.armId = policy != null ? policy : baseArm;
          if (!passArmFilter(c.armId, opt)) {
            continue;
          }
          c.label = f.id;
          c.overrides.putAll(f.overrides != null ? f.overrides
              : Collections.<String, Object>emptyMap());
          c.extras.put("factor", f.id);
          c.unsafe = f.unsafe;
          cells.add(c);
        }
      }
      return cells;
    }

    if ("param".equals(kind)) {
      List<Map<String, Object>> points = cartesian(suite.grid);
      String armId = suite.base_arm != null ? suite.base_arm : "rule";
      if (!passArmFilter(armId, opt)) {
        return cells;
      }
      if (points.isEmpty()) {
        RunCell c = new RunCell();
        c.armId = armId;
        c.label = "param:default";
        c.extras.put("param_point", Collections.emptyMap());
        cells.add(c);
        return cells;
      }
      int i = 0;
      for (Map<String, Object> pt : points) {
        RunCell c = new RunCell();
        c.armId = armId;
        c.label = "param:" + (++i);
        c.overrides.putAll(pt);
        c.extras.put("param_point", pt);
        cells.add(c);
      }
      return cells;
    }

    List<String> armList = suite.arms;
    if (armList == null || armList.isEmpty()) {
      armList = new ArrayList<String>();
      if (parseStage) {
        armList.add("kart");
      } else {
        armList.add("fullscan");
        armList.add("rbo");
        armList.add("cbo");
        armList.add("kart");
      }
    }
    for (String a : armList) {
      if (!passArmFilter(a, opt)) {
        continue;
      }
      boolean known = parseStage
          ? registry.resolveParse(a) != null
          : registry.resolve(a) != null;
      if (!known) {
        System.err.println("BENCH warn: unknown arm in suite: " + a);
        continue;
      }
      RunCell c = new RunCell();
      c.armId = a;
      c.label = a;
      cells.add(c);
    }
    return cells;
  }

  private static String overridePolicy(Map<String, Object> overrides) {
    if (overrides == null) {
      return null;
    }
    Object p = overrides.get("policy");
    if (p == null) {
      p = overrides.get("arm");
    }
    return p == null ? null : String.valueOf(p);
  }

  private static boolean isAblation(SuiteSpec suite) {
    return suite != null && suite.kind != null
        && "ablation".equalsIgnoreCase(suite.kind.trim());
  }

  private static boolean passArmFilter(String armId, Options opt) {
    if (opt.armFilter == null || opt.armFilter.isEmpty()) {
      return true;
    }
    String n = armId.trim().toLowerCase();
    for (String f : opt.armFilter) {
      if (f != null && f.trim().toLowerCase().equals(n)) {
        return true;
      }
    }
    return false;
  }

  private static boolean passFactorFilter(String factorId, Options opt) {
    if (opt.factorFilter == null || opt.factorFilter.isEmpty()) {
      return true;
    }
    String n = factorId.trim().toLowerCase();
    for (String f : opt.factorFilter) {
      if (f != null && f.trim().toLowerCase().equals(n)) {
        return true;
      }
    }
    return false;
  }

  /**
   * True when the expanded ablation cells actually contain the Full baseline.
   * Used for {@code meta.ablation_pair_full} (not the same as {@link #shouldPairFull}).
   */
  static boolean cellsIncludeFull(List<RunCell> cells) {
    if (cells == null) {
      return false;
    }
    for (RunCell c : cells) {
      if (c == null) {
        continue;
      }
      if ("full".equals(c.label)) {
        return true;
      }
      Object factor = c.extras == null ? null : c.extras.get("factor");
      if (factor != null && "full".equalsIgnoreCase(String.valueOf(factor).trim())) {
        return true;
      }
    }
    return false;
  }

  /**
   * Safe {@code --factor} selections auto-include Full so paired deltas are defined.
   * Risk-only filters ({@code no_coverage}) stay unpaired. {@code --no-pair-full} disables this.
   */
  static boolean shouldPairFull(SuiteSpec suite, Options opt) {
    if (opt == null || opt.noPairFull) {
      return false;
    }
    if (opt.factorFilter == null || opt.factorFilter.isEmpty()) {
      return false;
    }
    return !factorFilterIsRiskOnly(suite, opt);
  }

  static boolean factorFilterIsRiskOnly(SuiteSpec suite, Options opt) {
    if (opt == null || opt.factorFilter == null || opt.factorFilter.isEmpty()) {
      return false;
    }
    LinkedHashSet<String> ids = new LinkedHashSet<String>();
    for (String f : opt.factorFilter) {
      if (f == null || f.trim().isEmpty()) {
        continue;
      }
      String n = f.trim().toLowerCase();
      if ("full".equals(n) || "base".equals(n)) {
        continue;
      }
      ids.add(n);
    }
    if (ids.isEmpty()) {
      return false;
    }
    if (suite == null || suite.factors == null) {
      return false;
    }
    for (String id : ids) {
      SuiteSpec.Factor found = null;
      for (SuiteSpec.Factor f : suite.factors) {
        if (f != null && f.id != null && id.equalsIgnoreCase(f.id.trim())) {
          found = f;
          break;
        }
      }
      if (found == null || !found.unsafe) {
        return false;
      }
    }
    return true;
  }

  static void ensureLlmSchema(Map<String, Object> row) {
    if (row == null) {
      return;
    }
    if (!row.containsKey("llm_calls")) {
      row.put("llm_calls", null);
    }
    if (!row.containsKey("tokens_in")) {
      row.put("tokens_in", null);
    }
    if (!row.containsKey("tokens_out")) {
      row.put("tokens_out", null);
    }
  }

  static boolean isLlmSchemaKey(String key) {
    return "llm_calls".equals(key) || "tokens_in".equals(key) || "tokens_out".equals(key);
  }

  static void putQueryStrata(Map<String, Object> row, BoundIr ir) {
    if (row == null || ir == null) {
      return;
    }
    if (ir.similarity != null && ir.similarity.metric != null
        && !ir.similarity.metric.trim().isEmpty()) {
      row.put("metric", ir.similarity.metric.trim());
    } else if (!row.containsKey("metric")) {
      row.put("metric", "none");
    }
    if (ir.result != null && ir.result.mode != null) {
      row.put("result_mode", ir.result.mode);
    }
    if (ir.result != null && ir.result.k != null) {
      row.put("k", ir.result.k);
    } else if (!row.containsKey("k")) {
      row.put("k", "n/a");
    }
    Object sel = row.get("selectivity");
    if (sel != null) {
      String s = String.valueOf(sel).trim().toLowerCase();
      if ("empty".equals(s) || "boundary".equals(s)) {
        row.put("empty_boundary", s);
      } else if (!row.containsKey("empty_boundary")) {
        row.put("empty_boundary", "interior");
      }
    }
  }

  /** LLM rows that fell back to a rule/CBO plan must not share the pure-LLM arm name. */
  static boolean splitsOnLlmFallback(String armId) {
    return "kart".equals(armId)
        || "llm".equals(armId)
        || "llm_direct".equals(armId)
        || "kart-conditional-llm".equals(armId)
        || "pool-llm".equals(armId)
        || "pool-conditional-llm".equals(armId);
  }

  static String fallbackArmName(String armId, String label, boolean ablation) {
    if (ablation) {
      return (label == null || label.isEmpty() ? armId : label) + "-rule-fallback";
    }
    if ("kart".equals(armId)) {
      return "kart-rule-fallback";
    }
    return armId + "-rule-fallback";
  }

  static boolean isUncalibratedOverride(Map<String, Object> overrides) {
    if (overrides == null) {
      return false;
    }
    Object v = overrides.get("uncalibrated");
    if (v == null) {
      return false;
    }
    if (v instanceof Boolean) {
      return ((Boolean) v).booleanValue();
    }
    String s = String.valueOf(v).trim().toLowerCase();
    return "true".equals(s) || "yes".equals(s) || "1".equals(s);
  }

  static List<Map<String, Object>> factorAudit(Path root, AppConfig.PlannerConfig base,
                                               List<RunCell> cells) {
    List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
    if (cells == null) {
      return out;
    }
    for (RunCell c : cells) {
      out.add(factorAuditEntry(root, base, c));
    }
    return out;
  }

  static Map<String, Object> factorAuditEntry(Path root, AppConfig.PlannerConfig base, RunCell c) {
    Map<String, Object> m = new LinkedHashMap<String, Object>();
    if (c == null) {
      return m;
    }
    AppConfig.PlannerConfig planner = PlannerOverlay.apply(base, c.overrides, root);
    boolean uncal = isUncalibratedOverride(c.overrides);
    String rel = uncal ? PlannerOverlay.UNCALIBRATED_COEFFS : "config/planner.yaml";
    Path coeffsPath = root != null ? root.resolve(rel) : java.nio.file.Paths.get(rel);
    m.put("id", c.label);
    m.put("arm", c.armId);
    m.put("factor", c.extras != null ? c.extras.get("factor") : null);
    m.put("overrides", new LinkedHashMap<String, Object>(c.overrides));
    m.put("unsafe", Boolean.valueOf(c.unsafe));
    if (planner != null && planner.cost != null) {
      m.put("cost_calibrated", Boolean.valueOf(planner.cost.calibrated));
      m.put("cost_model_version", planner.cost.model_version);
    } else {
      m.put("cost_calibrated", null);
      m.put("cost_model_version", null);
    }
    m.put("cost_coeffs_path", rel);
    m.put("cost_coeffs_sha256", sha256(coeffsPath));
    m.put("cost_source", uncal ? "frozen_uncalibrated_yaml" : "planner_yaml");
    return m;
  }

  private static List<Map<String, Object>> cartesian(Map<String, List<Object>> grid) {
    List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
    if (grid == null || grid.isEmpty()) {
      return out;
    }
    List<String> keys = new ArrayList<String>(grid.keySet());
    recurseCart(keys, 0, grid, new LinkedHashMap<String, Object>(), out);
    return out;
  }

  private static void recurseCart(List<String> keys, int idx, Map<String, List<Object>> grid,
                                  Map<String, Object> cur, List<Map<String, Object>> out) {
    if (idx >= keys.size()) {
      out.add(new LinkedHashMap<String, Object>(cur));
      return;
    }
    String k = keys.get(idx);
    List<Object> vals = grid.get(k);
    if (vals == null || vals.isEmpty()) {
      recurseCart(keys, idx + 1, grid, cur, out);
      return;
    }
    for (Object v : vals) {
      cur.put(k, v);
      recurseCart(keys, idx + 1, grid, cur, out);
    }
    cur.remove(k);
  }

  private static List<Map<String, Object>> cellSummaries(List<RunCell> cells) {
    List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
    for (RunCell c : cells) {
      Map<String, Object> m = new LinkedHashMap<String, Object>();
      m.put("label", c.label);
      m.put("arm", c.armId);
      m.put("factor", c.extras.get("factor"));
      m.put("overrides", c.overrides);
      m.put("unsafe", Boolean.valueOf(c.unsafe));
      out.add(m);
    }
    return out;
  }

  private static List<BoundIr> loadQueries(Path workloadPath, Integer limit) throws Exception {
    JsonNode root = MAPPER.readTree(Files.readAllBytes(workloadPath));
    List<BoundIr> queries = new ArrayList<BoundIr>();
    JsonNode arr = root.get("queries");
    if (arr == null || !arr.isArray()) {
      throw new IllegalStateException("workload missing queries[]: " + workloadPath);
    }
    for (JsonNode q : arr) {
      if (q.has("utterance") && !q.has("ir_version")) {
        continue; // NL row in mixed file
      }
      queries.add(MAPPER.treeToValue(q, BoundIr.class));
      if (limit != null && queries.size() >= limit.intValue()) {
        break;
      }
    }
    return queries;
  }

  private static List<NlItem> loadNlItems(Path workloadPath, Integer limit) throws Exception {
    JsonNode root = MAPPER.readTree(Files.readAllBytes(workloadPath));
    List<NlItem> items = new ArrayList<NlItem>();
    JsonNode arr = root.get("queries");
    if (arr == null || !arr.isArray()) {
      throw new IllegalStateException("NL workload missing queries[]: " + workloadPath);
    }
    for (JsonNode q : arr) {
      items.add(MAPPER.treeToValue(q, NlItem.class));
      if (limit != null && items.size() >= limit.intValue()) {
        break;
      }
    }
    return items;
  }

  private static Path resolve(Path root, String rel) {
    Path p = java.nio.file.Paths.get(rel);
    return p.isAbsolute() ? p : root.resolve(rel);
  }

  private static String readWorkloadSeed(Path workloadPath) {
    if (workloadPath == null || !Files.isRegularFile(workloadPath)) {
      return "kart-nl-default";
    }
    try {
      JsonNode root = MAPPER.readTree(workloadPath.toFile());
      if (root.has("seed")) {
        return root.get("seed").asText("kart-nl-default");
      }
      if (root.has("workload_id")) {
        return root.get("workload_id").asText("kart-nl-default");
      }
    } catch (Exception ignore) {
      //
    }
    return "kart-nl-default";
  }

  private static List<NlItem> shuffleDeterministic(List<NlItem> items, String seed) {
    List<NlItem> copy = new ArrayList<NlItem>(items);
    long s = 0x9e3779b97f4a7c15L;
    if (seed != null) {
      s ^= seed.hashCode();
      for (int i = 0; i < seed.length(); i++) {
        s = s * 1099511628211L + seed.charAt(i);
      }
    }
    Collections.shuffle(copy, new Random(s));
    return copy;
  }
}
