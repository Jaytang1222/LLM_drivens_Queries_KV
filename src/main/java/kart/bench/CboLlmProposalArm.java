package kart.bench;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kart.cost.BenefitCalibrator;
import kart.cost.CostCard;
import kart.cost.FastCost;
import kart.ir.BoundIr;
import kart.probe.JointCandidateProbe;
import kart.llm.LlmClient;
import kart.llm.LlmException;
import kart.llm.LlmMessage;
import kart.llm.LlmOptions;
import kart.llm.LlmResponse;
import kart.plan.PlanBuilder;
import kart.plan.PlanEnvelope;
import kart.query.QueryEngine;
import kart.search.PlannerMode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * CBO＋LLM proposal arm ({@code docs/comparative_refine_2.md}):
 * one CBO BEST_FIRST search as floor; at most one LLM HTTP request proposing a
 * constructable plan ID missing from CBO's safe set (or keep_cbo); validate via
 * {@link QueryEngine#runFixed} (no BeamSearch). Cache stores plan IDs only.
 *
 * <p>{@code cbo_llm_short_v9} (default): short KEEP/choice with explicit
 * exec_saving semantics, uncertainty, and KEEP∥CBO or speculate scheduling.
 * Legacy {@code v8}/{@code v7} via {@code KART_CBO_LLM_PROTOCOL}.
 */
public final class CboLlmProposalArm implements Arm {

  /**
   * Shared wall budget (ms) for all LLM HTTP calls in one arm run.
   * Override with {@code KART_CBO_LLM_BUDGET_MS} (≥ 1).
   */
  public static final long LLM_WALL_BUDGET_MS = 120_000L;
  /** Legacy independent prompt (v7). */
  static final String PROMPT_VERSION_V7 = "cbo_llm_independent_v7";
  /** Short KEEP/choice prompt (v8 / refine_4). */
  static final String PROMPT_VERSION_V8 = "cbo_llm_short_v8";
  /** Short KEEP/choice with net-gain semantics (v9 / refine_5). */
  static final String PROMPT_VERSION_V9 = "cbo_llm_short_v9";
  /** Restricted action proposal (v10 / refine_6). */
  static final String PROMPT_VERSION_V10 = "cbo_llm_action_v10";
  /** Active prompt version (env-selected). */
  static final String PROMPT_VERSION = PROMPT_VERSION_V10;
  /** Max LLM HTTP calls per arm run (one call; illegal/timeout → CBO). */
  static final int MAX_LLM_HTTP_CALLS = 1;
  /** Wide temporal window threshold for uncertainty trigger (30 minutes). */
  static final long TRIGGER_TEMPORAL_MS = 30L * 60L * 1000L;
  /** Large spatial AABB area threshold (approx 2km × 2km in projected meters). */
  static final double TRIGGER_SPATIAL_AREA = 4_000_000.0d;
  /** FastCost must beat CBO selectedCost by this relative margin to force trigger. */
  static final double COST_BEAT_MARGIN = 0.95d;

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final boolean cacheEnabled;
  private final CboLlmProposalCache cache;
  private final long llmBudgetMs;

  public CboLlmProposalArm(boolean cacheEnabled) {
    this(cacheEnabled, CboLlmProposalCache.shared(), resolveBudgetMs());
  }

  public CboLlmProposalArm(boolean cacheEnabled, CboLlmProposalCache cache, long llmBudgetMs) {
    this.cacheEnabled = cacheEnabled;
    this.cache = cache == null ? CboLlmProposalCache.shared() : cache;
    this.llmBudgetMs = Math.max(1L, llmBudgetMs);
  }

  /** Default {@link #LLM_WALL_BUDGET_MS}, or {@code KART_CBO_LLM_BUDGET_MS} when set. */
  static long resolveBudgetMs() {
    String raw = System.getenv("KART_CBO_LLM_BUDGET_MS");
    if (raw == null || raw.trim().isEmpty()) {
      return LLM_WALL_BUDGET_MS;
    }
    try {
      return Math.max(1L, Long.parseLong(raw.trim()));
    } catch (NumberFormatException e) {
      return LLM_WALL_BUDGET_MS;
    }
  }

  /**
   * When true (default), speculate validate+exec of top-k FastCost-ranked novels
   * while the LLM runs. {@code KART_CBO_LLM_SPECULATE} preferred;
   * {@code KART_CBO_LLM_SPECULATE_PT} kept as alias.
   */
  static boolean speculateEnabled() {
    String raw = System.getenv("KART_CBO_LLM_SPECULATE");
    if (raw == null || raw.trim().isEmpty()) {
      raw = System.getenv("KART_CBO_LLM_SPECULATE_PT");
    }
    if (raw == null || raw.trim().isEmpty()) {
      return true;
    }
    String v = raw.trim().toLowerCase();
    return !(v.equals("0") || v.equals("false") || v.equals("off") || v.equals("no"));
  }

  /** @deprecated use {@link #speculateEnabled()}. */
  static boolean speculatePtEnabled() {
    return speculateEnabled();
  }

  /**
   * Hybrid fixed-plan prepare mode. Default {@code safety_only} (compile+validate);
   * set {@code KART_CBO_LLM_PREPARE_MODE=full} to force Final feature/cost for
   * prepare_breakdown measurements.
   */
  static boolean safetyOnlyPrepareEnabled() {
    String raw = System.getenv("KART_CBO_LLM_PREPARE_MODE");
    if (raw == null || raw.trim().isEmpty()) {
      return true;
    }
    String v = raw.trim().toLowerCase();
    if ("full".equals(v) || "final".equals(v)) {
      return false;
    }
    return true;
  }

  /** How many top-ranked novels to speculate (default 1, max 3). */
  static int resolveSpeculateK() {
    String raw = System.getenv("KART_CBO_LLM_SPECULATE_K");
    if (raw == null || raw.trim().isEmpty()) {
      return 1;
    }
    try {
      int k = Integer.parseInt(raw.trim());
      if (k < 1) {
        return 1;
      }
      return Math.min(3, k);
    } catch (NumberFormatException e) {
      return 1;
    }
  }

  /**
   * Protocol: {@code action}/{@code v10} (default refine_6), {@code v9}/{@code short},
   * {@code v8}, or {@code v7}.
   */
  static String resolveProtocol() {
    String raw = System.getenv("KART_CBO_LLM_PROTOCOL");
    if ("v11".equalsIgnoreCase(raw)) return "v11";
    if (raw == null || raw.trim().isEmpty()) {
      return "v10";
    }
    String v = raw.trim().toLowerCase();
    if ("v7".equals(v) || "independent".equals(v) || "long".equals(v)) {
      return "v7";
    }
    if ("v8".equals(v)) {
      return "v8";
    }
    if ("v9".equals(v) || "short".equals(v)) {
      return "v9";
    }
    return "v10";
  }

  static boolean shortProtocol() {
    String p = resolveProtocol();
    return !"v7".equals(p);
  }

  static boolean actionProtocol() {
    return "v10".equals(resolveProtocol());
  }

  static String activePromptVersion() {
    String p = resolveProtocol();
    if ("v11".equals(p)) return "cbo_llm_compact_pair_v11";
    if ("v7".equals(p)) {
      return PROMPT_VERSION_V7;
    }
    if ("v8".equals(p)) {
      return PROMPT_VERSION_V8;
    }
    if ("v9".equals(p)) {
      return PROMPT_VERSION_V9;
    }
    return "cbo_llm_action_v10";
  }

  /** When no trustworthy novel opportunity, exec CBO in parallel with LLM. */
  static boolean cboParallelKeepEnabled() {
    String raw = System.getenv("KART_CBO_LLM_CBO_PARALLEL");
    if (raw == null || raw.trim().isEmpty()) {
      return shortProtocol();
    }
    String v = raw.trim().toLowerCase();
    return !(v.equals("0") || v.equals("false") || v.equals("off") || v.equals("no"));
  }

  /** Estimated LLM critical-path cost shown in prompt (not a measured winner). */
  static long resolveEstLlmCriticalMs() {
    String raw = System.getenv("KART_CBO_LLM_EST_CRITICAL_MS");
    if (raw == null || raw.trim().isEmpty()) {
      return 1200L;
    }
    try {
      return Math.max(0L, Long.parseLong(raw.trim()));
    } catch (NumberFormatException e) {
      return 1200L;
    }
  }

  /** When true, call LLM whenever whitelist is non-empty (refine_4 default). */
  static boolean alwaysCallLlm() {
    String raw = System.getenv("KART_CBO_LLM_ALWAYS");
    if (raw == null || raw.trim().isEmpty()) {
      return shortProtocol();
    }
    String v = raw.trim().toLowerCase();
    return !(v.equals("0") || v.equals("false") || v.equals("off") || v.equals("no"));
  }

  static FastCost newFastCost(BenchContext ctx) {
    if (ctx == null) {
      return new FastCost(null, null);
    }
    return new FastCost(ctx.layout, ctx.stats,
        ctx.planner != null ? ctx.planner.cost : null);
  }

  /**
   * Rank whitelist by ascending FastCost {@code estimated_ms}. Ties break by id.
   * Missing envelopes sort last.
   */
  static List<String> rankWhitelistByFastCost(List<String> whitelist,
                                              List<PlanEnvelope> constructable,
                                              BoundIr ir, FastCost fastCost) {
    List<String> ids = new ArrayList<String>();
    if (whitelist != null) {
      ids.addAll(whitelist);
    }
    final FastCost fc = fastCost != null ? fastCost : new FastCost(null, null);
    final List<PlanEnvelope> envs = constructable;
    java.util.Collections.sort(ids, new java.util.Comparator<String>() {
      @Override
      public int compare(String a, String b) {
        double ea = fastEst(fc, envs, ir, a);
        double eb = fastEst(fc, envs, ir, b);
        int c = Double.compare(ea, eb);
        if (c != 0) {
          return c;
        }
        return String.valueOf(a).compareTo(String.valueOf(b));
      }
    });
    return ids;
  }

  static double fastEst(FastCost fc, List<PlanEnvelope> constructable, BoundIr ir,
                        String planId) {
    PlanEnvelope env = findEnvelope(constructable, planId);
    if (env == null || fc == null) {
      return Double.POSITIVE_INFINITY;
    }
    try {
      CostCard card = fc.estimate(env, ir);
      if (card == null || Double.isNaN(card.estimated_ms)) {
        return Double.POSITIVE_INFINITY;
      }
      return card.estimated_ms;
    } catch (Exception e) {
      return Double.POSITIVE_INFINITY;
    }
  }

  static String preferIdFromRanked(List<String> ranked) {
    if (ranked == null || ranked.isEmpty()) {
      return null;
    }
    return ranked.get(0);
  }

  /**
   * Rank whitelist by descending predicted S = E_cbo - E_p (calib). Ties by id.
   * Falls back to FastCost ascending when calibrator cards missing.
   */
  static List<String> rankWhitelistByCalib(List<String> whitelist,
                                           List<PlanEnvelope> constructable,
                                           BoundIr ir, FastCost fastCost,
                                           BenefitCalibrator calib, CostCard cboCard,
                                           Map<String, Double> sHatOut) {
    List<String> ids = new ArrayList<String>();
    if (whitelist != null) {
      ids.addAll(whitelist);
    }
    final BenefitCalibrator cal = calib;
    final CostCard cbo = cboCard;
    final FastCost fc = fastCost != null ? fastCost : new FastCost(null, null);
    final List<PlanEnvelope> envs = constructable;
    final Map<String, Double> scores = sHatOut != null
        ? sHatOut : new LinkedHashMap<String, Double>();
    for (String id : ids) {
      PlanEnvelope env = findEnvelope(envs, id);
      CostCard cand = cal != null ? cal.estimate(env, ir) : null;
      double s;
      if (cal != null && cbo != null && cand != null) {
        s = cal.predictS(cbo, cand);
      } else {
        double e = fastEst(fc, envs, ir, id);
        s = Double.isInfinite(e) ? Double.NEGATIVE_INFINITY : -e;
      }
      scores.put(id, Double.valueOf(s));
    }
    java.util.Collections.sort(ids, new java.util.Comparator<String>() {
      @Override
      public int compare(String a, String b) {
        double sa = scores.containsKey(a) ? scores.get(a).doubleValue()
            : Double.NEGATIVE_INFINITY;
        double sb = scores.containsKey(b) ? scores.get(b).doubleValue()
            : Double.NEGATIVE_INFINITY;
        int c = Double.compare(sb, sa); // descending S
        if (c != 0) {
          return c;
        }
        return String.valueOf(a).compareTo(String.valueOf(b));
      }
    });
    return ids;
  }

  private static void cancelSpeculative(Future<?> fut) {
    if (fut != null) {
      fut.cancel(true);
    }
  }

  /** Best-effort drain so speculative HBase work does not overlap the chosen exec. */
  private static void drainSpeculative(Future<?> fut) {
    if (fut == null) {
      return;
    }
    try {
      fut.get();
    } catch (Exception ignore) {
      // cancelled / failed — caller already decided not to use the result
    }
  }

  private static void shutdownSpeculative(ExecutorService pool) {
    if (pool != null) {
      pool.shutdownNow();
    }
  }

  public static CboLlmProposalArm withoutCache() {
    return new CboLlmProposalArm(false);
  }

  public static CboLlmProposalArm withCache() {
    return new CboLlmProposalArm(true);
  }

  @Override
  public String id() {
    return cacheEnabled ? "cbo-llm-proposal-cached" : "cbo-llm-proposal";
  }

  public boolean cacheEnabled() {
    return cacheEnabled;
  }

  @Override
  public TrialResult run(BoundIr ir, BenchContext ctx) throws Exception {
    long wall0 = System.currentTimeMillis();
    long planStart = wall0;
    QueryEngine cbo = ctx.newEngine(PlannerMode.BEST_FIRST);
    Path art = ctx.planOnly ? null : ctx.artifactDir(id(), ir != null ? ir.query_id : "q");

    long tSearch0 = System.currentTimeMillis();
    QueryEngine.RunResult cboPlanned = cbo.run(ir, null, true, null);
    long tCboSearchMs = Math.max(0L, System.currentTimeMillis() - tSearch0);
    if (cboPlanned == null || cboPlanned.selected == null) {
      TrialResult fail = TrialResult.fail(cboPlanned != null && cboPlanned.result != null
          ? cboPlanned.result.error : "cbo-llm-proposal: CBO produced no SafePlan");
      stampPlanClock(fail, planStart, System.currentTimeMillis());
      stampBaseExtras(fail, null, null, null, false, false, false, 0, 0L, 0L,
          "cbo_no_safe_plan", false, null);
      fail.extras.put("t_wall_ms", Long.valueOf(Math.max(0L, System.currentTimeMillis() - wall0)));
      fail.extras.put("t_cbo_search_ms", Long.valueOf(tCboSearchMs));
      fail.extras.put("cbo_llm_second_search", Boolean.FALSE);
      fail.extras.put("proposal_prompt_version", activePromptVersion());
      return fail;
    }

    String cboPlanId = cboPlanned.selected.plan() != null
        ? cboPlanned.selected.plan().plan_id : null;
    Set<String> cboSafeIds = safePlanIds(cboPlanned);
    List<PlanEnvelope> constructable = PlanBuilder.buildCandidatesWithMergeVariants(ir);
    List<String> novel = novelIds(constructable, cboSafeIds);
    FastCost fastCost = newFastCost(ctx);
    BenefitCalibrator calibrator = new BenefitCalibrator(fastCost, ctx.layout);
    CostCard cboFeatCard = null;
    if (cboPlanned.selected != null && cboPlanned.selected.plan() != null) {
      cboFeatCard = calibrator.estimate(cboPlanned.selected.plan(), ir);
    }
    if (cboFeatCard == null && cboPlanned.selectedCost != null) {
      cboFeatCard = cboPlanned.selectedCost;
    }
    double cboEstMs = cboPlanned.selectedCost != null
        ? cboPlanned.selectedCost.estimated_ms : Double.POSITIVE_INFINITY;
    Map<String, Double> sHatById = new LinkedHashMap<String, Double>();
    List<String> baseWhitelist = proposalWhitelist(novel);
    List<String> whitelist = shortProtocol()
        ? rankWhitelistByCalib(baseWhitelist, constructable, ir, fastCost,
            calibrator, cboFeatCard, sHatById)
        : rankWhitelistByFastCost(baseWhitelist, constructable, ir, fastCost);
    if (!shortProtocol()) {
      for (String wid : whitelist) {
        PlanEnvelope env = findEnvelope(constructable, wid);
        CostCard cand = calibrator.estimate(env, ir);
        if (cboFeatCard != null && cand != null) {
          sHatById.put(wid, Double.valueOf(calibrator.predictS(cboFeatCard, cand)));
        }
      }
    }
    if ("v11".equals(resolveProtocol())) {
      // Do not pay speculative execution for a candidate the unchanged gate
      // would inevitably reject. LLM sees only admissible proposals plus KEEP.
      List<String> eligible = new ArrayList<String>();
      for (String wid : whitelist) {
        Double gain = sHatById.get(wid);
        if (gain != null && BenefitCalibrator.conservativeNetS(gain,
            BenefitCalibrator.resolveSavingUncertaintyMs()) >= BenefitCalibrator.resolveMinAdoptSMs()) {
          eligible.add(wid);
        }
      }
      whitelist = eligible;
    }
    String preferId = preferIdFromRanked(whitelist);
    Double preferEstMs = preferId == null ? null
        : Double.valueOf(fastEst(fastCost, constructable, ir, preferId));
    Double preferSHat = preferId != null && sHatById.containsKey(preferId)
        ? sHatById.get(preferId) : null;
    double minSpecS = BenefitCalibrator.resolveMinSpeculateSMs();
    double minAdoptS = BenefitCalibrator.resolveMinAdoptSMs();
    long tCalibMs = 0L;

    String cacheKey = cacheKey(ir, ctx);
    boolean cacheHit = false;
    String proposalId = null;
    boolean llmTriggered = false;
    int llmCalls = 0;
    int httpAttempts = 0;
    Integer promptTokens = null;
    Integer completionTokens = null;
    long llmLatencyMs = 0L;
    long validationMs = 0L;
    long tPostValidateMs = 0L;
    long tSpecAwaitMs = 0L;
    long tSpecLaunchMs = 0L;
    String fallbackReason = null;
    boolean proposalNovel = false;
    boolean proposalValid = false;
    String selectedId = cboPlanId;
    QueryEngine.RunResult selectedRun = cboPlanned;
    String skipReason = null;
    String selectionAssist = null;
    boolean speculativeStarted = false;
    boolean speculativeUsed = false;
    boolean speculativeWaste = false;
    boolean speculativeReuseValidate = false;
    String speculativeIdsCsv = null;
    Map<String, Future<SpecBundle>> speculativeFuts =
        new LinkedHashMap<String, Future<SpecBundle>>();
    ExecutorService speculativePool = null;
    long tSpecValidateMs = 0L;
    long tSpecExecMs = 0L;
    long tFixedCompileMs = 0L;
    long tFixedSafetyMs = 0L;
    long tFixedFeaturesMs = 0L;
    long tFixedRegionLocateMs = 0L;
    long tFixedCostMs = 0L;
    long tFixedPrepareMs = 0L;
    int regionLocateCalls = 0;
    int regionLocateCacheHits = 0;
    int nScanTasks = 0;
    String prepareModeUsed = null;
    kart.validation.ValidatorTiming lastValidatorTiming = null;
    String speculativeRanker = null;
    String llmAction = null;
    String llmReasonCode = null;
    boolean gateFallback = false;
    String gateReason = null;
    Double selectedSHat = null;
    Double savingUncertaintyMs = BenefitCalibrator.resolveSavingUncertaintyMs();
    long estLlmCriticalMs = resolveEstLlmCriticalMs();
    String scheduleMode = null;
    boolean cboParallelStarted = false;
    boolean cboParallelUsed = false;
    boolean cboParallelWaste = false;
    Future<QueryEngine.RunResult> cboParallelFut = null;
    ExecutorService cboParallelPool = null;
    long tCboParallelAwaitMs = 0L;
    long tCboParallelExecMs = 0L;
    boolean featuresMissing = false;
    String llmProposedAction = null;
    String llmProposedPlanId = null;
    String llmRawResponse = null;
    String llmParseStatus = null;
    String gateDecision = null;
    String finalSelectionSource = null;
    boolean speculationRequested = false;
    JointCandidateProbe.Result probeResult = null;
    String llmActionId = null;
    long tProbeMs = 0L;

    // Cache path: reconstruct + re-validate only; never trust old safety proofs.
    if (cacheEnabled) {
      String cached = cache.get(cacheKey);
      if (cached != null) {
        cacheHit = true;
        long v0 = System.currentTimeMillis();
        QueryEngine.RunResult rebuilt =
            validateFixed(cbo, ir, constructable, cached, safetyOnlyPrepareEnabled());
        validationMs = Math.max(0L, System.currentTimeMillis() - v0);
        if (rebuilt != null) {
          tFixedCompileMs = nz(rebuilt.t_fixed_compile_ms);
          tFixedSafetyMs = nz(rebuilt.t_fixed_safety_ms);
          tFixedFeaturesMs = nz(rebuilt.t_fixed_features_ms);
          tFixedRegionLocateMs = nz(rebuilt.t_fixed_region_locate_ms);
          tFixedCostMs = nz(rebuilt.t_fixed_cost_ms);
          tFixedPrepareMs = nz(rebuilt.t_fixed_prepare_ms);
          regionLocateCalls = ni(rebuilt.region_locate_calls);
          regionLocateCacheHits = ni(rebuilt.region_locate_cache_hits);
          nScanTasks = ni(rebuilt.n_scan_tasks);
          prepareModeUsed = rebuilt.prepareMode;
          lastValidatorTiming = vtOf(rebuilt);
        }
        if (rebuilt != null && rebuilt.selected != null) {
          selectedRun = rebuilt;
          selectedId = cached;
          proposalId = cached;
          proposalNovel = !cboSafeIds.contains(cached);
          proposalValid = true;
        } else {
          cacheHit = false;
          cache.remove(cacheKey);
          fallbackReason = "cache_revalidate_failed";
        }
      }
    }

    if (!cacheHit) {
      skipReason = llmSkipReason(ir, whitelist, novel, preferEstMs, cboEstMs);
      if (skipReason != null) {
        llmTriggered = false;
        llmCalls = 0;
        fallbackReason = fallbackReason == null ? skipReason : fallbackReason;
      } else {
        llmTriggered = true;
        if (ctx.llm == null) {
          fallbackReason = "no_llm_client";
        } else {
          // Two-level schedule (refine_5): speculate novel OR CBO∥LLM, not both.
          long calib0 = System.currentTimeMillis();
          featuresMissing = calibrator.hasMissingFeatures(cboFeatCard);
          for (String wid : whitelist) {
            PlanEnvelope wenv = findEnvelope(constructable, wid);
            CostCard wc = calibrator.estimate(wenv, ir);
            if (calibrator.hasMissingFeatures(wc)) {
              featuresMissing = true;
              break;
            }
          }
          // refine_6 action protocol: LLM decides what to check first — no
          // pre-LLM candidate speculation (may still CBO∥LLM).
          boolean allowSpec = !ctx.planOnly && speculateEnabled() && !whitelist.isEmpty()
              && !actionProtocol();
          if (shortProtocol() && !actionProtocol()) {
            if (featuresMissing) {
              allowSpec = false;
              gateReason = "gate_missing_features";
            } else if (preferSHat == null
                || Double.isNaN(preferSHat.doubleValue())
                || preferSHat.doubleValue() < minSpecS) {
              allowSpec = false;
              if (gateReason == null) {
                gateReason = "gate_skip_speculate_low_s";
              }
            }
          } else if (actionProtocol()) {
            allowSpec = false;
            scheduleMode = "cbo_parallel";
          }
          tCalibMs = Math.max(0L, System.currentTimeMillis() - calib0);
          scheduleMode = allowSpec ? "speculate_candidate" : "cbo_parallel";
          speculationRequested = allowSpec;
          if (allowSpec) {
            long launch0 = System.currentTimeMillis();
            int k = Math.min(resolveSpeculateK(), whitelist.size());
            List<String> specIds = new ArrayList<String>(whitelist.subList(0, k));
            speculativeIdsCsv = joinCsv(specIds);
            speculativeRanker = shortProtocol() ? BenefitCalibrator.VERSION : "fastcost";
            try {
              speculativePool = Executors.newFixedThreadPool(Math.max(1, specIds.size()));
              for (final String specId : specIds) {
                final QueryEngine specEngine = ctx.newEngine(PlannerMode.BEST_FIRST);
                final Path specArt = ctx.artifactDir(id() + "-spec-" + specId,
                    ir != null ? ir.query_id : "q");
                final BoundIr specIr = ir;
                final List<PlanEnvelope> specConstructable = constructable;
                speculativeFuts.put(specId,
                    speculativePool.submit(new Callable<SpecBundle>() {
                      @Override
                      public SpecBundle call() throws Exception {
                        long v0 = System.currentTimeMillis();
                        QueryEngine.RunResult planned =
                            validateFixed(specEngine, specIr, specConstructable, specId,
                                safetyOnlyPrepareEnabled());
                        long vMs = Math.max(0L, System.currentTimeMillis() - v0);
                        if (planned == null || planned.selected == null) {
                          throw new IllegalStateException("spec_validate_failed:" + specId);
                        }
                        long e0 = System.currentTimeMillis();
                        QueryEngine.RunResult executed =
                            specEngine.executeSelected(specIr, specArt, planned);
                        long eMs = Math.max(0L, System.currentTimeMillis() - e0);
                        return new SpecBundle(executed, vMs, eMs);
                      }
                    }));
              }
              speculativeStarted = !speculativeFuts.isEmpty();
            } catch (Exception ignore) {
              cancelAllSpeculative(speculativeFuts);
              shutdownSpeculative(speculativePool);
              speculativeFuts.clear();
              speculativePool = null;
              speculativeStarted = false;
            }
            tSpecLaunchMs = Math.max(0L, System.currentTimeMillis() - launch0);
          } else if (!ctx.planOnly && cboParallelKeepEnabled()) {
            try {
              cboParallelPool = Executors.newSingleThreadExecutor();
              final QueryEngine cboEng = cbo;
              final BoundIr cboIr = ir;
              final Path cboArt = art;
              final QueryEngine.RunResult cboPlannedFinal = cboPlanned;
              cboParallelFut = cboParallelPool.submit(new Callable<QueryEngine.RunResult>() {
                @Override
                public QueryEngine.RunResult call() throws Exception {
                  return cboEng.executeSelected(cboIr, cboArt, cboPlannedFinal);
                }
              });
              cboParallelStarted = true;
              scheduleMode = "cbo_parallel";
            } catch (Exception ignore) {
              cboParallelFut = null;
              if (cboParallelPool != null) {
                cboParallelPool.shutdownNow();
                cboParallelPool = null;
              }
              cboParallelStarted = false;
            }
          }

          long estExtra = allowSpec
              ? Math.max(0L, estLlmCriticalMs) // overlapped with V+E when speculate hits
              : Math.max(0L, estLlmCriticalMs); // prompt field; wall uses max(L,Ec)
          Map<String, Double> predExecById = new LinkedHashMap<String, Double>();
          if (cboFeatCard != null) {
            predExecById.put(cboPlanId, Double.valueOf(calibrator.predictExecMs(cboFeatCard)));
          }
          for (String wid : whitelist) {
            PlanEnvelope wenv = findEnvelope(constructable, wid);
            CostCard wc = calibrator.estimate(wenv, ir);
            if (wc != null) {
              predExecById.put(wid, Double.valueOf(calibrator.predictExecMs(wc)));
            }
          }

          LlmAsk ask;
          if ("v11".equals(resolveProtocol())) {
            ask = askLlmCompact(ctx.llm, cboPlanId, whitelist, sHatById,
                savingUncertaintyMs, llmBudgetMs);
          } else if (actionProtocol()) {
            ask = askLlmOnceAction(ctx.llm, ir, cboPlanId, whitelist, sHatById,
                scheduleMode, llmBudgetMs);
            llmActionId = ask.actionId;
          } else if (shortProtocol()) {
            ask = askLlmOnceShort(ctx.llm, ir, cboPlanId, whitelist, sHatById, predExecById,
                scheduleMode, estExtra, savingUncertaintyMs, llmBudgetMs);
          } else {
            ask = askLlmOnceIndependent(ctx.llm, ir, cboPlanId, whitelist, preferId,
                llmBudgetMs);
          }
          llmCalls = ask.calls;
          httpAttempts = ask.httpAttempts;
          llmLatencyMs = ask.latencyMs;
          promptTokens = ask.promptTokens;
          completionTokens = ask.completionTokens;
          llmRawResponse = ask.rawContent;
          llmParseStatus = ask.parseStatus;
          llmAction = ask.action;
          llmReasonCode = ask.reasonCode;
          // refine_6: never overwrite raw proposal fields after gating.
          llmProposedAction = ask.action;
          llmProposedPlanId = ask.planId;
          if (actionProtocol()) {
            // Map restricted action → candidate / probe / keep.
            String mapped = mapActionToProposal(ask.actionId, whitelist, preferId, cboPlanId);
            if ("PROBE_JOINT_CANDIDATES".equals(ask.actionId)
                || "CHECK_INTERSECT".equals(ask.actionId)) {
              long p0 = System.currentTimeMillis();
              PlanEnvelope probeEnv = preferId != null
                  ? findEnvelope(constructable, preferId)
                  : (whitelist.isEmpty() ? null : findEnvelope(constructable, whitelist.get(0)));
              if (probeEnv != null && ctx.kv != null) {
                probeResult = new JointCandidateProbe(ctx.layout, ctx.kv).probe(probeEnv, ir);
                // Diagnostic only: sampled row volume has no validated mapping
                // to saved milliseconds. Never manufacture a positive boost.
              }
              tProbeMs = Math.max(0L, System.currentTimeMillis() - p0);
              // After probe, deterministic program may adopt prefer if margin ok.
              proposalId = preferId;
              llmProposedPlanId = preferId;
            } else if (mapped != null) {
              proposalId = mapped;
            } else if ("NO_ACTION".equals(ask.actionId) || "keep_cbo".equals(ask.action)) {
              fallbackReason = "llm_no_action";
              proposalId = null;
            } else if (ask.error != null) {
              fallbackReason = ask.error;
              proposalId = null;
            } else {
              fallbackReason = "unknown_action";
              proposalId = null;
            }
          } else if (ask.error != null) {
            fallbackReason = ask.error;
            proposalId = ask.planId;
          } else if ("keep_cbo".equals(ask.action)) {
            fallbackReason = "llm_keep_cbo";
            proposalId = null;
          } else {
            proposalId = ask.planId;
          }
          // Post-decision adopt gate: reject low / uncertain predicted S.
          // Does NOT rewrite llmProposedAction / llm_action.
          if (proposalId != null && classifyProposal(proposalId, cboPlanId, whitelist) == null
              && shortProtocol()) {
            Double sh = sHatById.get(proposalId);
            selectedSHat = sh;
            boolean reject = false;
            if (sh == null || Double.isNaN(sh.doubleValue()) || sh.doubleValue() < minAdoptS) {
              reject = true;
              gateReason = "gate_negative_s";
            } else {
              double cons = BenefitCalibrator.conservativeNetS(
                  sh.doubleValue(), savingUncertaintyMs);
              if (cons < minAdoptS) {
                reject = true;
                gateReason = savingUncertaintyMs == null
                    ? "gate_uncertain_s" : "gate_conservative_s";
              }
            }
            if (reject) {
              gateFallback = true;
              gateDecision = "reject";
              fallbackReason = gateReason;
              proposalId = null;
              selectionAssist = "calib_gate";
            } else {
              gateDecision = "accept";
            }
          } else if (proposalId == null) {
            gateDecision = gateDecision == null ? "keep" : gateDecision;
          }
          if (proposalId != null && classifyProposal(proposalId, cboPlanId, whitelist) == null) {
            proposalNovel = true;
            selectedSHat = sHatById.get(proposalId);
            if (cboParallelStarted) {
              // Novel wins: discard parallel CBO exec.
              cboParallelWaste = true;
              if (cboParallelFut != null) {
                cboParallelFut.cancel(true);
              }
              shutdownSpeculative(cboParallelPool);
              cboParallelPool = null;
              cboParallelFut = null;
            }
            if (speculativeStarted && speculativeFuts.containsKey(proposalId)) {
              proposalValid = true;
              selectedId = proposalId;
              speculativeReuseValidate = true;
            } else {
              long v0 = System.currentTimeMillis();
              QueryEngine.RunResult rebuilt =
                  validateFixed(cbo, ir, constructable, proposalId,
                      safetyOnlyPrepareEnabled());
              tPostValidateMs = Math.max(0L, System.currentTimeMillis() - v0);
              validationMs = tPostValidateMs;
              if (rebuilt != null) {
                tFixedCompileMs = nz(rebuilt.t_fixed_compile_ms);
                tFixedSafetyMs = nz(rebuilt.t_fixed_safety_ms);
                tFixedFeaturesMs = nz(rebuilt.t_fixed_features_ms);
                tFixedRegionLocateMs = nz(rebuilt.t_fixed_region_locate_ms);
                tFixedCostMs = nz(rebuilt.t_fixed_cost_ms);
                tFixedPrepareMs = nz(rebuilt.t_fixed_prepare_ms);
                regionLocateCalls = ni(rebuilt.region_locate_calls);
                regionLocateCacheHits = ni(rebuilt.region_locate_cache_hits);
                nScanTasks = ni(rebuilt.n_scan_tasks);
                prepareModeUsed = rebuilt.prepareMode;
                lastValidatorTiming = vtOf(rebuilt);
              }
              if (rebuilt != null && rebuilt.selected != null) {
                proposalValid = true;
                selectedRun = rebuilt;
                selectedId = proposalId;
                if (cacheEnabled) {
                  cache.put(cacheKey, proposalId);
                }
              } else {
                fallbackReason = "validation_reject";
                proposalNovel = false;
                gateFallback = true;
              }
            }
          } else if (fallbackReason == null) {
            fallbackReason = classifyProposal(proposalId, cboPlanId, whitelist);
          }
        }
      }
    }

    long planEnd = System.currentTimeMillis();
    selectedRun.planStartEpochMs = planStart;
    selectedRun.planEndEpochMs = planEnd;
    selectedRun.t_plan_ms = Long.valueOf(Math.max(0L, planEnd - planStart));

    QueryEngine.RunResult executed;
    Future<SpecBundle> hitFut =
        (selectedId != null) ? speculativeFuts.get(selectedId) : null;
    if (ctx.planOnly) {
      executed = selectedRun;
      cancelAllSpeculative(speculativeFuts);
      drainAllSpeculative(speculativeFuts);
      shutdownSpeculative(speculativePool);
      if (cboParallelFut != null) {
        cboParallelFut.cancel(true);
      }
      shutdownSpeculative(cboParallelPool);
    } else if (cboParallelStarted && cboParallelFut != null
        && (proposalId == null || !proposalValid)
        && cboPlanId != null && cboPlanId.equals(selectedId)) {
      // KEEP / reject path with CBO∥LLM: reuse parallel CBO exec.
      long await0 = System.currentTimeMillis();
      try {
        cancelAllSpeculative(speculativeFuts);
        drainAllSpeculative(speculativeFuts);
        shutdownSpeculative(speculativePool);
        executed = cboParallelFut.get();
        tCboParallelAwaitMs = Math.max(0L, System.currentTimeMillis() - await0);
        if (executed != null && executed.t_exec_ms != null) {
          tCboParallelExecMs = executed.t_exec_ms.longValue();
        }
        cboParallelUsed = true;
        selectedRun = executed != null ? executed : selectedRun;
        selectedId = cboPlanId;
      } catch (Exception e) {
        tCboParallelAwaitMs = Math.max(0L, System.currentTimeMillis() - await0);
        cboParallelWaste = true;
        executed = cbo.executeSelected(ir, art, cboPlanned);
        selectedId = cboPlanId;
        selectedRun = executed;
      } finally {
        shutdownSpeculative(cboParallelPool);
        cboParallelPool = null;
      }
      if (executed != null) {
        executed.planStartEpochMs = planStart;
        executed.planEndEpochMs = planEnd;
        executed.t_plan_ms = Long.valueOf(Math.max(0L, planEnd - planStart));
      }
    } else if (speculativeStarted && hitFut != null && proposalValid) {
      long await0 = System.currentTimeMillis();
      try {
        // Cancel siblings first so they do not contend during await.
        cancelOtherSpeculative(speculativeFuts, selectedId);
        SpecBundle bundle = hitFut.get();
        tSpecAwaitMs = Math.max(0L, System.currentTimeMillis() - await0);
        tSpecValidateMs = bundle.validateMs;
        tSpecExecMs = bundle.execMs;
        executed = bundle.executed;
        speculativeUsed = true;
        selectedRun = executed;
        if (executed != null) {
          tFixedCompileMs = nz(executed.t_fixed_compile_ms);
          tFixedSafetyMs = nz(executed.t_fixed_safety_ms);
          tFixedFeaturesMs = nz(executed.t_fixed_features_ms);
          tFixedRegionLocateMs = nz(executed.t_fixed_region_locate_ms);
          tFixedCostMs = nz(executed.t_fixed_cost_ms);
          tFixedPrepareMs = nz(executed.t_fixed_prepare_ms);
          regionLocateCalls = ni(executed.region_locate_calls);
          regionLocateCacheHits = ni(executed.region_locate_cache_hits);
          nScanTasks = ni(executed.n_scan_tasks);
          prepareModeUsed = executed.prepareMode;
          lastValidatorTiming = vtOf(executed);
        }
        if (cacheEnabled && cacheKey != null) {
          cache.put(cacheKey, selectedId);
        }
      } catch (Exception e) {
        tSpecAwaitMs = Math.max(0L, System.currentTimeMillis() - await0);
        speculativeWaste = true;
        cancelAllSpeculative(speculativeFuts);
        drainAllSpeculative(speculativeFuts);
        long v0 = System.currentTimeMillis();
        QueryEngine.RunResult rebuilt = validateFixed(cbo, ir, constructable, selectedId,
            safetyOnlyPrepareEnabled());
        validationMs += Math.max(0L, System.currentTimeMillis() - v0);
        if (rebuilt != null && rebuilt.selected != null) {
          selectedRun = rebuilt;
          executed = cbo.executeSelected(ir, art, selectedRun);
        } else {
          proposalValid = false;
          fallbackReason = "spec_failed_and_revalidate";
          gateFallback = true;
          selectedId = cboPlanId;
          selectedRun = cboPlanned;
          executed = cbo.executeSelected(ir, art, selectedRun);
        }
      } finally {
        shutdownSpeculative(speculativePool);
        if (cboParallelFut != null) {
          cboParallelFut.cancel(true);
        }
        shutdownSpeculative(cboParallelPool);
      }
      executed.planStartEpochMs = planStart;
      executed.planEndEpochMs = planEnd;
      executed.t_plan_ms = Long.valueOf(Math.max(0L, planEnd - planStart));
    } else {
      if (cboParallelStarted && cboParallelFut != null) {
        cboParallelWaste = true;
        cboParallelFut.cancel(true);
        shutdownSpeculative(cboParallelPool);
        cboParallelPool = null;
      }
      if (speculativeStarted) {
        speculativeWaste = true;
        // Observability: if a speculative future already finished, record its
        // prepare/exec split even though the result is not adopted.
        for (Future<SpecBundle> fut : speculativeFuts.values()) {
          if (fut == null || !fut.isDone() || fut.isCancelled()) {
            continue;
          }
          try {
            SpecBundle bundle = fut.get();
            if (bundle != null) {
              tSpecValidateMs = bundle.validateMs;
              tSpecExecMs = bundle.execMs;
              if (bundle.executed != null) {
                tFixedCompileMs = nz(bundle.executed.t_fixed_compile_ms);
                tFixedSafetyMs = nz(bundle.executed.t_fixed_safety_ms);
                tFixedFeaturesMs = nz(bundle.executed.t_fixed_features_ms);
                tFixedRegionLocateMs = nz(bundle.executed.t_fixed_region_locate_ms);
                tFixedCostMs = nz(bundle.executed.t_fixed_cost_ms);
                tFixedPrepareMs = nz(bundle.executed.t_fixed_prepare_ms);
                regionLocateCalls = ni(bundle.executed.region_locate_calls);
                regionLocateCacheHits = ni(bundle.executed.region_locate_cache_hits);
                nScanTasks = ni(bundle.executed.n_scan_tasks);
                prepareModeUsed = bundle.executed.prepareMode;
              }
            }
            break;
          } catch (Exception ignore) {
            // still waste; timings unavailable
          }
        }
        // Measurement aid: under FULL prepare mode, wait for speculative prepare
        // so prepare_breakdown is populated even when LLM rejects.
        if (!safetyOnlyPrepareEnabled()) {
          for (Future<SpecBundle> fut : speculativeFuts.values()) {
            if (fut == null) {
              continue;
            }
            try {
              SpecBundle bundle = fut.get();
              if (bundle != null) {
                tSpecValidateMs = bundle.validateMs;
                tSpecExecMs = bundle.execMs;
                if (bundle.executed != null) {
                  tFixedCompileMs = nz(bundle.executed.t_fixed_compile_ms);
                  tFixedSafetyMs = nz(bundle.executed.t_fixed_safety_ms);
                  tFixedFeaturesMs = nz(bundle.executed.t_fixed_features_ms);
                  tFixedRegionLocateMs = nz(bundle.executed.t_fixed_region_locate_ms);
                  tFixedCostMs = nz(bundle.executed.t_fixed_cost_ms);
                  tFixedPrepareMs = nz(bundle.executed.t_fixed_prepare_ms);
                  regionLocateCalls = ni(bundle.executed.region_locate_calls);
                  regionLocateCacheHits = ni(bundle.executed.region_locate_cache_hits);
                  nScanTasks = ni(bundle.executed.n_scan_tasks);
                  prepareModeUsed = bundle.executed.prepareMode;
                }
              }
              break;
            } catch (Exception ignore) {
              // ignore
            }
          }
        }
      }
      cancelAllSpeculative(speculativeFuts);
      drainAllSpeculative(speculativeFuts);
      shutdownSpeculative(speculativePool);
      executed = cbo.executeSelected(ir, art, selectedRun);
      executed.planStartEpochMs = planStart;
      executed.planEndEpochMs = planEnd;
      executed.t_plan_ms = selectedRun.t_plan_ms;
    }

    long wall = Math.max(0L, System.currentTimeMillis() - wall0);
    TrialResult tr = TrialResult.fromRun(executed, wall);
    tr.t_plan_ms = Long.valueOf(Math.max(0L, planEnd - planStart));
    tr.extras.put("plan_start_ms", Long.valueOf(planStart));
    tr.extras.put("plan_end_ms", Long.valueOf(planEnd));
    if (speculativeUsed || cboParallelUsed) {
      tr.t_e2e_ms = Long.valueOf(wall);
    } else if (tr.t_exec_ms != null && tr.t_plan_ms != null) {
      tr.t_e2e_ms = Long.valueOf(tr.t_plan_ms.longValue() + tr.t_exec_ms.longValue());
    } else if (tr.t_plan_ms != null) {
      tr.t_e2e_ms = tr.t_plan_ms;
    }
    stampBaseExtras(tr, cboPlanId, proposalId, selectedId, proposalNovel, proposalValid,
        llmTriggered, llmCalls, llmLatencyMs, validationMs, fallbackReason, cacheHit, cacheKey);
    tr.extras.put("llm_budget_ms", Long.valueOf(llmBudgetMs));
    tr.extras.put("llm_http_attempts", Integer.valueOf(httpAttempts));
    tr.extras.put("tokens_in", promptTokens);
    tr.extras.put("tokens_out", completionTokens);
    tr.extras.put("n_candidates", Integer.valueOf(
        selectedRun.candidates != null ? selectedRun.candidates.size()
            : (selectedRun.safe != null ? selectedRun.safe.size() : 0)));
    tr.extras.put("n_validation_reject", Integer.valueOf(
        selectedRun.rejections != null ? selectedRun.rejections.size() : 0));
    tr.extras.put("n_whitelist", Integer.valueOf(whitelist.size()));
    tr.extras.put("n_novel", Integer.valueOf(novel.size()));
    tr.extras.put("cbo_n_safe", Integer.valueOf(cboSafeIds.size()));
    tr.extras.put("proposal_prompt_version", activePromptVersion());
    tr.extras.put("proposal_candidate_ids", new ArrayList<String>(whitelist));
    tr.extras.put("prefer_plan_id", preferId);
    if (preferEstMs != null) {
      tr.extras.put("prefer_fastcost_ms", preferEstMs);
    }
    if (preferSHat != null) {
      tr.extras.put("prefer_s_hat_ms", preferSHat);
    }
    if (selectedSHat != null) {
      tr.extras.put("selected_s_hat_ms", selectedSHat);
    }
    tr.extras.put("calib_version", kart.cost.OnlineBenefitModel.get() != null
        ? "online_pair_nnls_v1" : BenefitCalibrator.VERSION);
    tr.extras.put("llm_requested", Boolean.valueOf(llmCalls > 0));
    tr.extras.put("calib_min_spec_s_ms", Double.valueOf(minSpecS));
    tr.extras.put("calib_min_adopt_s_ms", Double.valueOf(minAdoptS));
    tr.extras.put("t_calib_ms", Long.valueOf(tCalibMs));
    if (savingUncertaintyMs != null) {
      tr.extras.put("saving_uncertainty_ms", savingUncertaintyMs);
    } else {
      tr.extras.put("saving_uncertainty_ms", "unknown");
    }
    tr.extras.put("features_missing", Boolean.valueOf(featuresMissing));
    if (scheduleMode != null) {
      tr.extras.put("schedule_mode", scheduleMode);
    }
    tr.extras.put("estimated_extra_critical_path_ms", Long.valueOf(estLlmCriticalMs));
    tr.extras.put("cbo_parallel_started", Boolean.valueOf(cboParallelStarted));
    tr.extras.put("cbo_parallel_used", Boolean.valueOf(cboParallelUsed));
    tr.extras.put("cbo_parallel_waste", Boolean.valueOf(cboParallelWaste));
    tr.extras.put("t_cbo_parallel_await_ms", Long.valueOf(tCboParallelAwaitMs));
    tr.extras.put("t_cbo_parallel_exec_ms", Long.valueOf(tCboParallelExecMs));
    if (gateReason != null) {
      tr.extras.put("gate_reason", gateReason);
    }
    if (!sHatById.isEmpty()) {
      tr.extras.put("s_hat_by_plan", new LinkedHashMap<String, Double>(sHatById));
    }
    if (!Double.isInfinite(cboEstMs)) {
      tr.extras.put("cbo_estimated_ms", Double.valueOf(cboEstMs));
    }
    if (selectionAssist != null) {
      tr.extras.put("selection_assist", selectionAssist);
    }
    tr.extras.put("speculative_started", Boolean.valueOf(speculativeStarted));
    tr.extras.put("speculative_used", Boolean.valueOf(speculativeUsed));
    tr.extras.put("speculative_waste", Boolean.valueOf(speculativeWaste));
    tr.extras.put("speculative_reuse_validate", Boolean.valueOf(speculativeReuseValidate));
    if (speculativeIdsCsv != null) {
      tr.extras.put("speculative_plan_ids", speculativeIdsCsv);
    }
    if (speculativeRanker != null) {
      tr.extras.put("speculative_ranker", speculativeRanker);
    }
    // Compat aliases for prior census parsers.
    tr.extras.put("speculative_pt_started", Boolean.valueOf(speculativeStarted));
    tr.extras.put("speculative_pt_used", Boolean.valueOf(speculativeUsed));
    tr.extras.put("speculative_pt_waste", Boolean.valueOf(speculativeWaste));
    tr.extras.put("speculative_pt_reuse_validate", Boolean.valueOf(speculativeReuseValidate));
    tr.extras.put("t_cbo_search_ms", Long.valueOf(tCboSearchMs));
    tr.extras.put("t_spec_launch_ms", Long.valueOf(tSpecLaunchMs));
    tr.extras.put("t_post_validate_ms", Long.valueOf(tPostValidateMs));
    tr.extras.put("t_spec_await_ms", Long.valueOf(tSpecAwaitMs));
    tr.extras.put("t_spec_validate_ms", Long.valueOf(tSpecValidateMs));
    tr.extras.put("t_spec_exec_ms", Long.valueOf(tSpecExecMs));
    tr.extras.put("t_fixed_compile_ms", Long.valueOf(tFixedCompileMs));
    tr.extras.put("t_fixed_safety_ms", Long.valueOf(tFixedSafetyMs));
    tr.extras.put("t_fixed_features_ms", Long.valueOf(tFixedFeaturesMs));
    tr.extras.put("t_fixed_region_locate_ms", Long.valueOf(tFixedRegionLocateMs));
    tr.extras.put("t_fixed_cost_ms", Long.valueOf(tFixedCostMs));
    tr.extras.put("t_fixed_prepare_ms", Long.valueOf(tFixedPrepareMs));
    tr.extras.put("region_locate_calls", Integer.valueOf(regionLocateCalls));
    tr.extras.put("region_locate_cache_hits", Integer.valueOf(regionLocateCacheHits));
    tr.extras.put("n_scan_tasks", Integer.valueOf(nScanTasks));
    if (prepareModeUsed != null) {
      tr.extras.put("prepare_mode", prepareModeUsed);
    }
    if (lastValidatorTiming == null && selectedRun != null) {
      lastValidatorTiming = selectedRun.validatorTiming;
    }
    if (lastValidatorTiming != null) {
      lastValidatorTiming.putExtras(tr.extras);
    }
    tr.extras.put("llm_hint_mode", hintMode());
    if (llmAction != null) {
      tr.extras.put("llm_action", llmAction);
    }
    if (llmProposedAction != null) {
      tr.extras.put("llm_proposed_action", llmProposedAction);
    }
    if (llmProposedPlanId != null) {
      tr.extras.put("llm_proposed_plan_id", llmProposedPlanId);
    }
    if (llmRawResponse != null) {
      String raw = llmRawResponse;
      if (raw.length() > 512) {
        raw = raw.substring(0, 512);
      }
      tr.extras.put("llm_raw_response", raw);
    }
    if (llmParseStatus != null) {
      tr.extras.put("llm_parse_status", llmParseStatus);
    }
    if (llmActionId != null) {
      tr.extras.put("llm_action_id", llmActionId);
    }
    if (gateDecision != null) {
      tr.extras.put("gate_decision", gateDecision);
    }
    tr.extras.put("final_plan_id", selectedId);
    if (finalSelectionSource == null) {
      if (speculativeUsed) {
        finalSelectionSource = "speculate";
      } else if (cboParallelUsed) {
        finalSelectionSource = "cbo_parallel";
      } else if (proposalValid && selectedId != null && !selectedId.equals(cboPlanId)) {
        finalSelectionSource = "llm_adopt";
      } else {
        finalSelectionSource = "cbo";
      }
    }
    tr.extras.put("final_selection_source", finalSelectionSource);
    tr.extras.put("speculation_requested", Boolean.valueOf(speculationRequested));
    tr.extras.put("speculation_started", Boolean.valueOf(speculativeStarted));
    tr.extras.put("speculation_completed",
        Boolean.valueOf(speculativeStarted && (speculativeUsed || speculativeWaste)));
    tr.extras.put("speculation_used", Boolean.valueOf(speculativeUsed));
    tr.extras.put("speculation_waste", Boolean.valueOf(speculativeWaste));
    tr.extras.put("t_probe_ms", Long.valueOf(tProbeMs));
    if (probeResult != null) {
      tr.extras.put("probe_started", Boolean.valueOf(probeResult.started));
      tr.extras.put("probe_completed", Boolean.valueOf(probeResult.completed));
      tr.extras.put("probe_budget_exhausted", Boolean.valueOf(probeResult.budgetExhausted));
      tr.extras.put("probe_wall_ms", Long.valueOf(probeResult.wallMs));
      tr.extras.put("probe_rows_seen", Long.valueOf(probeResult.rowsSeen));
      tr.extras.put("probe_ranges_sampled", Integer.valueOf(probeResult.rangesSampled));
      tr.extras.put("probe_est_candidate_scale", Double.valueOf(probeResult.estCandidateScale));
      if (probeResult.error != null) {
        tr.extras.put("probe_error", probeResult.error);
      }
    }
    if (llmReasonCode != null) {
      tr.extras.put("llm_reason_code", llmReasonCode);
    }
    tr.extras.put("gate_fallback", Boolean.valueOf(gateFallback));
    tr.extras.put("cbo_llm_second_search", Boolean.FALSE);
    tr.extras.put("cache_key_version", CboLlmProposalCache.KEY_VERSION);
    tr.extras.put("timing_schema_version", "refine2_v1");
    if (skipReason != null) {
      tr.extras.put("llm_skip_reason", skipReason);
    }
    if (fallbackReason != null && !proposalValid
        && (proposalId == null || !proposalId.equals(selectedId))) {
      tr.extras.put("llm_fallback", Boolean.TRUE);
    } else {
      tr.extras.put("llm_fallback", Boolean.FALSE);
    }
    return tr;
  }

  private static void cancelAllSpeculative(Map<String, Future<SpecBundle>> futs) {
    if (futs == null) {
      return;
    }
    for (Future<SpecBundle> f : futs.values()) {
      cancelSpeculative(f);
    }
  }

  private static void cancelOtherSpeculative(Map<String, Future<SpecBundle>> futs,
                                             String keepId) {
    if (futs == null) {
      return;
    }
    for (Map.Entry<String, Future<SpecBundle>> e : futs.entrySet()) {
      if (keepId == null || !keepId.equals(e.getKey())) {
        cancelSpeculative(e.getValue());
      }
    }
  }

  private static void drainAllSpeculative(Map<String, Future<SpecBundle>> futs) {
    if (futs == null) {
      return;
    }
    for (Future<SpecBundle> f : futs.values()) {
      drainSpeculative(f);
    }
  }

  private static final class SpecBundle {
    final QueryEngine.RunResult executed;
    final long validateMs;
    final long execMs;

    SpecBundle(QueryEngine.RunResult executed, long validateMs, long execMs) {
      this.executed = executed;
      this.validateMs = validateMs;
      this.execMs = execMs;
    }
  }

  private static String joinCsv(List<String> ids) {
    if (ids == null || ids.isEmpty()) {
      return "";
    }
    StringBuilder sb = new StringBuilder();
    appendCsv(sb, ids);
    return sb.toString();
  }

  private static long nz(Long v) {
    return v == null ? 0L : v.longValue();
  }

  private static int ni(Integer v) {
    return v == null ? 0 : v.intValue();
  }

  private static kart.validation.ValidatorTiming vtOf(QueryEngine.RunResult rr) {
    return rr == null ? null : rr.validatorTiming;
  }

  private static QueryEngine.RunResult validateFixed(QueryEngine engine, BoundIr ir,
                                                     List<PlanEnvelope> constructable,
                                                     String planId) throws Exception {
    return validateFixed(engine, ir, constructable, planId, false);
  }

  /**
   * @param safetyOnly when true, compile+validate only (skip Final feature/cost);
   *     used by hybrid speculative prepare so region-locate is not paid twice.
   */
  private static QueryEngine.RunResult validateFixed(QueryEngine engine, BoundIr ir,
                                                     List<PlanEnvelope> constructable,
                                                     String planId,
                                                     boolean safetyOnly) throws Exception {
    PlanEnvelope env = findEnvelope(constructable, planId);
    if (env == null) {
      return null;
    }
    QueryEngine.PrepareMode mode = safetyOnly
        ? QueryEngine.PrepareMode.SAFETY_ONLY : QueryEngine.PrepareMode.FULL;
    QueryEngine.RunResult rr = engine.runFixed(ir, null, true, env, mode);
    if (rr == null || rr.selected == null) {
      return null;
    }
    return rr;
  }

  private static PlanEnvelope findEnvelope(List<PlanEnvelope> list, String planId) {
    if (list == null || planId == null) {
      return null;
    }
    for (PlanEnvelope e : list) {
      if (e != null && planId.equals(e.plan_id)) {
        return e;
      }
    }
    return null;
  }

  static Set<String> safePlanIds(QueryEngine.RunResult rr) {
    Set<String> ids = new LinkedHashSet<String>();
    if (rr == null || rr.safe == null) {
      return ids;
    }
    for (kart.validation.SafePlanHandle h : rr.safe) {
      if (h != null && h.plan() != null && h.plan().plan_id != null) {
        ids.add(h.plan().plan_id);
      }
    }
    return ids;
  }

  static List<String> novelIds(List<PlanEnvelope> constructable, Set<String> cboSafe) {
    List<String> out = new ArrayList<String>();
    if (constructable == null) {
      return out;
    }
    for (PlanEnvelope e : constructable) {
      if (e == null || e.plan_id == null) {
        continue;
      }
      if (cboSafe != null && cboSafe.contains(e.plan_id)) {
        continue;
      }
      // P_FULL is never a "novel improvement" target for this diagnostic arm.
      if ("P_FULL".equals(e.plan_id)) {
        continue;
      }
      out.add(e.plan_id);
    }
    return out;
  }

  /**
   * Drop merge-only variants when non-merge novel IDs exist. Merge-only novelty
   * does not justify an LLM call (selection-side slim).
   */
  static List<String> proposalWhitelist(List<String> novel) {
    List<String> out = new ArrayList<String>();
    if (novel == null) {
      return out;
    }
    for (String id : novel) {
      if (id == null || isMergeVariant(id)) {
        continue;
      }
      out.add(id);
    }
    return out;
  }

  static boolean isMergeVariant(String planId) {
    return planId != null && planId.contains("MERGE");
  }

  /**
   * @return skip reason when LLM must not run; {@code null} to call once.
   */
  static String llmSkipReason(BoundIr ir, List<String> whitelist, List<String> novel) {
    return llmSkipReason(ir, whitelist, novel, null, Double.POSITIVE_INFINITY);
  }

  static String llmSkipReason(BoundIr ir, List<String> whitelist, List<String> novel,
                              Double preferEstMs, double cboEstMs) {
    if ("v11".equals(resolveProtocol()) && alwaysCallLlm()) return null;
    if (whitelist == null || whitelist.isEmpty()) {
      if (novel != null && !novel.isEmpty()) {
        return "skip_merge_only_whitelist";
      }
      return "empty_whitelist";
    }
    if (alwaysCallLlm()) {
      return null;
    }
    if (!shouldTriggerLlm(ir, whitelist, preferEstMs, cboEstMs)) {
      return "skip_no_trigger";
    }
    return null;
  }

  /**
   * General trigger: FastCost says a novel beats CBO, and/or query uncertainty
   * is high (Top-K / similarity / wide ST), and/or CBO omitted a single-index
   * plan. Not a measured-winner map.
   */
  static boolean shouldTriggerLlm(BoundIr ir, List<String> whitelist) {
    return shouldTriggerLlm(ir, whitelist, null, Double.POSITIVE_INFINITY);
  }

  static boolean shouldTriggerLlm(BoundIr ir, List<String> whitelist,
                                  Double preferEstMs, double cboEstMs) {
    if (whitelist == null || whitelist.isEmpty()) {
      return false;
    }
    if (preferEstMs != null && preferEstMs.doubleValue() < cboEstMs * COST_BEAT_MARGIN) {
      return true;
    }
    if (isTopK(ir) || hasSimilarity(ir)) {
      return true;
    }
    if (temporalSpanMs(ir) >= TRIGGER_TEMPORAL_MS) {
      return true;
    }
    if (spatialArea(ir) >= TRIGGER_SPATIAL_AREA) {
      return true;
    }
    for (String id : whitelist) {
      if ("P_T".equals(id) || "P_Z".equals(id) || "P_H".equals(id)) {
        return true;
      }
    }
    return false;
  }

  static boolean isTopK(BoundIr ir) {
    return ir != null && ir.result != null && "TOP_K".equals(ir.result.mode);
  }

  static boolean hasSimilarity(BoundIr ir) {
    return ir != null && ir.similarity != null && ir.similarity.metric != null;
  }

  static long temporalSpanMs(BoundIr ir) {
    if (ir == null || ir.temporal == null) {
      return 0L;
    }
    return Math.max(0L, ir.temporal.end_ms - ir.temporal.start_ms);
  }

  static double spatialArea(BoundIr ir) {
    if (ir == null || ir.spatial == null) {
      return 0.0d;
    }
    double w = ir.spatial.max_x - ir.spatial.min_x;
    double h = ir.spatial.max_y - ir.spatial.min_y;
    if (w <= 0.0d || h <= 0.0d) {
      return 0.0d;
    }
    return w * h;
  }

  /** Parse legacy strict JSON {@code {"plan_id":"P_..."}} — no free-text scrape. */
  static String parsePlanIdJson(String content) {
    LlmDecision d = parseLlmDecision(content);
    if (d == null || !"propose".equals(d.action)) {
      return null;
    }
    return d.planId;
  }

  static final class LlmDecision {
    String action;
    String planId;
    String reasonCode;
  }

  /**
   * v7 structured decision: {@code keep_cbo} or {@code propose}. Also accepts
   * legacy single-field {@code {"plan_id":"P_..."}} as propose.
   */
  static String stripModelJson(String content) {
    if (content == null) {
      return null;
    }
    String s = content.trim();
    if (s.startsWith("```")) {
      int nl = s.indexOf('\n');
      if (nl > 0) {
        s = s.substring(nl + 1);
      }
      int fence = s.lastIndexOf("```");
      if (fence >= 0) {
        s = s.substring(0, fence);
      }
      s = s.trim();
    }
    // Extract first JSON object if prose wraps it.
    int l = s.indexOf('{');
    int r = s.lastIndexOf('}');
    if (l >= 0 && r > l) {
      s = s.substring(l, r + 1);
    }
    return s.trim();
  }

  static LlmDecision parseLlmDecision(String content) {
    if (content == null || content.trim().isEmpty()) {
      return null;
    }
    try {
      JsonNode n = MAPPER.readTree(stripModelJson(content));
      if (n == null || !n.isObject()) {
        return null;
      }
      LlmDecision d = new LlmDecision();
      if (n.has("action") && n.get("action").isTextual()) {
        String action = n.get("action").asText("").trim();
        if ("keep_cbo".equals(action)) {
          d.action = "keep_cbo";
          d.planId = null;
          if (n.has("reason_code") && n.get("reason_code").isTextual()) {
            d.reasonCode = normalizeReasonCode(n.get("reason_code").asText(null));
          }
          return d;
        }
        if ("propose".equals(action)) {
          d.action = "propose";
          if (!n.has("plan_id") || !n.get("plan_id").isTextual()) {
            return null;
          }
          String pid = n.get("plan_id").asText(null);
          if (pid == null) {
            return null;
          }
          pid = pid.trim();
          if (!pid.startsWith("P_")) {
            return null;
          }
          d.planId = pid;
          if (n.has("reason_code") && n.get("reason_code").isTextual()) {
            d.reasonCode = normalizeReasonCode(n.get("reason_code").asText(null));
          }
          return d;
        }
        return null;
      }
      // Legacy: {"plan_id":"P_..."} only.
      if (n.size() == 1 && n.has("plan_id") && n.get("plan_id").isTextual()) {
        String pid = n.get("plan_id").asText(null);
        if (pid == null) {
          return null;
        }
        pid = pid.trim();
        if (!pid.startsWith("P_")) {
          return null;
        }
        d.action = "propose";
        d.planId = pid;
        d.reasonCode = "other";
        return d;
      }
      return null;
    } catch (Exception e) {
      return null;
    }
  }

  static String normalizeReasonCode(String raw) {
    if (raw == null) {
      return null;
    }
    String r = raw.trim();
    if ("insufficient_gain".equals(r) || "lower_fetch_cost".equals(r)
        || "lower_scan_cost".equals(r) || "uncertainty".equals(r) || "other".equals(r)) {
      return r;
    }
    return "other";
  }

  /** Short KEEP/choice protocol (v8/v9). */
  private LlmAsk askLlmOnceShort(LlmClient llm, BoundIr ir, String cboPlanId,
                                 List<String> whitelist, Map<String, Double> sHatById,
                                 long budgetMs) {
    return askLlmOnceShort(llm, ir, cboPlanId, whitelist, sHatById, null,
        "speculate_candidate", 0L, BenefitCalibrator.resolveSavingUncertaintyMs(), budgetMs);
  }

  private LlmAsk askLlmOnceShort(LlmClient llm, BoundIr ir, String cboPlanId,
                                 List<String> whitelist, Map<String, Double> sHatById,
                                 Map<String, Double> predExecById, String scheduleMode,
                                 long estExtraCriticalMs, Double uncertaintyMs,
                                 long budgetMs) {
    LlmAsk out = new LlmAsk();
    String userPrompt = "v8".equals(resolveProtocol())
        ? buildShortPromptV8(ir, cboPlanId, whitelist, sHatById)
        : buildShortPrompt(ir, cboPlanId, whitelist, sHatById, predExecById,
            scheduleMode, estExtraCriticalMs, uncertaintyMs);
    List<LlmMessage> msgs = new ArrayList<LlmMessage>();
    msgs.add(LlmMessage.system(
        "Choose the safer plan expected to finish end-to-end sooner. "
            + "Reply one JSON only. Examples: {\"choice\":\"KEEP\"} or {\"choice\":0}. "
            + "Use an integer index from the whitelist, never the letter N. No other text."));
    msgs.add(LlmMessage.user(userPrompt));

    final long callBudget = Math.max(1L, budgetMs);
    LlmOptions opts = LlmOptions.oneShot((int) Math.min(Integer.MAX_VALUE, callBudget));
    if (opts.maxTokens == null || opts.maxTokens.intValue() > 32) {
      String raw = System.getenv("KART_CBO_LLM_MAX_TOKENS");
      if (raw == null || raw.trim().isEmpty()) {
        opts.maxTokens = Integer.valueOf(16);
      }
    }
    ExecutorService pool = Executors.newSingleThreadExecutor();
    long t0 = System.currentTimeMillis();
    Future<LlmResponse> fut = pool.submit(new Callable<LlmResponse>() {
      @Override
      public LlmResponse call() throws Exception {
        return llm.chat(msgs, null, opts);
      }
    });
    try {
      LlmResponse resp = fut.get(callBudget, TimeUnit.MILLISECONDS);
      out.latencyMs = Math.max(0L, System.currentTimeMillis() - t0);
      out.calls = 1;
      out.httpAttempts = resp != null && resp.attempts > 0 ? resp.attempts : 1;
      out.promptTokens = resp != null ? resp.promptTokens : null;
      out.completionTokens = resp != null ? resp.completionTokens : null;
      out.rawContent = resp != null ? resp.content : null;
      LlmDecision d = parseShortChoice(out.rawContent, whitelist);
      if (d == null) {
        out.parseStatus = "invalid";
        out.error = "non_json_or_missing_plan_id";
        return out;
      }
      out.parseStatus = "ok";
      out.action = d.action;
      out.reasonCode = d.reasonCode;
      if ("keep_cbo".equals(d.action)) {
        out.planId = null;
        out.error = null;
        return out;
      }
      out.planId = d.planId;
      String reject = classifyProposal(out.planId, cboPlanId, whitelist);
      if (reject != null) {
        out.error = reject;
        return out;
      }
      out.error = null;
      return out;
    } catch (TimeoutException te) {
      fut.cancel(true);
      out.latencyMs = Math.max(0L, System.currentTimeMillis() - t0);
      out.calls = 1;
      out.httpAttempts = 1;
      out.parseStatus = "timeout";
      out.error = "llm_timeout";
      return out;
    } catch (Exception e) {
      out.latencyMs = Math.max(0L, System.currentTimeMillis() - t0);
      out.calls = 1;
      out.httpAttempts = 1;
      out.parseStatus = "error";
      if (e.getCause() instanceof LlmException) {
        LlmException le = (LlmException) e.getCause();
        out.error = le.httpStatus != null ? ("http_" + le.httpStatus) : "http_failure";
      } else if (e instanceof LlmException) {
        LlmException le = (LlmException) e;
        out.error = le.httpStatus != null ? ("http_" + le.httpStatus) : "http_failure";
      } else {
        out.error = "http_failure";
      }
      return out;
    } finally {
      pool.shutdownNow();
    }
  }

  /** Legacy v8 compact prompt. */
  static String buildShortPromptV8(BoundIr ir, String cboPlanId, List<String> whitelist,
                                   Map<String, Double> sHatById) {
    StringBuilder sb = new StringBuilder();
    sb.append("prompt_version=").append(PROMPT_VERSION_V8).append('\n');
    sb.append("cbo=").append(cboPlanId == null ? "?" : cboPlanId).append('\n');
    sb.append("q=").append(summarizeQuery(ir)).append('\n');
    if (whitelist != null) {
      for (int i = 0; i < whitelist.size(); i++) {
        String pid = whitelist.get(i);
        sb.append(i).append('=').append(pid);
        sb.append(" access=").append(accessLabel(pid));
        if (sHatById != null && sHatById.containsKey(pid)) {
          sb.append(" s_hat=").append(Math.round(sHatById.get(pid).doubleValue()));
        }
        sb.append('\n');
      }
    }
    sb.append("Reply {\"choice\":\"KEEP\"} or {\"choice\":0}\n");
    return sb.toString();
  }

  /** Compat: v9 builder with defaults. */
  static String buildShortPrompt(BoundIr ir, String cboPlanId, List<String> whitelist,
                                 Map<String, Double> sHatById) {
    return buildShortPrompt(ir, cboPlanId, whitelist, sHatById, null,
        "speculate_candidate", 1200L, BenefitCalibrator.resolveSavingUncertaintyMs());
  }

  /**
   * refine_5 / v9: explicit exec_saving semantics + schedule/extra/uncertainty.
   * Does not include measured winners.
   */
  static String buildShortPrompt(BoundIr ir, String cboPlanId, List<String> whitelist,
                                 Map<String, Double> sHatById,
                                 Map<String, Double> predExecById,
                                 String scheduleMode, long estExtraCriticalMs,
                                 Double uncertaintyMs) {
    StringBuilder sb = new StringBuilder();
    sb.append("prompt_version=").append(PROMPT_VERSION_V9).append('\n');
    sb.append("task=choose_faster_safe_e2e_plan\n");
    sb.append("exec_saving_ms: ms; positive => candidate exec faster than CBO; ");
    sb.append("does NOT subtract LLM/prep critical-path cost.\n");
    sb.append("All values are predictions. Uncertainty unknown != 0.\n");
    sb.append("If evidence insufficient or predicted net gain non-positive: KEEP.\n");
    sb.append("Else pick a legal candidate index.\n");
    sb.append("schedule_mode=").append(scheduleMode == null ? "?" : scheduleMode).append('\n');
    sb.append("estimated_extra_critical_path_ms=").append(estExtraCriticalMs).append('\n');
    if (uncertaintyMs == null) {
      sb.append("saving_uncertainty_ms=unknown\n");
    } else {
      sb.append("saving_uncertainty_ms=").append(Math.round(uncertaintyMs.doubleValue()))
          .append('\n');
    }
    sb.append("cbo=").append(cboPlanId == null ? "?" : cboPlanId);
    if (predExecById != null && cboPlanId != null && predExecById.containsKey(cboPlanId)) {
      sb.append(" cbo_exec_ms=").append(Math.round(predExecById.get(cboPlanId).doubleValue()));
    }
    sb.append('\n');
    sb.append("q=").append(summarizeQuery(ir)).append('\n');
    if (whitelist != null) {
      for (int i = 0; i < whitelist.size(); i++) {
        String pid = whitelist.get(i);
        sb.append(i).append('=').append(pid);
        sb.append(" access=").append(accessLabel(pid));
        if (sHatById != null && sHatById.containsKey(pid)) {
          sb.append(" exec_saving_ms=")
              .append(Math.round(sHatById.get(pid).doubleValue()));
        }
        if (predExecById != null && predExecById.containsKey(pid)) {
          sb.append(" candidate_exec_ms=")
              .append(Math.round(predExecById.get(pid).doubleValue()));
        }
        sb.append('\n');
      }
    }
    sb.append("Examples: {\"choice\":\"KEEP\"}  OR  {\"choice\":0}\n");
    sb.append("Use integer index only (0,1,...). Never output the letter N.\n");
    return sb.toString();
  }

  /**
   * Parse short protocol: {@code {"choice":"KEEP"}} or {@code {"choice":N}}.
   * Also tolerates bare KEEP / integer and legacy action JSON.
   */
  static LlmDecision parseShortChoice(String raw, List<String> whitelist) {
    if (raw == null) {
      return null;
    }
    String text = stripModelJson(raw);
    if (text == null) {
      return null;
    }
    text = text.trim();
    if (text.isEmpty()) {
      return null;
    }
    // Bare KEEP / integer
    if ("KEEP".equalsIgnoreCase(text) || "keep_cbo".equalsIgnoreCase(text)) {
      LlmDecision d = new LlmDecision();
      d.action = "keep_cbo";
      d.reasonCode = "other";
      return d;
    }
    try {
      int bare = Integer.parseInt(text);
      if (whitelist != null && bare >= 0 && bare < whitelist.size()) {
        LlmDecision d = new LlmDecision();
        d.action = "propose";
        d.planId = whitelist.get(bare);
        d.reasonCode = "other";
        return d;
      }
    } catch (NumberFormatException ignore) {
      // fall through
    }
    try {
      int brace = text.indexOf('{');
      int end = text.lastIndexOf('}');
      if (brace >= 0 && end > brace) {
        text = text.substring(brace, end + 1);
      }
      JsonNode n = MAPPER.readTree(text);
      if (n == null || !n.isObject()) {
        return null;
      }
      if (n.has("choice")) {
        JsonNode ch = n.get("choice");
        if (ch.isTextual()) {
          String c = ch.asText("").trim();
          if ("KEEP".equalsIgnoreCase(c) || "keep_cbo".equalsIgnoreCase(c)
              || "null".equalsIgnoreCase(c)) {
            LlmDecision d = new LlmDecision();
            d.action = "keep_cbo";
            d.reasonCode = "other";
            return d;
          }
          // Model often copies the letter N from "{choice:N}" docs — reject.
          if ("N".equalsIgnoreCase(c) || "n/a".equalsIgnoreCase(c)) {
            return null;
          }
          try {
            int idx = Integer.parseInt(c);
            if (whitelist != null && idx >= 0 && idx < whitelist.size()) {
              LlmDecision d = new LlmDecision();
              d.action = "propose";
              d.planId = whitelist.get(idx);
              d.reasonCode = "other";
              return d;
            }
          } catch (NumberFormatException e) {
            return null;
          }
          return null;
        }
        if (ch.isNumber()) {
          int idx = ch.asInt(-1);
          if (whitelist != null && idx >= 0 && idx < whitelist.size()) {
            LlmDecision d = new LlmDecision();
            d.action = "propose";
            d.planId = whitelist.get(idx);
            d.reasonCode = "other";
            return d;
          }
          return null;
        }
      }
      // Fallback: legacy action JSON
      return parseLlmDecision(raw);
    } catch (Exception e) {
      return null;
    }
  }

  /** Single independent LLM call; no prefer-corrective retry. */
  private LlmAsk askLlmOnceIndependent(LlmClient llm, BoundIr ir, String cboPlanId,
                                       List<String> whitelist, String rankHintId,
                                       long budgetMs) {
    LlmAsk out = new LlmAsk();
    String userPrompt = buildPrompt(ir, cboPlanId, whitelist, rankHintId);
    List<LlmMessage> msgs = new ArrayList<LlmMessage>();
    String sys = "Output one JSON object only. "
        + "action=keep_cbo or propose. "
        + "If propose, plan_id MUST be copied exactly from whitelist. "
        + "Never output forbidden. Rank hint is optional.";
    msgs.add(LlmMessage.system(sys));
    msgs.add(LlmMessage.user(userPrompt));

    final long callBudget = Math.max(1L, budgetMs);
    LlmOptions opts = LlmOptions.oneShot((int) Math.min(Integer.MAX_VALUE, callBudget));
    ExecutorService pool = Executors.newSingleThreadExecutor();
    long t0 = System.currentTimeMillis();
    Future<LlmResponse> fut = pool.submit(new Callable<LlmResponse>() {
      @Override
      public LlmResponse call() throws Exception {
        return llm.chat(msgs, null, opts);
      }
    });
    try {
      LlmResponse resp = fut.get(callBudget, TimeUnit.MILLISECONDS);
      out.latencyMs = Math.max(0L, System.currentTimeMillis() - t0);
      out.calls = 1;
      out.httpAttempts = resp != null && resp.attempts > 0 ? resp.attempts : 1;
      out.promptTokens = resp != null ? resp.promptTokens : null;
      out.completionTokens = resp != null ? resp.completionTokens : null;
      LlmDecision d = parseLlmDecision(resp != null ? resp.content : null);
      if (d == null) {
        out.error = "non_json_or_missing_plan_id";
        return out;
      }
      out.action = d.action;
      out.reasonCode = d.reasonCode;
      if ("keep_cbo".equals(d.action)) {
        out.planId = null;
        out.error = null;
        return out;
      }
      out.planId = d.planId;
      String reject = classifyProposal(out.planId, cboPlanId, whitelist);
      if (reject != null) {
        out.error = reject;
        return out;
      }
      out.error = null;
      return out;
    } catch (TimeoutException te) {
      fut.cancel(true);
      out.latencyMs = Math.max(0L, System.currentTimeMillis() - t0);
      out.calls = 1;
      out.httpAttempts = 1;
      out.error = "llm_timeout";
      return out;
    } catch (Exception e) {
      out.latencyMs = Math.max(0L, System.currentTimeMillis() - t0);
      out.calls = 1;
      out.httpAttempts = 1;
      if (e.getCause() instanceof LlmException) {
        LlmException le = (LlmException) e.getCause();
        out.error = le.httpStatus != null ? ("http_" + le.httpStatus) : "http_failure";
      } else if (e instanceof LlmException) {
        LlmException le = (LlmException) e;
        out.error = le.httpStatus != null ? ("http_" + le.httpStatus) : "http_failure";
      } else {
        out.error = "http_failure";
      }
      return out;
    } finally {
      pool.shutdownNow();
    }
  }

  /**
   * @return null when {@code planId} is a usable whitelist proposal; else a
   *     fallback_reason code ({@code duplicate_cbo_id}, {@code unknown_plan_id},
   *     {@code non_json_or_missing_plan_id}).
   */
  static String classifyProposal(String planId, String cboPlanId, List<String> whitelist) {
    if (planId == null || planId.isEmpty()) {
      return "non_json_or_missing_plan_id";
    }
    if (cboPlanId != null && cboPlanId.equals(planId)) {
      return "duplicate_cbo_id";
    }
    if (whitelist == null || !whitelist.contains(planId)) {
      return "unknown_plan_id";
    }
    return null;
  }

  /**
   * v7 disables prefer-corrective soft retry; always false (kept for tests).
   */
  static boolean shouldSoftRetryPrefer(String planId, String preferId, List<String> whitelist) {
    return false;
  }

  /** @deprecated use {@link #shouldSoftRetryPrefer(String, String, List)}. */
  static boolean shouldSoftRetryPreferPt(BoundIr ir, String planId, List<String> whitelist) {
    if (!"P_Z".equals(planId) || whitelist == null || !whitelist.contains("P_T")) {
      return false;
    }
    return prefersTimeOnlyPrior(ir);
  }

  /** Legacy shape prior (tests / docs); v6 ranking uses FastCost instead. */
  static boolean prefersTimeOnlyPrior(BoundIr ir) {
    if (isTopK(ir) || hasSimilarity(ir)) {
      return true;
    }
    if (temporalSpanMs(ir) >= TRIGGER_TEMPORAL_MS && hasSpatial(ir)) {
      return true;
    }
    return false;
  }

  static boolean hasSpatial(BoundIr ir) {
    return ir != null && ir.spatial != null;
  }

  static boolean hasTemporal(BoundIr ir) {
    return ir != null && ir.temporal != null;
  }

  /**
   * Stable whitelist order: {@code P_T} before {@code P_Z} so small models see
   * the time-only miss first. Not a measured-winner map.
   */
  static List<String> orderWhitelist(List<String> whitelist) {
    if (whitelist == null || whitelist.isEmpty()) {
      return new ArrayList<String>();
    }
    List<String> out = new ArrayList<String>(whitelist);
    java.util.Collections.sort(out, new java.util.Comparator<String>() {
      @Override
      public int compare(String a, String b) {
        int da = whitelistRank(a) - whitelistRank(b);
        if (da != 0) {
          return da;
        }
        return String.valueOf(a).compareTo(String.valueOf(b));
      }
    });
    return out;
  }

  static int whitelistRank(String id) {
    if ("P_T".equals(id)) {
      return 0;
    }
    if ("P_H".equals(id)) {
      return 1;
    }
    if ("P_Z".equals(id)) {
      return 2;
    }
    return 3;
  }

  static String accessLabel(String planId) {
    if (planId == null) {
      return "unknown";
    }
    if ("P_T".equals(planId)) {
      return "TIME_ONLY";
    }
    if ("P_Z".equals(planId)) {
      return "ZORDER_ONLY";
    }
    if ("P_H".equals(planId)) {
      return "HASH_ONLY";
    }
    if (planId.startsWith("P_TZ") || planId.contains("TZ")) {
      return "TIME_AND_ZORDER";
    }
    return "other";
  }

  /**
   * Independent prompt v7: FastCost-ranked whitelist + forbidden + uncertain
   * rank_hint (not a must-obey prefer). No measured winners / est_ms dump.
   */
  static String buildPrompt(BoundIr ir, String cboPlanId, List<String> whitelist) {
    String hint = (whitelist != null && !whitelist.isEmpty()) ? whitelist.get(0) : null;
    return buildPrompt(ir, cboPlanId, whitelist, hint);
  }

  /**
   * Hint ablation: {@code KART_CBO_LLM_HINT=on|off|shuffle} (default on).
   * shuffle rotates whitelist display order and omits rank_hint.
   */
  static String hintMode() {
    String raw = System.getenv("KART_CBO_LLM_HINT");
    if (raw == null || raw.trim().isEmpty()) {
      return "on";
    }
    String v = raw.trim().toLowerCase();
    if (v.equals("0") || v.equals("false") || v.equals("off") || v.equals("no")) {
      return "off";
    }
    if (v.equals("shuffle") || v.equals("rotate")) {
      return "shuffle";
    }
    return "on";
  }

  static List<String> maybeShuffleWhitelist(List<String> whitelist) {
    if (whitelist == null || whitelist.size() < 2 || !"shuffle".equals(hintMode())) {
      return whitelist;
    }
    List<String> out = new ArrayList<String>(whitelist);
    // Deterministic rotate by query-independent salt: move first to end.
    String head = out.remove(0);
    out.add(head);
    return out;
  }

  static String buildPrompt(BoundIr ir, String cboPlanId, List<String> whitelist,
                            String rankHintId) {
    String mode = hintMode();
    List<String> shown = maybeShuffleWhitelist(whitelist);
    StringBuilder sb = new StringBuilder();
    sb.append("prompt_version=").append(PROMPT_VERSION_V7).append('\n');
    sb.append("hint_mode=").append(mode).append('\n');
    sb.append("Pick keep_cbo OR one whitelist id. Never forbidden.\n");
    sb.append("Examples:\n");
    sb.append("{\"action\":\"keep_cbo\",\"plan_id\":null,\"reason_code\":\"insufficient_gain\"}\n");
    if (shown != null && !shown.isEmpty()) {
      sb.append("{\"action\":\"propose\",\"plan_id\":\"").append(shown.get(0))
          .append("\",\"reason_code\":\"lower_scan_cost\"}\n");
    }
    sb.append("whitelist=");
    appendCsv(sb, shown);
    sb.append('\n');
    sb.append("forbidden=");
    sb.append(cboPlanId == null ? "" : cboPlanId);
    sb.append('\n');
    sb.append("q=");
    sb.append(summarizeQuery(ir));
    sb.append('\n');
    if ("on".equals(mode) && rankHintId != null && whitelist != null
        && whitelist.contains(rankHintId)) {
      sb.append("rank_hint=").append(rankHintId)
          .append(" uncertain; may ignore\n");
    }
    sb.append("Reply ONE JSON object only.\n");
    return sb.toString();
  }

  static String summarizeQuery(BoundIr ir) {
    if (ir == null) {
      return "empty";
    }
    StringBuilder sb = new StringBuilder();
    if (isTopK(ir)) {
      sb.append("topk");
      if (ir.result != null && ir.result.k != null) {
        sb.append('=').append(ir.result.k);
      }
    } else {
      sb.append("ids");
    }
    if (hasSimilarity(ir)) {
      sb.append(",sim=").append(ir.similarity.metric);
    }
    sb.append(",t_ms=").append(temporalSpanMs(ir));
    sb.append(",area=").append((long) spatialArea(ir));
    return sb.toString();
  }

  /** @deprecated use {@link #buildPrompt(BoundIr, String, List)}. */
  static String buildPrompt(String cboPlanId, List<String> whitelist) {
    return buildPrompt(null, cboPlanId, whitelist);
  }

  /** Corrective prompt after an invalid first reply (same budget pool). */
  static String buildRetryPrompt(BoundIr ir, String cboPlanId, List<String> whitelist,
                                 String previousInvalid) {
    String prefer = (whitelist != null && !whitelist.isEmpty()) ? whitelist.get(0) : null;
    return buildRetryPrompt(ir, cboPlanId, whitelist, prefer, previousInvalid);
  }

  static String buildRetryPrompt(BoundIr ir, String cboPlanId, List<String> whitelist,
                                 String preferId, String previousInvalid) {
    StringBuilder sb = new StringBuilder();
    sb.append("prompt_version=").append(PROMPT_VERSION).append("_retry\n");
    sb.append("Previous plan_id was invalid. Pick a DIFFERENT id from whitelist only.\n");
    sb.append("Reply ONLY JSON: {\"plan_id\":\"P_...\"}\n");
    sb.append("whitelist=");
    appendCsv(sb, whitelist);
    sb.append('\n');
    sb.append("forbidden=");
    sb.append(cboPlanId == null ? "" : cboPlanId);
    sb.append('\n');
    sb.append("previous_invalid=");
    sb.append(previousInvalid == null ? "" : previousInvalid);
    sb.append('\n');
    if (preferId != null && whitelist != null && whitelist.contains(preferId)) {
      sb.append("prefer=").append(preferId).append('\n');
    }
    return sb.toString();
  }

  /** Soft retry toward FastCost prefer id. */
  static String buildPreferRetryPrompt(BoundIr ir, String cboPlanId, List<String> whitelist,
                                       String preferId, String previousId) {
    StringBuilder sb = new StringBuilder();
    sb.append("prompt_version=").append(PROMPT_VERSION).append("_prefer\n");
    sb.append("Prefer ").append(preferId == null ? "?" : preferId)
        .append(". Reply ONLY JSON: {\"plan_id\":\"")
        .append(preferId == null ? "P_..." : preferId).append("\"}\n");
    sb.append("whitelist=");
    appendCsv(sb, whitelist);
    sb.append('\n');
    sb.append("forbidden=");
    sb.append(cboPlanId == null ? "" : cboPlanId);
    if (previousId != null && !previousId.equals(cboPlanId)) {
      sb.append(',').append(previousId);
    }
    sb.append('\n');
    if (preferId != null) {
      sb.append("prefer=").append(preferId).append('\n');
    }
    return sb.toString();
  }

  /** @deprecated use {@link #buildPreferRetryPrompt}. */
  static String buildPreferPtRetryPrompt(BoundIr ir, String cboPlanId, List<String> whitelist,
                                         String previousId) {
    return buildPreferRetryPrompt(ir, cboPlanId, whitelist, "P_T", previousId);
  }

  /** @deprecated kept for older call sites. */
  static String buildRetryPrompt(String cboPlanId, List<String> whitelist,
                                 String previousInvalid) {
    return buildRetryPrompt(null, cboPlanId, whitelist, previousInvalid);
  }

  private static void appendCsv(StringBuilder sb, List<String> ids) {
    if (ids == null || ids.isEmpty()) {
      return;
    }
    for (int i = 0; i < ids.size(); i++) {
      if (i > 0) {
        sb.append(',');
      }
      sb.append(ids.get(i));
    }
  }

  /** @deprecated kept for older tests; delegates to compact builder. */
  static String buildPrompt(BoundIr ir, String cboPlanId, List<kart.cost.CostCard> cards,
                            List<PlanEnvelope> constructable, List<String> whitelist,
                            kart.cost.FastCost fastCost) {
    return buildPrompt(ir, cboPlanId, whitelist);
  }

  private static String cacheKey(BoundIr ir, BenchContext ctx) {
    String manifest = ctx.manifest != null ? ctx.manifest.manifest_id : null;
    String sem = null;
    if (ir != null && ir.snapshot != null) {
      sem = ir.snapshot.semantics_version;
      if (manifest == null) {
        manifest = ir.snapshot.manifest_id;
      }
    }
    String layoutVer = ctx.layout != null ? String.valueOf(ctx.layout.hashCode()) : "";
    String costVer = "";
    if (ctx.planner != null && ctx.planner.cost != null
        && ctx.planner.cost.model_version != null) {
      costVer = ctx.planner.cost.model_version;
    }
    return CboLlmProposalCache.keyFor(ir, manifest, sem, layoutVer, costVer);
  }

  private static void stampPlanClock(TrialResult tr, long start, long end) {
    tr.t_plan_ms = Long.valueOf(Math.max(0L, end - start));
    tr.extras.put("plan_start_ms", Long.valueOf(start));
    tr.extras.put("plan_end_ms", Long.valueOf(end));
    tr.t_e2e_ms = tr.t_plan_ms;
  }

  private static void stampBaseExtras(TrialResult tr, String cboPlanId, String proposalId,
                                      String selectedId, boolean novel, boolean valid,
                                      boolean triggered, int calls, long llmLat, long valMs,
                                      String fallback, boolean cacheHit, String cacheKey) {
    tr.extras.put("cbo_plan_id", cboPlanId);
    tr.extras.put("proposal_plan_id", proposalId);
    tr.extras.put("selected_plan_id", selectedId);
    tr.extras.put("proposal_novel", Boolean.valueOf(novel));
    tr.extras.put("proposal_valid", Boolean.valueOf(valid));
    tr.extras.put("llm_triggered", Boolean.valueOf(triggered));
    tr.extras.put("llm_calls", Long.valueOf(calls));
    tr.extras.put("llm_latency_ms", Long.valueOf(llmLat));
    tr.extras.put("validation_ms", Long.valueOf(valMs));
    if (fallback != null) {
      tr.extras.put("fallback_reason", fallback);
    }
    tr.extras.put("cache_hit", Boolean.valueOf(cacheHit));
    if (cacheKey != null) {
      tr.extras.put("cache_key", cacheKey);
    }
  }

  /**
   * Map restricted action id → whitelist plan (or null for keep/probe-handled).
   */
  static String mapActionToProposal(String actionId, List<String> whitelist,
                                    String preferId, String cboPlanId) {
    if (actionId == null) {
      return null;
    }
    if ("NO_ACTION".equals(actionId) || "KEEP".equals(actionId)) {
      return null;
    }
    if ("PROBE_JOINT_CANDIDATES".equals(actionId) || "CHECK_INTERSECT".equals(actionId)
        || "CHECK_FRAGMENTATION".equals(actionId)) {
      return null; // handled by probe / deterministic path
    }
    String want = null;
    if ("PROPOSE_TIME".equals(actionId) || "PROPOSE_P_T".equals(actionId)) {
      want = "P_T";
    } else if ("PROPOSE_ZORDER".equals(actionId) || "PROPOSE_P_Z".equals(actionId)) {
      want = "P_Z";
    } else if ("PROPOSE_JOINT".equals(actionId) || "PROPOSE_P_TZ".equals(actionId)) {
      want = "P_TZ";
    } else if ("PROPOSE_PREFER".equals(actionId)) {
      want = preferId;
    }
    if (want == null || whitelist == null || !whitelist.contains(want)) {
      return null;
    }
    if (cboPlanId != null && cboPlanId.equals(want)) {
      return null;
    }
    return want;
  }

  private LlmAsk askLlmOnceAction(LlmClient llm, BoundIr ir, String cboPlanId,
                                  List<String> whitelist, Map<String, Double> sHatById,
                                  String scheduleMode, long budgetMs) {
    LlmAsk out = new LlmAsk();
    StringBuilder sb = new StringBuilder();
    sb.append("prompt_version=").append(PROMPT_VERSION_V10).append('\n');
    sb.append("task=propose_one_check_or_candidate\n");
    sb.append("You do NOT compare floats. Propose ONE action id from:\n");
    sb.append("NO_ACTION, PROBE_JOINT_CANDIDATES, CHECK_INTERSECT, ");
    sb.append("PROPOSE_P_T, PROPOSE_P_Z, PROPOSE_PREFER\n");
    sb.append("schedule_mode=").append(scheduleMode == null ? "?" : scheduleMode).append('\n');
    sb.append("cbo=").append(cboPlanId == null ? "?" : cboPlanId).append('\n');
    sb.append("q=").append(summarizeQuery(ir)).append('\n');
    sb.append("whitelist=");
    appendCsv(sb, whitelist);
    sb.append('\n');
    if (whitelist != null) {
      for (String pid : whitelist) {
        sb.append("cand=").append(pid);
        if (sHatById != null && sHatById.containsKey(pid)) {
          sb.append(" uncertain_saving_hint=")
              .append(Math.round(sHatById.get(pid).doubleValue()));
        }
        sb.append('\n');
      }
    }
    sb.append("Reply ONLY {\"action\":\"PROPOSE_P_T\"} style JSON.\n");
    List<LlmMessage> msgs = new ArrayList<LlmMessage>();
    msgs.add(LlmMessage.system(
        "Propose one allowed action id. Examples: "
            + "{\"action\":\"NO_ACTION\"} or {\"action\":\"PROPOSE_P_T\"} "
            + "or {\"action\":\"PROBE_JOINT_CANDIDATES\"}. No other text."));
    msgs.add(LlmMessage.user(sb.toString()));

    final long callBudget = Math.max(1L, budgetMs);
    LlmOptions opts = LlmOptions.oneShot((int) Math.min(Integer.MAX_VALUE, callBudget));
    if (opts.maxTokens == null || opts.maxTokens.intValue() > 32) {
      String raw = System.getenv("KART_CBO_LLM_MAX_TOKENS");
      if (raw == null || raw.trim().isEmpty()) {
        opts.maxTokens = Integer.valueOf(24);
      }
    }
    ExecutorService pool = Executors.newSingleThreadExecutor();
    long t0 = System.currentTimeMillis();
    Future<LlmResponse> fut = pool.submit(new Callable<LlmResponse>() {
      @Override
      public LlmResponse call() throws Exception {
        return llm.chat(msgs, null, opts);
      }
    });
    try {
      LlmResponse resp = fut.get(callBudget, TimeUnit.MILLISECONDS);
      out.latencyMs = Math.max(0L, System.currentTimeMillis() - t0);
      out.calls = 1;
      out.httpAttempts = resp != null && resp.attempts > 0 ? resp.attempts : 1;
      out.promptTokens = resp != null ? resp.promptTokens : null;
      out.completionTokens = resp != null ? resp.completionTokens : null;
      out.rawContent = resp != null ? resp.content : null;
      String actionId = parseActionId(out.rawContent);
      if (actionId == null) {
        out.parseStatus = "invalid";
        out.error = "non_json_or_missing_action";
        out.action = "keep_cbo";
        return out;
      }
      out.parseStatus = "ok";
      out.actionId = actionId;
      if ("NO_ACTION".equals(actionId) || "KEEP".equals(actionId)) {
        out.action = "keep_cbo";
        out.planId = null;
        return out;
      }
      out.action = "propose";
      out.planId = mapActionToProposal(actionId, whitelist,
          whitelist != null && !whitelist.isEmpty() ? whitelist.get(0) : null, cboPlanId);
      return out;
    } catch (TimeoutException te) {
      fut.cancel(true);
      out.latencyMs = Math.max(0L, System.currentTimeMillis() - t0);
      out.calls = 1;
      out.httpAttempts = 1;
      out.parseStatus = "timeout";
      out.error = "llm_timeout";
      out.action = "keep_cbo";
      return out;
    } catch (Exception e) {
      out.latencyMs = Math.max(0L, System.currentTimeMillis() - t0);
      out.calls = 1;
      out.httpAttempts = 1;
      out.parseStatus = "error";
      out.error = "http_failure";
      out.action = "keep_cbo";
      return out;
    } finally {
      pool.shutdownNow();
    }
  }

  static String parseActionId(String raw) {
    if (raw == null) {
      return null;
    }
    String text = stripModelJson(raw);
    if (text == null) {
      return null;
    }
    text = text.trim();
    String[] allowed = new String[] {
        "NO_ACTION", "KEEP", "PROBE_JOINT_CANDIDATES", "CHECK_INTERSECT",
        "CHECK_FRAGMENTATION", "PROPOSE_P_T", "PROPOSE_P_Z", "PROPOSE_P_TZ",
        "PROPOSE_TIME", "PROPOSE_ZORDER", "PROPOSE_JOINT", "PROPOSE_PREFER"
    };
    for (String a : allowed) {
      if (a.equalsIgnoreCase(text)) {
        return a.toUpperCase().replace("KEEP", "NO_ACTION");
      }
    }
    try {
      int brace = text.indexOf('{');
      int end = text.lastIndexOf('}');
      if (brace >= 0 && end > brace) {
        JsonNode n = MAPPER.readTree(text.substring(brace, end + 1));
        if (n != null && n.has("action") && n.get("action").isTextual()) {
          String a = n.get("action").asText("").trim().toUpperCase();
          for (String allow : allowed) {
            if (allow.equals(a) || ("KEEP".equals(a) && "NO_ACTION".equals(allow))) {
              return "KEEP".equals(a) ? "NO_ACTION" : a;
            }
          }
        }
      }
    } catch (Exception ignore) {
      return null;
    }
    return null;
  }

  static LlmDecision parseCompactChoice(String raw, List<String> whitelist) {
    if (raw == null) return null;
    String s = raw.trim();
    LlmDecision d = new LlmDecision();
    if ("K".equals(s)) { d.action="keep_cbo"; return d; }
    if (!s.matches("[0-9]")) return null;
    int index = s.charAt(0)-'0';
    if (whitelist == null || index >= whitelist.size()) return null;
    d.action="propose"; d.planId=whitelist.get(index); return d;
  }

  private LlmAsk askLlmCompact(final LlmClient llm, String cboId,
      final List<String> whitelist, Map<String,Double> gains, Double margin, long budget) {
    final List<LlmMessage> msgs = new ArrayList<LlmMessage>();
    msgs.add(LlmMessage.system("Select the candidate with greatest predicted saving. Return the choice JSON."));
    StringBuilder prompt=new StringBuilder("Eligible candidates; saved milliseconds:\n");
    for(int i=0;i<whitelist.size() && i<10;i++) {
      String id=whitelist.get(i);
      prompt.append(i).append(' ').append(id).append(' ').append(Math.round(gains.get(id)-margin)).append('\n');
    }
    prompt.append(whitelist.isEmpty()?"No eligible candidate. Output K.":"Index:");
    msgs.add(LlmMessage.user(prompt.toString()));
    final LlmOptions opts=LlmOptions.oneShot((int)Math.min(Integer.MAX_VALUE,budget));
    opts.maxTokens=16; opts.jsonObjectFormat=false; opts.responseSchemaFormat=true;
    final com.fasterxml.jackson.databind.node.ObjectNode schema=MAPPER.createObjectNode();
    schema.put("type","object"); schema.put("additionalProperties",false);
    schema.putArray("required").add("choice");
    com.fasterxml.jackson.databind.node.ObjectNode choice=schema.putObject("properties").putObject("choice");
    choice.put("type","string");
    com.fasterxml.jackson.databind.node.ArrayNode allowed=choice.putArray("enum");
    allowed.add("K");
    for(int i=0;i<whitelist.size() && i<10;i++)allowed.add(String.valueOf(i));
    LlmAsk out=new LlmAsk(); out.calls=1; out.httpAttempts=1;
    ExecutorService pool=Executors.newSingleThreadExecutor();
    Future<LlmResponse> fut=null; long start=System.nanoTime();
    try {
      fut=pool.submit(new Callable<LlmResponse>() { public LlmResponse call() throws Exception {return llm.chat(msgs,schema,opts);} });
      LlmResponse r=fut.get(budget,TimeUnit.MILLISECONDS);
      out.rawContent=r.content;out.promptTokens=r.promptTokens;out.completionTokens=r.completionTokens;
      String rawChoice = null;
      try { JsonNode parsed=MAPPER.readTree(r.content); if(parsed.isObject() && parsed.size()==1 && parsed.path("choice").isTextual()) rawChoice=parsed.path("choice").asText(); } catch(Exception ignore) { }
      LlmDecision d=parseCompactChoice(rawChoice,whitelist);
      if(d==null){out.error="invalid_compact_choice";out.parseStatus="invalid";}
      else {out.action=d.action;out.planId=d.planId;out.parseStatus="ok";}
    } catch(TimeoutException e){out.error="llm_timeout";}
      catch(Exception e){out.error="llm_failure";}
    finally {if(fut!=null && !fut.isDone())fut.cancel(true);pool.shutdownNow();out.latencyMs=(System.nanoTime()-start)/1000000L;}
    return out;
  }

  private static final class LlmAsk {
    String planId;
    String action;
    String actionId;
    String reasonCode;
    String error;
    String rawContent;
    String parseStatus;
    int calls;
    int httpAttempts;
    long latencyMs;
    Integer promptTokens;
    Integer completionTokens;
  }
}
