package kart.bench;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import kart.catalog.CatalogStore;
import kart.catalog.Manifest;
import kart.catalog.StatsSnapshot;
import kart.compile.LayoutContext;
import kart.config.AppConfig;
import kart.cost.CostCard;
import kart.exec.HBaseBackend;
import kart.exec.QueryResult;
import kart.ir.BoundIr;
import kart.plan.PlanBuilder;
import kart.plan.PlanEnvelope;
import kart.query.QueryEngine;
import kart.search.PlannerMode;
import kart.validation.SafePlanHandle;
import kart.validation.ValidationReport;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Development diagnostic: CBO opportunity census (no LLM).
 * See {@code docs/cbo_opportunity_census_guide.md}.
 */
public final class OpportunityCensus {

  private static final ObjectMapper MAPPER = new ObjectMapper()
      .enable(SerializationFeature.INDENT_OUTPUT)
      .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
  private static final ObjectMapper JSONL = new ObjectMapper()
      .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

  public static final class Options {
    public Path root;
    public String runId = "opportunity-dev-v1";
    public Path pool;
    public Path extraPool;
    public Path exclude;
    public List<Path> oraclePaths = new ArrayList<Path>();
    public String manifestId = "tdrive_v1_ready";
    public String cache = "warm";
    public int initialTrials = 1;
    public int repeatTrials = 3;
    public long maxExecMs = 60_000L;
    public long maxWallMinutes = 180L;
    public long hybridExtraPlanMs = OpportunityCensusLabels.PROVISIONAL_HYBRID_EXTRA_PLAN_MS;
    public long repeatGainThresholdMs = 500L;
    public boolean phaseSearch = true;
    public boolean phaseExec = true;
    public Integer limit;
    public boolean includeFullscanExec;
  }

  public static final class Result {
    public int exitCode;
    public Path outDir;
    public int queries;
    public int searchRows;
    public int measurementRows;
  }

  private static final class PoolQuery {
    BoundIr ir;
    String fingerprint;
    String sourceLayer;
    String sourceQueryId;
    String category;
    String templateHint;
  }

  private static final class ValidatedPlan {
    PlanEnvelope envelope;
    String planId;
    String signature;
    boolean inCboSafe;
    boolean cboSelected;
    Double estimatedMs;
    SafePlanHandle handle;
    QueryEngine.RunResult planned;
    long validateMs;
  }

  public Result run(Options opt) throws Exception {
    if (opt == null || opt.root == null) {
      throw new IllegalArgumentException("root required");
    }
    if (opt.pool == null || !Files.isRegularFile(resolve(opt.root, opt.pool))) {
      throw new IllegalStateException("pool workload missing: " + opt.pool);
    }
    Path outDir = opt.root.resolve("experiments/opportunity").resolve(opt.runId);
    Files.createDirectories(outDir);
    long deadlineMs = System.currentTimeMillis() + Math.max(1L, opt.maxWallMinutes) * 60_000L;

    List<PoolQuery> pool = loadAndDedup(opt);
    if (opt.limit != null && opt.limit.intValue() > 0 && pool.size() > opt.limit.intValue()) {
      pool = new ArrayList<PoolQuery>(pool.subList(0, opt.limit.intValue()));
    }

    Map<String, OracleChecker.Answer> oracle = new LinkedHashMap<String, OracleChecker.Answer>();
    for (Path op : opt.oraclePaths) {
      Path abs = resolve(opt.root, op);
      if (Files.isRegularFile(abs)) {
        oracle.putAll(OracleChecker.load(abs));
      }
    }
    // Default oracles for known pools
    Path advOracle = opt.root.resolve("experiments/workloads/bound_ir_advantage_v2.oracle.json");
    Path pilotOracle = opt.root.resolve("experiments/workloads/bound_ir_cbo_llm_pilot_v1.oracle.json");
    if (Files.isRegularFile(advOracle) && !oracle.containsKey("__loaded_adv__")) {
      oracle.putAll(OracleChecker.load(advOracle));
    }
    if (Files.isRegularFile(pilotOracle)) {
      oracle.putAll(OracleChecker.load(pilotOracle));
    }

    // Fail-closed: every query needs an oracle entry for exec phase success counting
    int missingOracle = 0;
    for (PoolQuery pq : pool) {
      if (!oracle.containsKey(pq.ir.query_id)) {
        missingOracle++;
      }
    }

    CatalogStore store = new CatalogStore(opt.root.resolve("catalog"));
    Manifest manifest = store.loadManifest(opt.manifestId).orElse(null);
    if (manifest == null) {
      throw new IllegalStateException("manifest not READY/found: " + opt.manifestId);
    }
    LayoutContext layout = LayoutContext.from(manifest);
    StatsSnapshot stats = store.loadStats(opt.manifestId).orElse(null);
    AppConfig cfg = AppConfig.load(opt.root);
    AppConfig.PlannerConfig planner = cfg.planner().copy();

    Path site = opt.root.resolve("config/hbase/hbase-site.xml");
    HBaseBackend kv = new HBaseBackend(HBaseBackend.open(site));
    Map<String, Object> hbaseEnv = HBaseEnvProbe.collect(kv,
        opt.root.resolve("experiments/results/hbase_version_evidence.txt"));

    writeMeta(outDir, opt, pool, oracle.size(), missingOracle, manifest, cfg, hbaseEnv);

    Path searchPath = outDir.resolve("search.jsonl");
    Path measPath = outDir.resolve("measurements.jsonl");
    Set<String> doneSearch = loadDoneQueryIds(searchPath, "query_id");
    Set<String> doneMeasKeys = loadDoneKeys(measPath);

    QueryEngine cboEngine = newEngine(kv, layout, stats, planner, PlannerMode.BEST_FIRST);
    QueryEngine fixedEngine = newEngine(kv, layout, stats, planner, PlannerMode.RULE);

    int searchRows = 0;
    int measRows = 0;
    List<Map<String, Object>> querySummaries = new ArrayList<Map<String, Object>>();

    try {
      BufferedWriter searchW = Files.newBufferedWriter(searchPath, StandardCharsets.UTF_8,
          StandardOpenOption.CREATE, StandardOpenOption.APPEND);
      BufferedWriter measW = Files.newBufferedWriter(measPath, StandardCharsets.UTF_8,
          StandardOpenOption.CREATE, StandardOpenOption.APPEND);
      try {
        for (PoolQuery pq : pool) {
          if (System.currentTimeMillis() > deadlineMs) {
            writeCensoredSearch(searchW, pq, "max_wall_minutes");
            searchRows++;
            Map<String, Object> qs = baseQuerySummary(pq);
            qs.put("labels", Collections.singletonList("censored"));
            qs.put("censor_reason", "max_wall_minutes");
            querySummaries.add(qs);
            continue;
          }

          List<Map<String, Object>> searchLines = new ArrayList<Map<String, Object>>();
          ValidatedPlan cboSelected = null;
          List<ValidatedPlan> validatedNonFull = new ArrayList<ValidatedPlan>();
          Set<String> cboSafeIds = new LinkedHashSet<String>();
          Set<String> cboSafeSigs = new LinkedHashSet<String>();
          boolean incompleteTrace = false;
          String stopReason = null;
          Long tPlanCbo = null;
          boolean searchFailed = false;

          if (opt.phaseSearch && !doneSearch.contains(pq.ir.query_id)) {
            QueryEngine.RunResult cboRr = cboEngine.run(pq.ir, null, true, null);
            tPlanCbo = cboRr != null ? cboRr.t_plan_ms : null;
            stopReason = cboRr != null ? cboRr.searchStopReason : null;
            if (cboRr == null || cboRr.selected == null) {
              searchFailed = true;
              Map<String, Object> row = baseSearchRow(pq, "cbo_search");
              row.put("status", "CBO_FAIL");
              row.put("error", cboRr != null && cboRr.result != null ? cboRr.result.error : "null");
              row.put("incomplete_search_trace", Boolean.TRUE);
              searchLines.add(row);
            } else {
              if (cboRr.safe == null || cboRr.safe.isEmpty()) {
                incompleteTrace = true;
              }
              for (SafePlanHandle h : cboRr.safe) {
                if (h != null && h.plan() != null && h.plan().plan_id != null) {
                  cboSafeIds.add(h.plan().plan_id);
                  cboSafeSigs.add(h.plan().signature());
                }
              }
              String selectedId = cboRr.selected.plan() != null
                  ? cboRr.selected.plan().plan_id : null;
              Map<String, Object> cboRow = baseSearchRow(pq, "cbo_search");
              cboRow.put("status", "OK");
              cboRow.put("cbo_selected_plan_id", selectedId);
              cboRow.put("cbo_safe_plan_ids", new ArrayList<String>(cboSafeIds));
              cboRow.put("search_stop_reason", stopReason);
              cboRow.put("t_plan_cbo_ms", tPlanCbo);
              cboRow.put("incomplete_search_trace", Boolean.valueOf(incompleteTrace));
              cboRow.put("n_safe", Integer.valueOf(cboSafeIds.size()));
              List<Map<String, Object>> cards = new ArrayList<Map<String, Object>>();
              if (cboRr.costCards != null) {
                for (CostCard c : cboRr.costCards) {
                  if (c == null) {
                    continue;
                  }
                  Map<String, Object> cm = new LinkedHashMap<String, Object>();
                  cm.put("plan_id", c.plan_id);
                  cm.put("estimated_ms", Double.valueOf(c.estimated_ms));
                  cm.put("uncertainty", c.uncertainty);
                  cards.add(cm);
                }
              }
              cboRow.put("cost_cards", cards);
              searchLines.add(cboRow);

              ValidatedPlan sel = new ValidatedPlan();
              sel.handle = cboRr.selected;
              sel.envelope = cboRr.selected.plan();
              sel.planId = selectedId;
              sel.signature = sel.envelope != null ? sel.envelope.signature() : null;
              sel.inCboSafe = true;
              sel.cboSelected = true;
              sel.estimatedMs = cboRr.selectedCost != null
                  ? Double.valueOf(cboRr.selectedCost.estimated_ms) : null;
              sel.planned = cboRr;
              sel.validateMs = tPlanCbo != null ? tPlanCbo.longValue() : 0L;
              cboSelected = sel;
            }

            for (PlanEnvelope env : PlanBuilder.buildCandidatesWithMergeVariants(pq.ir)) {
              long v0 = System.currentTimeMillis();
              QueryEngine.RunResult one = fixedEngine.runFixed(pq.ir, null, true, env);
              long vMs = Math.max(0L, System.currentTimeMillis() - v0);
              Map<String, Object> row = baseSearchRow(pq, "constructable");
              row.put("plan_id", env.plan_id);
              row.put("plan_signature", env.signature());
              row.put("t_plan_validate_ms", Long.valueOf(vMs));
              boolean ok = one != null && one.selected != null;
              row.put("validated_safe", Boolean.valueOf(ok));
              if (!ok) {
                row.put("status", "REJECT");
                String reason = "rejected";
                if (one != null && one.rejectionReports != null && !one.rejectionReports.isEmpty()) {
                  ValidationReport vr = one.rejectionReports.get(0);
                  reason = vr != null ? String.valueOf(vr) : reason;
                } else if (one != null && one.result != null && one.result.error != null) {
                  reason = one.result.error;
                }
                row.put("reject_reason", reason);
                searchLines.add(row);
                continue;
              }
              row.put("status", "OK");
              Double est = one.selectedCost != null
                  ? Double.valueOf(one.selectedCost.estimated_ms) : null;
              row.put("estimated_ms", est);
              boolean inSafe = cboSafeIds.contains(env.plan_id)
                  || cboSafeSigs.contains(env.signature());
              boolean selected = cboSelected != null && env.plan_id != null
                  && env.plan_id.equals(cboSelected.planId);
              row.put("in_cbo_safe_set", Boolean.valueOf(inSafe));
              row.put("cbo_selected", Boolean.valueOf(selected));
              String missTag = null;
              if (!"P_FULL".equals(env.plan_id) && ok) {
                if (!inSafe) {
                  missTag = "candidate_miss";
                } else if (!selected) {
                  missTag = "selection_miss";
                }
              }
              row.put("miss_tag", missTag);
              searchLines.add(row);

              ValidatedPlan vp = new ValidatedPlan();
              vp.envelope = env;
              vp.planId = env.plan_id;
              vp.signature = env.signature();
              vp.inCboSafe = inSafe;
              vp.cboSelected = selected;
              vp.estimatedMs = est;
              vp.handle = one.selected;
              vp.planned = one;
              vp.validateMs = vMs;
              if (!"P_FULL".equals(env.plan_id)) {
                validatedNonFull.add(vp);
              } else if (selected) {
                // keep selected full as cboSelected already
              }
            }

            for (Map<String, Object> line : searchLines) {
              writeJsonl(searchW, line);
              searchRows++;
            }
            doneSearch.add(pq.ir.query_id);
          } else if (opt.phaseSearch) {
            // Resume: search already present; still need structures for exec.
            // Re-run lightweight reconstruction for exec phase.
            QueryEngine.RunResult cboRr = cboEngine.run(pq.ir, null, true, null);
            tPlanCbo = cboRr != null ? cboRr.t_plan_ms : null;
            stopReason = cboRr != null ? cboRr.searchStopReason : null;
            if (cboRr != null && cboRr.selected != null) {
              for (SafePlanHandle h : cboRr.safe) {
                if (h != null && h.plan() != null && h.plan().plan_id != null) {
                  cboSafeIds.add(h.plan().plan_id);
                  cboSafeSigs.add(h.plan().signature());
                }
              }
              ValidatedPlan sel = new ValidatedPlan();
              sel.handle = cboRr.selected;
              sel.envelope = cboRr.selected.plan();
              sel.planId = sel.envelope != null ? sel.envelope.plan_id : null;
              sel.signature = sel.envelope != null ? sel.envelope.signature() : null;
              sel.inCboSafe = true;
              sel.cboSelected = true;
              sel.planned = cboRr;
              cboSelected = sel;
              for (PlanEnvelope env : PlanBuilder.buildCandidatesWithMergeVariants(pq.ir)) {
                if ("P_FULL".equals(env.plan_id) && (sel.planId == null
                    || !sel.planId.equals("P_FULL"))) {
                  continue;
                }
                QueryEngine.RunResult one = fixedEngine.runFixed(pq.ir, null, true, env);
                if (one == null || one.selected == null) {
                  continue;
                }
                ValidatedPlan vp = new ValidatedPlan();
                vp.envelope = env;
                vp.planId = env.plan_id;
                vp.signature = env.signature();
                vp.inCboSafe = cboSafeIds.contains(env.plan_id)
                    || cboSafeSigs.contains(env.signature());
                vp.cboSelected = sel.planId != null && sel.planId.equals(env.plan_id);
                vp.handle = one.selected;
                vp.planned = one;
                if (!"P_FULL".equals(env.plan_id)) {
                  validatedNonFull.add(vp);
                }
              }
            } else {
              searchFailed = true;
            }
          }

          Map<String, Object> qSum = baseQuerySummary(pq);
          qSum.put("cbo_selected_plan_id", cboSelected != null ? cboSelected.planId : null);
          qSum.put("cbo_safe_plan_ids", new ArrayList<String>(cboSafeIds));
          qSum.put("search_stop_reason", stopReason);
          qSum.put("t_plan_cbo_ms", tPlanCbo);
          qSum.put("incomplete_search_trace", Boolean.valueOf(incompleteTrace));
          qSum.put("n_validated_non_full", Integer.valueOf(validatedNonFull.size()));

          if (!opt.phaseExec) {
            qSum.put("labels", Collections.singletonList("search_only"));
            querySummaries.add(qSum);
            continue;
          }

          if (System.currentTimeMillis() > deadlineMs) {
            Map<String, Object> crow = baseMeasRow(pq, null, 0, "censored");
            crow.put("censor_reason", "max_wall_minutes");
            crow.put("phase", "wall_deadline");
            writeJsonl(measW, crow);
            measRows++;
            qSum.put("labels", Collections.singletonList("censored"));
            querySummaries.add(qSum);
            continue;
          }

          if (searchFailed || cboSelected == null) {
            Map<String, Object> crow = baseMeasRow(pq, null, 0, "cbo_fail");
            crow.put("fail_reason", "no_cbo_safe_plan");
            writeJsonl(measW, crow);
            measRows++;
            qSum.put("labels", Collections.singletonList("oracle_or_execution_failure"));
            querySummaries.add(qSum);
            continue;
          }

          OracleChecker.Answer ans = oracle.get(pq.ir.query_id);
          boolean oracleMissing = ans == null;
          List<Long> searchMissSavings = new ArrayList<Long>();
          List<Long> selectMissSavings = new ArrayList<Long>();
          boolean anyOracleFail = oracleMissing;
          boolean anyCensor = false;
          boolean anyInsufficient = false;

          // Build exec list: CBO selected + validated non-full (+ optional fullscan)
          List<ValidatedPlan> toExec = new ArrayList<ValidatedPlan>();
          toExec.add(cboSelected);
          for (ValidatedPlan vp : validatedNonFull) {
            if (vp.cboSelected) {
              continue; // already have selected
            }
            toExec.add(vp);
          }
          if (opt.includeFullscanExec) {
            for (PlanEnvelope env : PlanBuilder.buildCandidatesWithMergeVariants(pq.ir)) {
              if (!"P_FULL".equals(env.plan_id)) {
                continue;
              }
              if (cboSelected.planId != null && "P_FULL".equals(cboSelected.planId)) {
                break;
              }
              QueryEngine.RunResult one = fixedEngine.runFixed(pq.ir, null, true, env);
              if (one != null && one.selected != null) {
                ValidatedPlan vp = new ValidatedPlan();
                vp.envelope = env;
                vp.planId = "P_FULL";
                vp.signature = env.signature();
                vp.inCboSafe = cboSafeIds.contains("P_FULL");
                vp.cboSelected = false;
                vp.handle = one.selected;
                vp.planned = one;
                toExec.add(vp);
              }
              break;
            }
          } else if (cboSelected.planId == null || !"P_FULL".equals(cboSelected.planId)) {
            // Record skipped fullscan baseline
            String key = pq.ir.query_id + "|P_FULL|skip";
            if (!doneMeasKeys.contains(key)) {
              // Not a whole-query censor: FullScan baseline intentionally unmeasured.
              Map<String, Object> skip = baseMeasRow(pq, "P_FULL", 0, "fullscan_unmeasured");
              skip.put("censor_reason", "fullscan_not_selected_budget_skip");
              skip.put("phase", "fullscan_skip");
              skip.put("plan_signature", null);
              skip.put("in_cbo_safe_set", Boolean.valueOf(cboSafeIds.contains("P_FULL")));
              skip.put("cbo_selected", Boolean.FALSE);
              writeJsonl(measW, skip);
              measRows++;
              doneMeasKeys.add(key);
              // do NOT set anyCensor — query itself may still be fully measured
            }
          }

          // Coarse: warmup + timed trial 1 with rotation
          Map<String, Long> coarseExec = new LinkedHashMap<String, Long>();
          int nPlans = toExec.size();
          for (int pos = 0; pos < nPlans; pos++) {
            if (System.currentTimeMillis() > deadlineMs) {
              anyCensor = true;
              break;
            }
            ValidatedPlan vp = toExec.get(pos);
            // warmup
            runExecOnce(fixedEngine, pq, vp, ans, measW, doneMeasKeys, opt, "warmup",
                /*trial*/ 0, /*record*/ false);
            // timed
            Map<String, Object> m = runExecOnce(fixedEngine, pq, vp, ans, measW, doneMeasKeys,
                opt, "coarse", /*trial*/ 0, true);
            measRows++;
            if (m != null && Boolean.TRUE.equals(m.get("ok_oracle"))
                && m.get("t_exec_ms") instanceof Number) {
              coarseExec.put(vp.signature != null ? vp.signature : vp.planId,
                  Long.valueOf(((Number) m.get("t_exec_ms")).longValue()));
            } else if (m != null && "censored".equals(m.get("status"))) {
              anyCensor = true;
            } else if (m != null && "missing_oracle".equals(String.valueOf(m.get("oracle_msg")))) {
              anyOracleFail = true; // counted separately in summary as missing_oracle
            } else {
              anyOracleFail = true;
            }
          }

          Long cboExec = coarseExec.get(cboSelected.signature != null
              ? cboSelected.signature : cboSelected.planId);
          List<ValidatedPlan> repeatTargets = new ArrayList<ValidatedPlan>();
          for (ValidatedPlan vp : toExec) {
            if (vp.cboSelected) {
              repeatTargets.add(vp);
              continue;
            }
            Long ex = coarseExec.get(vp.signature != null ? vp.signature : vp.planId);
            long saving = OpportunityCensusLabels.executionSavingMs(cboExec, ex);
            if (saving >= opt.repeatGainThresholdMs) {
              repeatTargets.add(vp);
            }
          }
          // Always include CBO; if only CBO, still do repeats for baseline stability
          if (repeatTargets.size() == 1 && opt.repeatTrials > 1) {
            // add one no-gain control if any
            for (ValidatedPlan vp : toExec) {
              if (!vp.cboSelected) {
                repeatTargets.add(vp);
                break;
              }
            }
          }

          Map<String, List<Long>> repeatExec = new LinkedHashMap<String, List<Long>>();
          for (int trial = 1; trial <= opt.repeatTrials; trial++) {
            int shift = (trial - 1) % Math.max(1, repeatTargets.size());
            for (int pos = 0; pos < repeatTargets.size(); pos++) {
              if (System.currentTimeMillis() > deadlineMs) {
                anyCensor = true;
                anyInsufficient = true;
                break;
              }
              ValidatedPlan vp = repeatTargets.get((pos + shift) % repeatTargets.size());
              Map<String, Object> m = runExecOnce(fixedEngine, pq, vp, ans, measW, doneMeasKeys,
                  opt, "repeat_t" + trial, /*trial*/ trial, true);
              measRows++;
              if (m != null && Boolean.TRUE.equals(m.get("ok_oracle"))
                  && m.get("t_exec_ms") instanceof Number) {
                String k = vp.signature != null ? vp.signature : vp.planId;
                if (!repeatExec.containsKey(k)) {
                  repeatExec.put(k, new ArrayList<Long>());
                }
                repeatExec.get(k).add(Long.valueOf(((Number) m.get("t_exec_ms")).longValue()));
              } else if (m != null && "censored".equals(m.get("status"))) {
                anyCensor = true;
              } else {
                anyOracleFail = true;
              }
            }
          }

          String cboKey = cboSelected.signature != null
              ? cboSelected.signature : cboSelected.planId;
          Long cboMed = median(repeatExec.get(cboKey));
          if (cboMed == null) {
            cboMed = cboExec;
          }
          for (ValidatedPlan vp : toExec) {
            if (vp.cboSelected) {
              continue;
            }
            String k = vp.signature != null ? vp.signature : vp.planId;
            Long candMed = median(repeatExec.get(k));
            if (candMed == null) {
              candMed = coarseExec.get(k);
            }
            long saving = OpportunityCensusLabels.executionSavingMs(cboMed, candMed);
            if (saving == Long.MIN_VALUE) {
              continue;
            }
            if (!vp.inCboSafe) {
              searchMissSavings.add(Long.valueOf(saving));
            } else {
              selectMissSavings.add(Long.valueOf(saving));
            }
          }

          List<String> labels = OpportunityCensusLabels.labelQuery(
              anyOracleFail, anyCensor, anyInsufficient,
              searchMissSavings, selectMissSavings, opt.hybridExtraPlanMs);
          qSum.put("labels", labels);
          qSum.put("best_search_miss_saving_ms", Long.valueOf(maxPos(searchMissSavings)));
          qSum.put("best_selection_miss_saving_ms", Long.valueOf(maxPos(selectMissSavings)));
          qSum.put("cbo_exec_median_ms", cboMed);
          long bestCand = Math.max(maxPos(searchMissSavings), maxPos(selectMissSavings));
          if (cboMed != null && bestCand > 0L && tPlanCbo != null) {
            // Post-hoc oracle upper bound only
            qSum.put("oracle_upper_bound_ms",
                Long.valueOf(tPlanCbo.longValue() + (cboMed.longValue() - bestCand)));
          }
          querySummaries.add(qSum);
        }
      } finally {
        searchW.close();
        measW.close();
      }
    } finally {
      try {
        kv.close();
      } catch (Exception ignore) {
        //
      }
    }

    Map<String, Object> summary = new LinkedHashMap<String, Object>();
    summary.put("development_diagnostic", Boolean.TRUE);
    summary.put("run_id", opt.runId);
    summary.put("n_queries", Integer.valueOf(pool.size()));
    summary.put("search_rows_written", Integer.valueOf(searchRows));
    summary.put("measurement_rows_written", Integer.valueOf(measRows));
    summary.put("provisional_hybrid_extra_plan_ms", Long.valueOf(opt.hybridExtraPlanMs));
    summary.put("note", "extra_plan_ms unknown in census; net_gain not claimed");
    Map<String, Integer> labelCounts = new LinkedHashMap<String, Integer>();
    int withSearchGain = 0;
    int withSelectGain = 0;
    int noFaster = 0;
    for (Map<String, Object> qs : querySummaries) {
      @SuppressWarnings("unchecked")
      List<String> labs = (List<String>) qs.get("labels");
      if (labs == null) {
        continue;
      }
      for (String l : labs) {
        Integer c = labelCounts.get(l);
        labelCounts.put(l, Integer.valueOf(c == null ? 1 : c.intValue() + 1));
      }
      if (labs.contains("search_miss_with_exec_gain")) {
        withSearchGain++;
      }
      if (labs.contains("selection_miss_with_exec_gain")) {
        withSelectGain++;
      }
      if (labs.contains("no_faster_safe_plan")) {
        noFaster++;
      }
    }
    summary.put("label_counts", labelCounts);
    summary.put("n_search_miss_with_exec_gain", Integer.valueOf(withSearchGain));
    summary.put("n_selection_miss_with_exec_gain", Integer.valueOf(withSelectGain));
    summary.put("n_no_faster_safe_plan", Integer.valueOf(noFaster));
    summary.put("queries", querySummaries);
    Files.write(outDir.resolve("summary.json"),
        MAPPER.writeValueAsBytes(summary));
    writeSummaryMd(outDir.resolve("summary.md"), summary, querySummaries);

    Result r = new Result();
    r.outDir = outDir;
    r.queries = pool.size();
    r.searchRows = searchRows;
    r.measurementRows = measRows;
    r.exitCode = 0;
    return r;
  }

  private static Map<String, Object> runExecOnce(QueryEngine engine, PoolQuery pq,
      ValidatedPlan vp, OracleChecker.Answer ans, BufferedWriter measW,
      Set<String> doneKeys, Options opt, String phase, boolean record) throws Exception {
    return runExecOnce(engine, pq, vp, ans, measW, doneKeys, opt, phase, 0, record);
  }

  private static Map<String, Object> runExecOnce(QueryEngine engine, PoolQuery pq,
      ValidatedPlan vp, OracleChecker.Answer ans, BufferedWriter measW,
      Set<String> doneKeys, Options opt, String phase, int trial, boolean record)
      throws Exception {
    String key = pq.ir.query_id + "|" + vp.planId + "|" + phase + "|"
        + (vp.signature != null ? vp.signature.hashCode() : 0);
    if (doneKeys.contains(key) && record) {
      return null;
    }
    long t0 = System.currentTimeMillis();
    QueryEngine.RunResult exec;
    try {
      exec = engine.executeSelected(pq.ir, null, vp.planned);
    } catch (Exception e) {
      if (!record) {
        return null;
      }
      Map<String, Object> fail = baseMeasRow(pq, vp.planId, trial, "FAIL");
      fail.put("phase", phase);
      fail.put("error", e.getMessage());
      fail.put("ok_oracle", Boolean.FALSE);
      fail.put("in_cbo_safe_set", Boolean.valueOf(vp.inCboSafe));
      fail.put("cbo_selected", Boolean.valueOf(vp.cboSelected));
      fail.put("plan_signature", vp.signature);
      writeJsonl(measW, fail);
      doneKeys.add(key);
      return fail;
    }
    long wall = Math.max(0L, System.currentTimeMillis() - t0);
    if (wall > opt.maxExecMs) {
      // Soft post-check: Coordinator may not cancel mid-flight.
      if (!record) {
        return null;
      }
      Map<String, Object> cen = baseMeasRow(pq, vp.planId, trial, "censored");
      cen.put("phase", phase);
      cen.put("censor_reason", "max_exec_ms");
      cen.put("t_exec_ms", exec != null ? exec.t_exec_ms : Long.valueOf(wall));
      cen.put("wall_ms", Long.valueOf(wall));
      cen.put("ok_oracle", Boolean.FALSE);
      cen.put("in_cbo_safe_set", Boolean.valueOf(vp.inCboSafe));
      cen.put("cbo_selected", Boolean.valueOf(vp.cboSelected));
      cen.put("plan_signature", vp.signature);
      writeJsonl(measW, cen);
      doneKeys.add(key);
      return cen;
    }
    if (!record) {
      return null;
    }
    Map<String, Object> row = baseMeasRow(pq, vp.planId, trial, null);
    row.put("phase", phase);
    row.put("plan_signature", vp.signature);
    row.put("in_cbo_safe_set", Boolean.valueOf(vp.inCboSafe));
    row.put("cbo_selected", Boolean.valueOf(vp.cboSelected));
    row.put("t_plan_validate_ms", Long.valueOf(vp.validateMs));
    row.put("t_exec_ms", exec != null ? exec.t_exec_ms : null);
    row.put("cache", opt.cache);
    row.put("cache_protocol", "warm".equals(opt.cache)
        ? "in_process_warmup_then_timed" : "cold_requested");
    String status = exec != null && exec.result != null ? exec.result.status : "FAIL";
    row.put("status", status);
    boolean okOracle = false;
    String oracleMsg = null;
    if (ans == null) {
      oracleMsg = "missing_oracle";
    } else if (exec == null || exec.result == null) {
      oracleMsg = "null_result";
    } else if ("PLAN_ONLY".equals(exec.result.status)) {
      oracleMsg = "plan_only_not_allowed";
    } else if (exec.t_exec_ms == null) {
      oracleMsg = "missing_t_exec";
    } else {
      oracleMsg = OracleChecker.compare(ans, exec.result);
      okOracle = oracleMsg == null && "OK".equals(exec.result.status);
      if (oracleMsg == null && !"OK".equals(exec.result.status)) {
        oracleMsg = "status=" + exec.result.status;
        okOracle = false;
      }
    }
    row.put("ok_oracle", Boolean.valueOf(okOracle));
    if (oracleMsg != null) {
      row.put("oracle_msg", oracleMsg);
    }
    if (exec != null && exec.result != null && exec.result.trace != null) {
      kart.exec.ExecutionTrace t = exec.result.trace;
      row.put("n_ranges", t.client_ops_count);
      row.put("index_rows", t.totalRows > 0 ? Long.valueOf(t.totalRows) : null);
      row.put("fetch_chunks", t.fetched_chunks);
      row.put("bytes", t.totalBytes > 0 ? Long.valueOf(t.totalBytes) : null);
    }
    if (!okOracle) {
      row.put("status", status == null ? "FAIL" : status);
    }
    writeJsonl(measW, row);
    doneKeys.add(key);
    return row;
  }

  private static QueryEngine newEngine(HBaseBackend kv, LayoutContext layout, StatsSnapshot stats,
                                       AppConfig.PlannerConfig planner, PlannerMode mode) {
    kart.exec.ExecLimits limits = kart.exec.ExecLimits.defaults();
    limits.maxCandidateChunks = (int) Math.min(Integer.MAX_VALUE, planner.max_candidate_chunks);
    limits.maxDtwCells = planner.max_dtw_cells;
    limits.fetchBatch = planner.fetch_batch_size;
    return new QueryEngine(kv, layout, limits, stats, planner, null, mode);
  }

  private static List<PoolQuery> loadAndDedup(Options opt) throws Exception {
    List<PoolQuery> all = new ArrayList<PoolQuery>();
    all.addAll(loadWorkloadQueries(resolve(opt.root, opt.pool), "advantage_v2"));
    if (opt.extraPool != null && Files.isRegularFile(resolve(opt.root, opt.extraPool))) {
      all.addAll(loadCandidates(resolve(opt.root, opt.extraPool), "pilot_candidates"));
    }
    Set<String> excludeFp = new LinkedHashSet<String>();
    if (opt.exclude != null && Files.isRegularFile(resolve(opt.root, opt.exclude))) {
      for (PoolQuery q : loadWorkloadQueries(resolve(opt.root, opt.exclude), "holdout_exclude")) {
        excludeFp.add(q.fingerprint);
      }
    }
    Map<String, PoolQuery> byFp = new LinkedHashMap<String, PoolQuery>();
    for (PoolQuery q : all) {
      if (excludeFp.contains(q.fingerprint)) {
        continue;
      }
      if (!byFp.containsKey(q.fingerprint)) {
        byFp.put(q.fingerprint, q);
      } else {
        PoolQuery keep = byFp.get(q.fingerprint);
        // Prefer keeping advantage layer; annotate near-dup source
        if ("pilot_candidates".equals(q.sourceLayer) && keep.templateHint == null) {
          keep.templateHint = "near_dup_of:" + q.ir.query_id;
        }
      }
    }
    return new ArrayList<PoolQuery>(byFp.values());
  }

  private static List<PoolQuery> loadWorkloadQueries(Path path, String layer) throws Exception {
    JsonNode root = MAPPER.readTree(Files.readAllBytes(path));
    List<PoolQuery> out = new ArrayList<PoolQuery>();
    JsonNode arr = root.get("queries");
    if (arr == null || !arr.isArray()) {
      throw new IllegalStateException("missing queries[]: " + path);
    }
    for (JsonNode q : arr) {
      if (q.has("utterance") && !q.has("ir_version")) {
        continue;
      }
      BoundIr ir = MAPPER.treeToValue(q, BoundIr.class);
      PoolQuery pq = new PoolQuery();
      pq.ir = ir;
      pq.fingerprint = fingerprint(ir);
      pq.sourceLayer = layer;
      pq.sourceQueryId = ir.query_id;
      out.add(pq);
    }
    return out;
  }

  private static List<PoolQuery> loadCandidates(Path path, String layer) throws Exception {
    JsonNode root = MAPPER.readTree(Files.readAllBytes(path));
    List<PoolQuery> out = new ArrayList<PoolQuery>();
    JsonNode arr = root.get("candidates");
    if (arr == null || !arr.isArray()) {
      return out;
    }
    for (JsonNode c : arr) {
      JsonNode q = c.get("query");
      if (q == null) {
        continue;
      }
      BoundIr ir = MAPPER.treeToValue(q, BoundIr.class);
      PoolQuery pq = new PoolQuery();
      pq.ir = ir;
      pq.fingerprint = c.has("fingerprint") ? c.get("fingerprint").asText() : fingerprint(ir);
      pq.sourceLayer = layer;
      pq.sourceQueryId = c.has("source_query_id") ? c.get("source_query_id").asText() : ir.query_id;
      pq.category = c.has("category") ? c.get("category").asText() : null;
      out.add(pq);
    }
    return out;
  }

  /** SHA-256 of BoundIR JSON without query_id (sorted keys). */
  public static String fingerprint(BoundIr ir) {
    try {
      ObjectNode body = MAPPER.valueToTree(ir);
      body.remove("query_id");
      String canon = MAPPER.writeValueAsString(body);
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] dig = md.digest(canon.getBytes(StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder();
      for (byte b : dig) {
        sb.append(String.format("%02x", Integer.valueOf(b & 0xff)));
      }
      return sb.toString();
    } catch (Exception e) {
      throw new IllegalStateException("fingerprint failed", e);
    }
  }

  private static Path resolve(Path root, Path p) {
    if (p == null) {
      return null;
    }
    return p.isAbsolute() ? p : root.resolve(p);
  }

  private static Map<String, Object> baseSearchRow(PoolQuery pq, String kind) {
    Map<String, Object> row = new LinkedHashMap<String, Object>();
    row.put("kind", kind);
    row.put("query_id", pq.ir.query_id);
    row.put("fingerprint", pq.fingerprint);
    row.put("source_layer", pq.sourceLayer);
    row.put("source_query_id", pq.sourceQueryId);
    row.put("category", pq.category);
    return row;
  }

  private static Map<String, Object> baseMeasRow(PoolQuery pq, String planId, int trial,
                                                 String status) {
    Map<String, Object> row = new LinkedHashMap<String, Object>();
    row.put("query_id", pq.ir.query_id);
    row.put("fingerprint", pq.fingerprint);
    row.put("source_layer", pq.sourceLayer);
    row.put("trial", Integer.valueOf(trial));
    row.put("plan_id", planId);
    if (status != null) {
      row.put("status", status);
    }
    return row;
  }

  private static Map<String, Object> baseQuerySummary(PoolQuery pq) {
    Map<String, Object> row = new LinkedHashMap<String, Object>();
    row.put("query_id", pq.ir.query_id);
    row.put("fingerprint", pq.fingerprint);
    row.put("source_layer", pq.sourceLayer);
    row.put("source_query_id", pq.sourceQueryId);
    row.put("category", pq.category);
    return row;
  }

  private static void writeCensoredSearch(BufferedWriter w, PoolQuery pq, String reason)
      throws IOException {
    Map<String, Object> row = baseSearchRow(pq, "censored");
    row.put("status", "censored");
    row.put("censor_reason", reason);
    writeJsonl(w, row);
  }

  private static void writeJsonl(BufferedWriter w, Map<String, Object> row) throws IOException {
    w.write(JSONL.writeValueAsString(row));
    w.write('\n');
    w.flush();
  }

  private static Set<String> loadDoneQueryIds(Path path, String field) throws IOException {
    Set<String> out = new LinkedHashSet<String>();
    if (!Files.isRegularFile(path)) {
      return out;
    }
    for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
      if (line.trim().isEmpty()) {
        continue;
      }
      JsonNode n = MAPPER.readTree(line);
      if (n.has(field) && "cbo_search".equals(n.path("kind").asText(null))) {
        out.add(n.get(field).asText());
      }
    }
    return out;
  }

  private static Set<String> loadDoneKeys(Path path) throws IOException {
    Set<String> out = new LinkedHashSet<String>();
    if (!Files.isRegularFile(path)) {
      return out;
    }
    for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
      if (line.trim().isEmpty()) {
        continue;
      }
      JsonNode n = MAPPER.readTree(line);
      String q = n.path("query_id").asText("");
      String p = n.path("plan_id").asText("");
      String ph = n.path("phase").asText(n.path("status").asText(""));
      out.add(q + "|" + p + "|" + ph + "|" + n.path("plan_signature").asText("").hashCode());
      if ("censored".equals(n.path("status").asText()) && "P_FULL".equals(p)) {
        out.add(q + "|P_FULL|skip");
      }
    }
    return out;
  }

  private static Long median(List<Long> xs) {
    if (xs == null || xs.isEmpty()) {
      return null;
    }
    List<Long> copy = new ArrayList<Long>(xs);
    Collections.sort(copy);
    return copy.get(copy.size() / 2);
  }

  private static long maxPos(List<Long> xs) {
    long m = 0L;
    if (xs == null) {
      return 0L;
    }
    for (Long x : xs) {
      if (x != null && x.longValue() > m) {
        m = x.longValue();
      }
    }
    return m;
  }

  private static void writeMeta(Path outDir, Options opt, List<PoolQuery> pool, int oracleSize,
                                int missingOracle, Manifest manifest, AppConfig cfg,
                                Map<String, Object> hbaseEnv) throws Exception {
    Map<String, Object> meta = new LinkedHashMap<String, Object>();
    meta.put("development_diagnostic", Boolean.TRUE);
    meta.put("run_id", opt.runId);
    meta.put("created_at", new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss").format(new Date()));
    meta.put("manifest_id", opt.manifestId);
    meta.put("cache", opt.cache);
    meta.put("initial_trials", Integer.valueOf(opt.initialTrials));
    meta.put("repeat_trials", Integer.valueOf(opt.repeatTrials));
    meta.put("max_exec_ms", Long.valueOf(opt.maxExecMs));
    meta.put("max_wall_minutes", Long.valueOf(opt.maxWallMinutes));
    meta.put("hybrid_extra_plan_ms_provisional", Long.valueOf(opt.hybridExtraPlanMs));
    meta.put("repeat_gain_threshold_ms", Long.valueOf(opt.repeatGainThresholdMs));
    meta.put("n_queries_deduped", Integer.valueOf(pool.size()));
    meta.put("oracle_entries_loaded", Integer.valueOf(oracleSize));
    meta.put("queries_missing_oracle", Integer.valueOf(missingOracle));
    meta.put("include_fullscan_exec", Boolean.valueOf(opt.includeFullscanExec));
    meta.put("phase_search", Boolean.valueOf(opt.phaseSearch));
    meta.put("phase_exec", Boolean.valueOf(opt.phaseExec));
    meta.put("hbase_env", hbaseEnv);
    meta.put("git", gitMeta(opt.root));
    if (manifest != null) {
      meta.put("manifest_status", String.valueOf(manifest.status));
    }
    Files.write(outDir.resolve("meta.json"), MAPPER.writeValueAsBytes(meta));
  }

  private static Map<String, Object> gitMeta(Path root) {
    Map<String, Object> g = new LinkedHashMap<String, Object>();
    try {
      Process p = new ProcessBuilder("git", "rev-parse", "HEAD").directory(root.toFile())
          .redirectErrorStream(true).start();
      String commit = new String(readAll(p.getInputStream()), StandardCharsets.UTF_8).trim();
      p.waitFor();
      g.put("commit", commit);
      Process p2 = new ProcessBuilder("git", "status", "--porcelain").directory(root.toFile())
          .redirectErrorStream(true).start();
      String dirty = new String(readAll(p2.getInputStream()), StandardCharsets.UTF_8);
      p2.waitFor();
      g.put("dirty", Boolean.valueOf(!dirty.trim().isEmpty()));
    } catch (Exception e) {
      g.put("error", e.getMessage());
    }
    return g;
  }

  private static byte[] readAll(java.io.InputStream in) throws IOException {
    java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
    byte[] buf = new byte[4096];
    int n;
    while ((n = in.read(buf)) >= 0) {
      bos.write(buf, 0, n);
    }
    return bos.toByteArray();
  }

  private static void writeSummaryMd(Path path, Map<String, Object> summary,
                                     List<Map<String, Object>> queries) throws IOException {
    StringBuilder sb = new StringBuilder();
    sb.append("# Opportunity census ").append(summary.get("run_id")).append('\n');
    sb.append('\n');
    sb.append("> development_diagnostic — not a formal E2/E3 result; ")
        .append("net_gain not claimed (LLM not run).\n\n");
    sb.append("- queries: ").append(summary.get("n_queries")).append('\n');
    sb.append("- label_counts: ").append(summary.get("label_counts")).append('\n');
    sb.append("- provisional hybrid extra_plan_ms: ")
        .append(summary.get("provisional_hybrid_extra_plan_ms")).append('\n');
    sb.append('\n');
    sb.append("| query_id | labels | best_search_miss | best_selection_miss | cbo_exec_med |\n");
    sb.append("|---|---|---:|---:|---:|\n");
    for (Map<String, Object> q : queries) {
      sb.append("| ").append(q.get("query_id"))
          .append(" | ").append(q.get("labels"))
          .append(" | ").append(q.get("best_search_miss_saving_ms"))
          .append(" | ").append(q.get("best_selection_miss_saving_ms"))
          .append(" | ").append(q.get("cbo_exec_median_ms"))
          .append(" |\n");
    }
    Files.write(path, sb.toString().getBytes(StandardCharsets.UTF_8));
  }
}
