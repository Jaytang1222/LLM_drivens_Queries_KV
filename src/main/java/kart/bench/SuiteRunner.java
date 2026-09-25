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
          || "kart".equals(c.armId) || "llmopt".equals(c.armId)) {
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

    try {
      TrialWriter parseWriter = hasParse ? new TrialWriter(parsePath, true) : null;
      TrialWriter planWriter = null;
      TrialWriter e2eWriter = null;
      try {
        for (RunCell cell : cells) {
          if (cell.unsafe && !opt.allowUnsafe) {
            System.out.println("BENCH skip unsafe cell " + cell.label);
            continue;
          }
          AppConfig.PlannerConfig planner = PlannerOverlay.apply(basePlanner, cell.overrides);
          for (String stage : suite.stages) {
            if ("parse".equalsIgnoreCase(stage)) {
              ParseArm parm = registry.resolveParse(cell.armId);
              if (parm == null) {
                System.err.println("BENCH unknown parse arm: " + cell.armId);
                continue;
              }
              BenchContext ctx = newCtx(opt, suite, stage, true, suite.require_oracle,
                  resultDir, manifest, layout, stats, planner, kv, llm, oracle, queries, cell);
              for (NlItem item : nlItems) {
                for (int trial = 1; trial <= trialCount; trial++) {
                  Map<String, Object> row = new LinkedHashMap<String, Object>();
                  row.put("run_id", opt.runId);
                  row.put("query_id", item.query_id);
                  row.put("arm", cell.label);
                  row.put("trial", Integer.valueOf(trial));
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
                  System.out.println("BENCH parse cell=" + cell.label + " q=" + item.query_id
                      + " ok_ex=" + tr.ok_ex + " t_parse_ms=" + tr.t_parse_ms
                      + (tr.error != null ? (" err=" + tr.error) : ""));
                }
              }
              continue;
            }

            boolean planOnly = "plan".equalsIgnoreCase(stage);
            boolean requireOracle = suite.require_oracle && !planOnly;
            Arm arm = registry.resolve(cell.armId);
            if (arm == null) {
              System.err.println("BENCH unknown arm: " + cell.armId);
              continue;
            }
            if (planOnly && planWriter == null) {
              planWriter = new TrialWriter(planPath, true);
            }
            if (!planOnly && e2eWriter == null) {
              e2eWriter = new TrialWriter(e2ePath, true);
            }

            BenchContext ctx = newCtx(opt, suite, stage, planOnly, requireOracle,
                resultDir, manifest, layout, stats, planner, kv, llm, oracle, queries, cell);
            BenchContext warmCtx = newCtxKeepArtifacts(opt, suite, stage, planOnly, requireOracle,
                resultDir, manifest, layout, stats, planner, kv, llm, oracle, queries, cell, false);

            for (BoundIr ir : queries) {
              if (!planOnly && cacheProtocol.warmupPasses > 0) {
                for (int w = 0; w < cacheProtocol.warmupPasses; w++) {
                  try {
                    arm.run(ir, warmCtx);
                  } catch (Exception ignore) {
                    // discarded untimed warmup
                  }
                }
              }
              for (int trial = 1; trial <= trialCount; trial++) {
                Map<String, Object> row = new LinkedHashMap<String, Object>();
                row.put("run_id", opt.runId);
                row.put("query_id", ir.query_id);
                row.put("arm", cell.label);
                row.put("trial", Integer.valueOf(trial));
                row.put("stage", stage);
                row.put("cache", opt.cache == null ? "cold" : opt.cache);
                row.put("warmup_passes", Integer.valueOf(planOnly ? 0 : cacheProtocol.warmupPasses));
                if (catalog.containsKey(ir.query_id)) {
                  row.putAll(catalog.get(ir.query_id));
                }

                if (!planOnly && "cold".equals(cacheProtocol.mode)) {
                  cacheProtocol.flushBeforeTrial(kv, layout);
                }

                TrialResult tr;
                try {
                  tr = arm.run(ir, ctx);
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
                enrichTrace(row, tr);
                if (tr.extras != null) {
                  for (Map.Entry<String, Object> extra : tr.extras.entrySet()) {
                    if (extra.getKey() != null && extra.getValue() != null) {
                      row.put(extra.getKey(), extra.getValue());
                    }
                  }
                  if (Boolean.TRUE.equals(tr.extras.get("llm_fallback"))
                      && ("kart".equals(cell.armId) || "llm".equals(cell.armId)
                      || "llm_direct".equals(cell.armId))) {
                    row.put("arm_requested", cell.armId);
                    row.put("arm", "kart".equals(cell.armId)
                        ? "kart-rule-fallback" : (cell.armId + "-rule-fallback"));
                  }
                }
                row.put("cache_enforced", Boolean.valueOf(cacheProtocol.cacheEnforced()));
                row.put("cache_protocol", cacheProtocol.protocolLabel());

                Boolean okOracle;
                Boolean planOk = null;
                if (planOnly) {
                  planOk = Boolean.valueOf(tr.error == null
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
                allTrials.add(row);
                System.out.println("BENCH " + suite.id + " stage=" + stage
                    + " cell=" + cell.label + " q=" + ir.query_id
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

  private static final class RunCell {
    String armId;
    String label;
    Map<String, Object> overrides = new LinkedHashMap<String, Object>();
    Map<String, Object> extras = new LinkedHashMap<String, Object>();
    boolean unsafe;
  }

  private static List<RunCell> expandCells(SuiteSpec suite, ArmRegistry registry, Options opt,
                                           boolean parseStage) {
    List<RunCell> cells = new ArrayList<RunCell>();
    String kind = suite.kind == null ? "compare" : suite.kind.trim().toLowerCase();

    if ("ablation".equals(kind)) {
      RunCell base = new RunCell();
      base.armId = suite.base_arm != null ? suite.base_arm : "rule";
      base.label = "base:" + base.armId;
      base.extras.put("factor", "base");
      if (passArmFilter(base.armId, opt) && passFactorFilter("base", opt)) {
        cells.add(base);
      }
      for (SuiteSpec.Factor f : suite.factors) {
        if (f == null || !f.enabled || f.id == null) {
          continue;
        }
        if (f.unsafe && !opt.allowUnsafe) {
          continue;
        }
        if (!passFactorFilter(f.id, opt)) {
          continue;
        }
        RunCell c = new RunCell();
        String policy = overridePolicy(f.overrides);
        c.armId = policy != null ? policy : base.armId;
        if (!passArmFilter(c.armId, opt)) {
          continue;
        }
        c.label = "ablate:" + f.id;
        c.overrides.putAll(f.overrides != null ? f.overrides
            : Collections.<String, Object>emptyMap());
        c.extras.put("factor", f.id);
        c.unsafe = f.unsafe;
        cells.add(c);
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
