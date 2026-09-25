package kart.query;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import kart.catalog.StatsSnapshot;
import kart.compile.LayoutContext;
import kart.compile.PhysicalPlan;
import kart.compile.QueryCompiler;
import kart.config.AppConfig;
import kart.cost.CostCard;
import kart.cost.CostFeatures;
import kart.cost.CostFeaturesExtractor;
import kart.cost.CostModel;
import kart.cost.FastCost;
import kart.cost.HBaseRegionMapping;
import kart.cost.PlanSelector;
import kart.cost.RegionMapping;
import kart.exec.Coordinator;
import kart.exec.ExecLimits;
import kart.exec.ExecutionTrace;
import kart.exec.HBaseBackend;
import kart.exec.KvBackend;
import kart.exec.QueryResult;
import kart.ir.BoundIr;
import kart.llm.LlmClient;
import kart.llm.LlmUsageAccumulator;
import kart.plan.PlanEnvelope;
import kart.search.BeamSearch;
import kart.search.BestFirstPolicy;
import kart.search.LlmDirectPlanPlanner;
import kart.search.LlmProposalPolicy;
import kart.search.PlannerMode;
import kart.search.ProposalPolicy;
import kart.search.RulePolicy;
import kart.search.SearchBudget;
import kart.search.SearchLog;
import kart.search.SearchResult;
import kart.validation.SafePlanHandle;
import kart.validation.PlanValidator;
import kart.validation.ValidationReport;
import kart.util.StatusLog;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * End-to-end BoundIR → plan search → Final cost-select → execute (or plan-only).
 * Policy: {@link PlannerMode} ({@code rule} / {@code best_first} / {@code llm} / {@code llm_direct}).
 */
public final class QueryEngine {

  private static final ObjectMapper MAPPER = new ObjectMapper()
      .enable(SerializationFeature.INDENT_OUTPUT);

  public static final class RunResult {
    public BoundIr ir;
    public List<PlanEnvelope> candidates = new ArrayList<PlanEnvelope>();
    public List<SafePlanHandle> safe = new ArrayList<SafePlanHandle>();
    public List<CostCard> costCards = new ArrayList<CostCard>();
    public List<ValidationReport> rejectionReports = new ArrayList<ValidationReport>();
    /** Full reject records (plan_id + report) for NFR-3 artifacts. */
    public List<SearchResult.Rejection> rejections = new ArrayList<SearchResult.Rejection>();
    public SafePlanHandle selected;
    public CostCard selectedCost;
    public QueryResult result;
    public Path runDir;
    public SearchLog searchLog;
    public String searchStopReason;
    /** Wall ms: search start → select end (E2 {@code t_plan}). */
    public Long t_plan_ms;
    /** Wall ms: Coordinator execute only (E3 {@code t_exec}); null when plan-only. */
    public Long t_exec_ms;
    public long planStartEpochMs;
    public long planEndEpochMs;
    public boolean llmRequested;
    public boolean llmFallback;
    public String fallbackReason;
    /** {@code selected.estimated_ms - min(safe.estimated_ms)}; 0 under Final Cost select. */
    public Double plan_regret_ms;
    public Double best_safe_estimated_ms;
    public PlannerMode plannerMode;
    public boolean planOnly;
  }

  private final KvBackend kv;
  private final LayoutContext layout;
  private final ExecLimits limits;
  private final StatsSnapshot stats;
  private final CostModel costModel;
  private final AppConfig.PlannerConfig planner;
  private final LlmClient llm;
  private final RegionMapping regionMapping;
  private PlannerMode plannerMode;
  private LlmUsageAccumulator usage = new LlmUsageAccumulator();

  public QueryEngine(KvBackend kv, LayoutContext layout) {
    this(kv, layout, ExecLimits.defaults(), null, null, null, null);
  }

  public QueryEngine(KvBackend kv, LayoutContext layout, ExecLimits limits) {
    this(kv, layout, limits, null, null, null, null);
  }

  /** RulePolicy planning (no LLM); uses default planner budget + given cost coeffs. */
  public QueryEngine(KvBackend kv, LayoutContext layout, ExecLimits limits,
                     StatsSnapshot stats, AppConfig.CostCoeffs coeffs) {
    this(kv, layout, limits, stats, plannerWithCost(coeffs), null, PlannerMode.RULE);
  }

  public QueryEngine(KvBackend kv, LayoutContext layout, ExecLimits limits,
                     StatsSnapshot stats, AppConfig.PlannerConfig planner, LlmClient llm) {
    this(kv, layout, limits, stats, planner, llm, null);
  }

  public QueryEngine(KvBackend kv, LayoutContext layout, ExecLimits limits,
                     StatsSnapshot stats, AppConfig.PlannerConfig planner, LlmClient llm,
                     PlannerMode mode) {
    this.kv = kv;
    this.layout = layout;
    this.planner = planner != null ? planner : AppConfig.PlannerConfig.defaults();
    ExecLimits fromCost = ExecLimits.fromCostCoeffs(this.planner.cost);
    if (limits == null) {
      this.limits = fromCost;
    } else {
      this.limits = limits;
      this.limits.indexParallelism = fromCost.indexParallelism;
      this.limits.scanParallelism = fromCost.scanParallelism;
      this.limits.scanParallelismPerRs = fromCost.scanParallelismPerRs;
      this.limits.fetchParallelism = fromCost.fetchParallelism;
    }
    this.stats = stats;
    this.regionMapping = buildRegionMapping(kv);
    this.costModel = new CostModel(this.planner.cost != null
        ? this.planner.cost : new AppConfig.CostCoeffs(), this.regionMapping);
    this.llm = llm;
    this.plannerMode = mode != null ? mode : defaultMode(llm);
  }

  private static PlannerMode defaultMode(LlmClient llm) {
    return llm != null ? PlannerMode.LLM : PlannerMode.RULE;
  }

  private static RegionMapping buildRegionMapping(KvBackend kv) {
    if (kv instanceof HBaseBackend) {
      return new HBaseRegionMapping(((HBaseBackend) kv).connection());
    }
    return new RegionMapping.ShardFallback();
  }

  public void setPlannerMode(PlannerMode mode) {
    if (mode != null) {
      this.plannerMode = mode;
    }
  }

  public PlannerMode plannerMode() {
    return plannerMode;
  }

  /** Optional shared accumulator (e.g. from Dialog NL parse). */
  public void setUsageAccumulator(LlmUsageAccumulator usage) {
    this.usage = usage != null ? usage : new LlmUsageAccumulator();
  }

  public LlmUsageAccumulator usageAccumulator() {
    return usage;
  }

  private static AppConfig.PlannerConfig plannerWithCost(AppConfig.CostCoeffs coeffs) {
    AppConfig.PlannerConfig p = AppConfig.PlannerConfig.defaults();
    if (coeffs != null) {
      p.cost = coeffs;
    }
    return p;
  }

  public RunResult run(BoundIr ir, Path runsRoot) throws IOException {
    return run(ir, runsRoot, false, null);
  }

  /**
   * @param planOnly when true, stop after plan select (E2); status {@code PLAN_ONLY}, no Coordinator
   */
  public RunResult run(BoundIr ir, Path runsRoot, boolean planOnly) throws IOException {
    return run(ir, runsRoot, planOnly, null);
  }

  /**
   * @param forcePlanId when non-null, keep only this {@code plan_id} among SafePlans before select
   *                    (bench {@code fullscan} arm uses {@code P_FULL}); default query paths pass null
   */
  public RunResult run(BoundIr ir, Path runsRoot, boolean planOnly, String forcePlanId)
      throws IOException {
    RunResult rr = new RunResult();
    rr.ir = ir;
    rr.plannerMode = plannerMode;
    rr.planOnly = planOnly;
    StatusLog.info("ENGINE", "beam search for query_id="
        + (ir == null ? "?" : ir.query_id)
        + " policy=" + plannerMode.wireName()
        + (planOnly ? " plan_only=true" : ""));

    long planStart = System.currentTimeMillis();
    SearchBudget budget = SearchBudget.from(planner);
    FastCost fastCost = new FastCost(layout, stats, planner.cost, regionMapping);
    SearchResult searchResult = runSearch(ir, budget, fastCost);
    rr.candidates = searchResult.candidates;
    rr.safe = new ArrayList<SafePlanHandle>(searchResult.safePlans);
    rr.searchLog = searchResult.log;
    rr.searchStopReason = budget.stopReason();
    annotateLlmFallback(rr);
    for (SearchResult.Rejection rej : searchResult.rejections) {
      rr.rejections.add(rej);
      if (rej.report != null) {
        rr.rejectionReports.add(rej.report);
      }
    }
    StatusLog.info("ENGINE", "candidates=" + rr.candidates.size()
        + " safe=" + rr.safe.size()
        + " stop=" + (rr.searchStopReason == null ? "OK" : rr.searchStopReason));

    CostFeaturesExtractor extractor = new CostFeaturesExtractor(layout, stats, regionMapping,
        planner.cost != null && planner.cost.soft_memory_bytes > 0
            ? planner.cost.soft_memory_bytes : limits.softMemoryBytes);
    List<PlanSelector.Scored> scored = new ArrayList<PlanSelector.Scored>();
    CostFeatures selectedFeatures = null;
    double bestMs = Double.POSITIVE_INFINITY;
    for (SafePlanHandle h : rr.safe) {
      String pid = h.plan() == null || h.plan().plan_id == null ? "?" : h.plan().plan_id;
      CostFeatures features = extractor.extractFinal(h.physicalPlan(), h.plan(), ir);
      CostCard card = costModel.estimateFinal(features);
      rr.costCards.add(card);
      scored.add(new PlanSelector.Scored(h, card));
      if (card.estimated_ms < bestMs) {
        bestMs = card.estimated_ms;
      }
      StatusLog.info("VALIDATE", "SAFE plan_id=" + pid
          + " estimated_ms=" + card.estimated_ms);
    }
    if (!Double.isInfinite(bestMs)) {
      rr.best_safe_estimated_ms = Double.valueOf(bestMs);
    }

    if (forcePlanId != null && !forcePlanId.trim().isEmpty()) {
      String want = forcePlanId.trim();
      List<PlanSelector.Scored> forced = new ArrayList<PlanSelector.Scored>();
      for (PlanSelector.Scored s : scored) {
        if (s.handle != null && s.handle.plan() != null
            && want.equals(s.handle.plan().plan_id)) {
          forced.add(s);
        }
      }
      if (forced.isEmpty()) {
        QueryResult fail = new QueryResult();
        fail.status = "NO_SAFE_PLAN";
        fail.error = "forcePlanId=" + want + " not in safe set (" + scored.size() + ")";
        attachPlanMetrics(fail, rr);
        rr.result = fail;
        stampPlanClock(rr, planStart);
        if (runsRoot != null) {
          rr.runDir = writeArtifacts(runsRoot, ir, null, null, null, rr.costCards, fail,
              rr.searchLog, rr.searchStopReason, null, rr.rejectionReports, null, rr);
        }
        return rr;
      }
      scored = forced;
    }

    // LLM_DIRECT: keep the (sole) validated LLM plan when present; else Final Cost select.
    PlanSelector.Scored best;
    if (forcePlanId == null
        && plannerMode == PlannerMode.LLM_DIRECT && scored.size() == 1
        && searchResult.log != null && isDirectAccept(searchResult.log)) {
      best = scored.get(0);
      // Regret vs best SafePlan in the constructor family (baseline #5 metric).
      refineDirectRegret(ir, extractor, rr, best);
    } else {
      best = PlanSelector.selectScored(scored);
    }
    rr.selected = best == null ? null : best.handle;
    rr.selectedCost = best == null ? null : best.card;
    if (rr.selected != null) {
      selectedFeatures = extractor.extractFinal(
          rr.selected.physicalPlan(), rr.selected.plan(), ir);
    }
    if (rr.selectedCost != null && rr.best_safe_estimated_ms != null) {
      rr.plan_regret_ms = Double.valueOf(
          rr.selectedCost.estimated_ms - rr.best_safe_estimated_ms.doubleValue());
    }

    stampPlanClock(rr, planStart);

    if (rr.selected == null) {
      QueryResult fail = new QueryResult();
      fail.status = "NO_SAFE_PLAN";
      fail.error = "no safe plan among " + rr.candidates.size() + " candidates"
          + (rr.searchStopReason == null ? "" : " (search=" + rr.searchStopReason + ")");
      attachPlanMetrics(fail, rr);
      rr.result = fail;
      StatusLog.info("SELECT", "no safe plan");
      if (runsRoot != null) {
        rr.runDir = writeArtifacts(runsRoot, ir, null, null, null, rr.costCards, fail,
            rr.searchLog, rr.searchStopReason, null, rr.rejectionReports, null, rr);
      }
      return rr;
    }

    String selId = rr.selected.plan() == null || rr.selected.plan().plan_id == null
        ? "?" : rr.selected.plan().plan_id;
    StatusLog.info("SELECT", "chosen plan_id=" + selId
        + " estimated_ms="
        + (rr.selectedCost == null ? "?" : String.valueOf(rr.selectedCost.estimated_ms))
        + " regret_ms=" + (rr.plan_regret_ms == null ? "?" : String.valueOf(rr.plan_regret_ms))
        + " calibrated=" + costModel.calibrated()
        + " t_plan_ms=" + rr.t_plan_ms);

    if (planOnly) {
      QueryResult planned = new QueryResult();
      planned.status = "PLAN_ONLY";
      attachPlanMetrics(planned, rr);
      rr.result = planned;
      rr.t_exec_ms = null;
      if (runsRoot != null) {
        rr.runDir = writeArtifacts(runsRoot, ir, rr.selected.plan(), rr.selected.physicalPlan(),
            rr.selectedCost, rr.costCards, planned, rr.searchLog, rr.searchStopReason,
            rr.selected.coverageCertificates(), rr.rejectionReports, selectedFeatures, rr);
        StatusLog.info("ARTIFACTS", "runDir=" + rr.runDir);
      }
      return rr;
    }

    StatusLog.info("EXEC", "Coordinator executing…");
    long execStart = System.currentTimeMillis();
    Coordinator coord = new Coordinator(kv, ir, layout, limits, regionMapping);
    if (selectedFeatures != null) {
      coord.applyCostEstimates(selectedFeatures);
    }
    rr.result = coord.execute(rr.selected);
    rr.t_exec_ms = Long.valueOf(Math.max(0L, System.currentTimeMillis() - execStart));
    attachPlanMetrics(rr.result, rr);
    if (rr.result != null && rr.result.trace != null) {
      rr.result.trace.llm_calls = usage.callsAsLongOrNull();
      rr.result.trace.llm_tokens = usage.tokensOrNull();
    }
    StatusLog.info("EXEC", "done status="
        + (rr.result == null ? "?" : rr.result.status)
        + " t_exec_ms=" + rr.t_exec_ms
        + " ids="
        + (rr.result == null || rr.result.trajectoryIds == null
            ? 0 : rr.result.trajectoryIds.size()));

    if (runsRoot != null) {
      rr.runDir = writeArtifacts(runsRoot, ir, rr.selected.plan(), rr.selected.physicalPlan(),
          rr.selectedCost, rr.costCards, rr.result, rr.searchLog, rr.searchStopReason,
          rr.selected.coverageCertificates(), rr.rejectionReports, selectedFeatures, rr);
      StatusLog.info("ARTIFACTS", "runDir=" + rr.runDir);
    }
    return rr;
  }

  /**
   * Execute exactly one caller-selected plan envelope.
   *
   * <p>This is deliberately separate from {@link #run}: fixed baseline arms
   * (for example FullScan and a published rule template) must not enumerate a
   * candidate family and then force the selected id.  The envelope is still
   * compiled and checked by the same compiler/validator before execution.</p>
   */
  public RunResult runFixed(BoundIr ir, Path runsRoot, boolean planOnly,
                            PlanEnvelope envelope) throws IOException {
    RunResult rr = new RunResult();
    rr.ir = ir;
    rr.plannerMode = plannerMode;
    rr.planOnly = planOnly;
    long planStart = System.currentTimeMillis();
    if (ir == null || envelope == null) {
      stampPlanClock(rr, planStart);
      QueryResult fail = new QueryResult();
      fail.status = "NO_SAFE_PLAN";
      fail.error = "fixed plan or BoundIR is null";
      rr.result = fail;
      return rr;
    }
    rr.candidates.add(envelope);
    QueryCompiler compiler = new QueryCompiler(layout);
    PhysicalPlan physical;
    try {
      physical = compiler.compile(envelope, ir);
    } catch (RuntimeException e) {
      ValidationReport report = new ValidationReport();
      report.fail("Compile", e.getMessage() == null ? "compile failed" : e.getMessage());
      rr.rejections.add(new SearchResult.Rejection(envelope.plan_id, envelope.signature(), report));
      rr.rejectionReports.add(report);
      stampPlanClock(rr, planStart);
      QueryResult fail = new QueryResult();
      fail.status = "NO_SAFE_PLAN";
      fail.error = "fixed plan compile failed";
      attachPlanMetrics(fail, rr);
      rr.result = fail;
      return rr;
    }
    ValidationReport report = new ValidationReport();
    Optional<SafePlanHandle> checked = new PlanValidator(layout).validate(
        envelope, ir, physical, report);
    if (!checked.isPresent()) {
      rr.rejections.add(new SearchResult.Rejection(envelope.plan_id, envelope.signature(), report));
      rr.rejectionReports.add(report);
      stampPlanClock(rr, planStart);
      QueryResult failV = new QueryResult();
      failV.status = "NO_SAFE_PLAN";
      failV.error = "fixed plan rejected by validator";
      attachPlanMetrics(failV, rr);
      rr.result = failV;
      return rr;
    }
    SafePlanHandle selected = checked.get();
    rr.safe.add(selected);
    rr.selected = selected;
    CostFeaturesExtractor extractor = new CostFeaturesExtractor(layout, stats, regionMapping,
        planner.cost != null && planner.cost.soft_memory_bytes > 0
            ? planner.cost.soft_memory_bytes : limits.softMemoryBytes);
    CostFeatures features = extractor.extractFinal(selected.physicalPlan(), selected.plan(), ir);
    rr.selectedCost = costModel.estimateFinal(features);
    rr.costCards.add(rr.selectedCost);
    rr.best_safe_estimated_ms = Double.valueOf(rr.selectedCost.estimated_ms);
    rr.plan_regret_ms = Double.valueOf(0.0);
    stampPlanClock(rr, planStart);

    if (planOnly) {
      QueryResult planned = new QueryResult();
      planned.status = "PLAN_ONLY";
      attachPlanMetrics(planned, rr);
      rr.result = planned;
      rr.t_exec_ms = null;
      if (runsRoot != null) {
        rr.runDir = writeArtifacts(runsRoot, ir, selected.plan(), selected.physicalPlan(),
            rr.selectedCost, rr.costCards, planned, null, "FIXED_PLAN",
            selected.coverageCertificates(), rr.rejectionReports, features, rr);
      }
      return rr;
    }

    long execStart = System.currentTimeMillis();
    Coordinator coord = new Coordinator(kv, ir, layout, limits, regionMapping);
    coord.applyCostEstimates(features);
    rr.result = coord.execute(selected);
    rr.t_exec_ms = Long.valueOf(Math.max(0L, System.currentTimeMillis() - execStart));
    attachPlanMetrics(rr.result, rr);
    if (rr.result != null && rr.result.trace != null) {
      rr.result.trace.llm_calls = usage.callsAsLongOrNull();
      rr.result.trace.llm_tokens = usage.tokensOrNull();
    }
    if (runsRoot != null) {
      rr.runDir = writeArtifacts(runsRoot, ir, selected.plan(), selected.physicalPlan(),
          rr.selectedCost, rr.costCards, rr.result, null, "FIXED_PLAN",
          selected.coverageCertificates(), rr.rejectionReports, features, rr);
    }
    return rr;
  }

  /**
   * Execute an already-selected SafePlan without another candidate search.
   * Used by transplant selectors (Bao) so generate→select is counted once.
   * {@code t_plan_ms} is copied from {@code planned}; this method only fills
   * {@code t_exec_ms}.
   */
  public RunResult executeSelected(BoundIr ir, Path runsRoot, RunResult planned)
      throws IOException {
    RunResult rr = new RunResult();
    rr.ir = ir;
    rr.plannerMode = plannerMode;
    rr.planOnly = false;
    if (planned != null) {
      rr.candidates = planned.candidates;
      rr.safe = planned.safe;
      rr.costCards = planned.costCards;
      rr.rejections = planned.rejections;
      rr.rejectionReports = planned.rejectionReports;
      rr.selected = planned.selected;
      rr.selectedCost = planned.selectedCost;
      rr.searchLog = planned.searchLog;
      rr.searchStopReason = planned.searchStopReason;
      rr.best_safe_estimated_ms = planned.best_safe_estimated_ms;
      rr.plan_regret_ms = planned.plan_regret_ms;
      rr.llmRequested = planned.llmRequested;
      rr.llmFallback = planned.llmFallback;
      rr.fallbackReason = planned.fallbackReason;
      rr.t_plan_ms = planned.t_plan_ms;
      rr.planStartEpochMs = planned.planStartEpochMs;
      rr.planEndEpochMs = planned.planEndEpochMs;
    }
    if (rr.selected == null) {
      QueryResult fail = new QueryResult();
      fail.status = "NO_SAFE_PLAN";
      fail.error = "executeSelected: no selected plan";
      attachPlanMetrics(fail, rr);
      rr.result = fail;
      return rr;
    }
    CostFeaturesExtractor extractor = new CostFeaturesExtractor(layout, stats, regionMapping,
        planner.cost != null && planner.cost.soft_memory_bytes > 0
            ? planner.cost.soft_memory_bytes : limits.softMemoryBytes);
    CostFeatures features = extractor.extractFinal(
        rr.selected.physicalPlan(), rr.selected.plan(), ir);
    long execStart = System.currentTimeMillis();
    Coordinator coord = new Coordinator(kv, ir, layout, limits, regionMapping);
    coord.applyCostEstimates(features);
    rr.result = coord.execute(rr.selected);
    rr.t_exec_ms = Long.valueOf(Math.max(0L, System.currentTimeMillis() - execStart));
    attachPlanMetrics(rr.result, rr);
    if (rr.result != null && rr.result.trace != null) {
      rr.result.trace.llm_calls = usage.callsAsLongOrNull();
      rr.result.trace.llm_tokens = usage.tokensOrNull();
    }
    if (runsRoot != null) {
      rr.runDir = writeArtifacts(runsRoot, ir, rr.selected.plan(), rr.selected.physicalPlan(),
          rr.selectedCost, rr.costCards, rr.result, rr.searchLog, rr.searchStopReason,
          rr.selected.coverageCertificates(), rr.rejectionReports, features, rr);
    }
    return rr;
  }

  private SearchResult runSearch(BoundIr ir, SearchBudget budget, FastCost fastCost) {
    if (plannerMode == PlannerMode.LLM_DIRECT) {
      if (llm == null) {
        StatusLog.info("ENGINE", "llm_direct without LlmClient → RulePolicy fallback");
        return new BeamSearch(layout, fastCost).search(ir, new RulePolicy(), budget);
      }
      LlmDirectPlanPlanner direct = new LlmDirectPlanPlanner(layout, fastCost, llm);
      direct.setUsageAccumulator(usage);
      return direct.plan(ir, budget);
    }
    BeamSearch search = new BeamSearch(layout, fastCost);
    ProposalPolicy policy = buildProposalPolicy(fastCost);
    return search.search(ir, policy, budget);
  }

  private ProposalPolicy buildProposalPolicy(FastCost fastCost) {
    switch (plannerMode) {
      case BEST_FIRST:
        return new BestFirstPolicy(fastCost);
      case LLM:
        if (llm != null) {
          LlmProposalPolicy llmPolicy = new LlmProposalPolicy(llm);
          llmPolicy.setUsageAccumulator(usage);
          return llmPolicy;
        }
        StatusLog.info("ENGINE", "llm mode without client → RulePolicy");
        return new RulePolicy();
      case RULE:
      default:
        return new RulePolicy();
    }
  }

  private static boolean isDirectAccept(SearchLog log) {
    if (log == null || log.steps().isEmpty()) {
      return false;
    }
    for (SearchLog.Step s : log.steps()) {
      if (s != null && s.event != null
          && (s.event.equals("llm_direct") || s.event.equals("llm_direct_envelope"))) {
        return true;
      }
    }
    return false;
  }

  /**
   * For LLM_DIRECT accept: recompute {@code best_safe_estimated_ms} over the full
   * constructor family so plan_regret_ms = selected − family-best (not trivially 0).
   */
  private void refineDirectRegret(BoundIr ir, CostFeaturesExtractor extractor,
                                  RunResult rr, PlanSelector.Scored selected) {
    if (selected == null || selected.card == null) {
      return;
    }
    BeamSearch beam = new BeamSearch(layout, new FastCost(layout, stats, planner.cost, regionMapping));
    SearchResult family = new SearchResult();
    for (PlanEnvelope env : kart.plan.PlanBuilder.buildCandidatesWithMergeVariants(ir)) {
      beam.validateInjected(ir, env, family);
    }
    double bestMs = selected.card.estimated_ms;
    for (SafePlanHandle h : family.safePlans) {
      CostFeatures features = extractor.extractFinal(h.physicalPlan(), h.plan(), ir);
      CostCard card = costModel.estimateFinal(features);
      if (card.estimated_ms < bestMs) {
        bestMs = card.estimated_ms;
      }
    }
    rr.best_safe_estimated_ms = Double.valueOf(bestMs);
  }

  private static void stampPlanClock(RunResult rr, long planStart) {
    long now = System.currentTimeMillis();
    rr.t_plan_ms = Long.valueOf(Math.max(0L, now - planStart));
    rr.planStartEpochMs = planStart;
    rr.planEndEpochMs = now;
  }

  private void annotateLlmFallback(RunResult rr) {
    rr.llmRequested = plannerMode == PlannerMode.LLM || plannerMode == PlannerMode.LLM_DIRECT;
    if (!rr.llmRequested) {
      return;
    }
    if (llm == null) {
      rr.llmFallback = true;
      rr.fallbackReason = "no_llm_client";
      return;
    }
    if (rr.searchLog != null && rr.searchLog.hasEvent("llm_direct_fallback")) {
      rr.llmFallback = true;
      rr.fallbackReason = "llm_direct_fallback";
      return;
    }
    if (usage == null || usage.calls() == 0) {
      rr.llmFallback = true;
      rr.fallbackReason = "zero_llm_calls";
      return;
    }
    if (entirelyRuleFallback(rr.searchLog)) {
      rr.llmFallback = true;
      rr.fallbackReason = "rule_fallback";
      return;
    }
    // Spec: any RulePolicy substitution is not merged into the KART LLM arm.
    if (rr.searchLog != null && rr.searchLog.hasEvent("illegal_action")) {
      rr.llmFallback = true;
      rr.fallbackReason = "illegal_action";
      return;
    }
    if (rr.searchLog != null && rr.searchLog.hasEvent("rule_fallback")) {
      rr.llmFallback = true;
      rr.fallbackReason = "partial_rule_fill";
    }
  }

  public static boolean entirelyRuleFallback(SearchLog log) {
    if (log == null || log.steps().isEmpty()) {
      return false;
    }
    boolean sawFallback = false;
    for (SearchLog.Step s : log.steps()) {
      if (s == null || s.event == null) {
        continue;
      }
      if ("ok".equals(s.event)
          || "llm_direct".equals(s.event)
          || "llm_direct_envelope".equals(s.event)) {
        return false;
      }
      if ("rule_fallback".equals(s.event) || "illegal_action".equals(s.event)) {
        sawFallback = true;
      }
    }
    return sawFallback;
  }

  private static void attachPlanMetrics(QueryResult result, RunResult rr) {
    if (result == null) {
      return;
    }
    if (result.trace == null) {
      result.trace = new ExecutionTrace();
    }
    result.trace.t_plan_ms = rr.t_plan_ms;
    result.trace.t_exec_ms = rr.t_exec_ms;
    result.trace.plan_regret_ms = rr.plan_regret_ms;
    result.trace.best_safe_estimated_ms = rr.best_safe_estimated_ms;
    result.trace.planner_mode = rr.plannerMode != null ? rr.plannerMode.wireName() : null;
  }

  private Path writeArtifacts(Path runsRoot, BoundIr ir, PlanEnvelope plan,
                              PhysicalPlan phys, CostCard selectedCost,
                              List<CostCard> allCosts, QueryResult result,
                              SearchLog searchLog, String stopReason,
                              List<kart.validation.CoverageCertificate> certs,
                              List<ValidationReport> rejections,
                              CostFeatures selectedFeatures,
                              RunResult rr)
      throws IOException {
    String runId = ir.query_id != null ? ir.query_id : ("run_" + System.currentTimeMillis());
    Path dir = runsRoot.resolve(runId);
    Files.createDirectories(dir);
    write(dir.resolve("bound_ir.json"), MAPPER.writeValueAsString(ir));
    if (plan != null) {
      write(dir.resolve("plan.json"), plan.toJson());
    }
    if (phys != null) {
      write(dir.resolve("physical.json"), MAPPER.writeValueAsString(phys));
    }
    if (selectedCost != null) {
      write(dir.resolve("cost_card.json"), selectedCost.toJson());
    }
    if (selectedFeatures != null) {
      write(dir.resolve("cost_features.json"), MAPPER.writeValueAsString(selectedFeatures.toFeatureMap()));
    } else if (selectedCost != null && selectedCost.features != null) {
      write(dir.resolve("cost_features.json"), MAPPER.writeValueAsString(selectedCost.features));
    }
    if (allCosts != null && !allCosts.isEmpty()) {
      write(dir.resolve("cost_cards.json"), MAPPER.writeValueAsString(allCosts));
    }

    // NFR-3: full candidate plans (+ physical when available), not only id/signature.
    Map<String, SafePlanHandle> safeById = indexSafeByPlanId(rr != null ? rr.safe : null);
    if (rr != null && rr.candidates != null && !rr.candidates.isEmpty()) {
      Path candRoot = dir.resolve("candidates");
      Files.createDirectories(candRoot);
      List<Map<String, Object>> candSummaries = new ArrayList<Map<String, Object>>();
      kart.compile.QueryCompiler compiler = new kart.compile.QueryCompiler(layout);
      for (PlanEnvelope c : rr.candidates) {
        if (c == null) {
          continue;
        }
        String pid = c.plan_id != null ? c.plan_id : ("sig_" + Integer.toHexString(c.signature().hashCode()));
        Path candDir = candRoot.resolve(sanitizePath(pid));
        Files.createDirectories(candDir);
        write(candDir.resolve("plan.json"), c.toJson());
        PhysicalPlan cPhys = null;
        SafePlanHandle sh = safeById.get(c.plan_id);
        if (sh != null) {
          cPhys = sh.physicalPlan();
        } else {
          try {
            cPhys = compiler.compile(c, ir);
          } catch (RuntimeException ignored) {
            // leave physical absent when compile fails
          }
        }
        if (cPhys != null) {
          write(candDir.resolve("physical.json"), MAPPER.writeValueAsString(cPhys));
        }
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("plan_id", c.plan_id);
        m.put("signature", c.signature());
        m.put("safe", Boolean.valueOf(sh != null));
        m.put("dir", "candidates/" + sanitizePath(pid));
        candSummaries.add(m);
      }
      write(dir.resolve("candidates.json"), MAPPER.writeValueAsString(candSummaries));
    }

    if (searchLog != null) {
      Map<String, Object> searchArtifact = new LinkedHashMap<String, Object>();
      searchArtifact.put("stop_reason", stopReason == null ? "OK" : stopReason);
      searchArtifact.put("planner_mode", rr != null && rr.plannerMode != null
          ? rr.plannerMode.wireName() : null);
      searchArtifact.put("t_plan_ms", rr != null ? rr.t_plan_ms : null);
      searchArtifact.put("t_exec_ms", rr != null ? rr.t_exec_ms : null);
      searchArtifact.put("plan_regret_ms", rr != null ? rr.plan_regret_ms : null);
      searchArtifact.put("best_safe_estimated_ms", rr != null ? rr.best_safe_estimated_ms : null);
      searchArtifact.put("steps", searchLog.steps());
      write(dir.resolve("search_log.json"), MAPPER.writeValueAsString(searchArtifact));
    }
    if (certs != null && !certs.isEmpty()) {
      write(dir.resolve("coverage_certificates.json"), MAPPER.writeValueAsString(certs));
    }

    // NFR-3: validation reports for SAFE and REJECT (not reject-only).
    List<Map<String, Object>> allReports = new ArrayList<Map<String, Object>>();
    if (rr != null && rr.safe != null) {
      for (SafePlanHandle h : rr.safe) {
        if (h == null) {
          continue;
        }
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("plan_id", h.plan() != null ? h.plan().plan_id : null);
        row.put("safe", Boolean.TRUE);
        row.put("hash", h.validationReportHash());
        row.put("report", h.validationReport());
        allReports.add(row);
      }
    }
    if (rr != null && rr.rejections != null) {
      for (SearchResult.Rejection rej : rr.rejections) {
        if (rej == null || rej.report == null) {
          continue;
        }
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("plan_id", rej.planId);
        row.put("signature", rej.signature);
        row.put("safe", Boolean.FALSE);
        row.put("hash", rej.report.hashHex());
        row.put("report", rej.report);
        allReports.add(row);
      }
    } else if (rr != null && rr.rejectionReports != null) {
      for (ValidationReport rep : rr.rejectionReports) {
        if (rep == null) {
          continue;
        }
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("safe", Boolean.FALSE);
        row.put("hash", rep.hashHex());
        row.put("report", rep);
        allReports.add(row);
      }
    }
    if (!allReports.isEmpty()) {
      write(dir.resolve("validation_reports.json"), MAPPER.writeValueAsString(allReports));
    }

    if (result != null) {
      if (result.trace != null) {
        if (result.trace.run_id == null) {
          result.trace.run_id = runId;
        }
        write(dir.resolve("trace.json"), result.trace.toJson());
      }
      write(dir.resolve("result.json"), result.toJson());
    }
    return dir;
  }

  private static Map<String, SafePlanHandle> indexSafeByPlanId(List<SafePlanHandle> safe) {
    Map<String, SafePlanHandle> m = new LinkedHashMap<String, SafePlanHandle>();
    if (safe == null) {
      return m;
    }
    for (SafePlanHandle h : safe) {
      if (h != null && h.plan() != null && h.plan().plan_id != null) {
        m.put(h.plan().plan_id, h);
      }
    }
    return m;
  }

  private static String sanitizePath(String id) {
    if (id == null || id.isEmpty()) {
      return "unknown";
    }
    return id.replaceAll("[^A-Za-z0-9._-]", "_");
  }

  private static void write(Path p, String json) throws IOException {
    Files.write(p, json.getBytes(StandardCharsets.UTF_8));
  }
}
