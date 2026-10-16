package kart.search;

import kart.compile.LayoutContext;
import kart.compile.PhysicalPlan;
import kart.compile.QueryCompiler;
import kart.cost.CostCard;
import kart.cost.FastCost;
import kart.cost.AdvFinalCalibrator;
import kart.cost.PlanSelector;
import kart.cost.AdvQueryRouter;
import kart.ir.BoundIr;
import kart.plan.PlanBuilder;
import kart.plan.PlanEnvelope;
// IndexId in same package
import kart.validation.IncrementalValidator;
import kart.validation.PlanValidator;
import kart.validation.SafePlanHandle;
import kart.validation.ValidationReport;
import kart.util.StatusLog;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Beam search over legal plan actions (IMPLEMENTATION_PLAN §11.5).
 */
public final class BeamSearch {

  private final LayoutContext layout;
  private final FastCost fastCost;
  private final LegalActionGenerator generator;
  private final IncrementalValidator incremental = new IncrementalValidator();
  private final SearchOptions options;
  private final boolean advancedIndexesEnabled;

  public BeamSearch(LayoutContext layout, FastCost fastCost) {
    this(layout, fastCost, SearchOptions.defaults());
  }

  public BeamSearch(LayoutContext layout, FastCost fastCost, SearchOptions options) {
    this(layout, fastCost, options, LegalActionGenerator.advancedIndexesEnabled());
  }

  public BeamSearch(LayoutContext layout, FastCost fastCost, SearchOptions options,
                    boolean advancedIndexesEnabled) {
    this.layout = layout;
    this.fastCost = fastCost != null ? fastCost : new FastCost(null);
    this.options = options != null ? options : SearchOptions.defaults();
    this.advancedIndexesEnabled = advancedIndexesEnabled;
    boolean boundedAdvancedPath = advancedIndexesEnabled && advancedInjectEnabled();
    this.generator = new LegalActionGenerator(this.options.allowIntersect,
        advancedIndexesEnabled && !boundedAdvancedPath);
  }

  public SearchResult search(BoundIr ir, ProposalPolicy policy, SearchBudget budget) {
    long searchStartMs = System.currentTimeMillis();
    if (budget == null) {
      budget = SearchBudget.defaults();
    }
    if (policy == null) {
      policy = new RulePolicy();
    }

    SearchResult result = new SearchResult();
    result.budget = budget;
    SearchLog log = result.log;
    Set<String> seenStates = new HashSet<String>();
    Set<String> candidateSigs = new HashSet<String>();
    Set<String> familyEmitted = new HashSet<String>();
    Map<String, PlanEnvelope> candidatesById = new HashMap<String, PlanEnvelope>();

    SearchState empty = SearchState.initial(ir);
    addCandidate(empty.completePlan(), candidatesById, candidateSigs, result);

    List<SearchState> frontier = new ArrayList<SearchState>();
    // ADV: seed classic only (TIME/Z/HASH). Advanced SafePlans are injected after
    // search without per-action FastCost (see injectAdvancedCandidates).
    List<String> seedIdx = LegalActionGenerator.availableIndexes(ir, advancedIndexesEnabled);
    if (advancedIndexesEnabled && advancedInjectEnabled()) {
      List<String> classic = new ArrayList<String>();
      for (String idx : seedIdx) {
        if (IndexId.TIME.equals(idx) || IndexId.ZORDER.equals(idx) || IndexId.HASH.equals(idx)) {
          classic.add(idx);
        }
      }
      if (!classic.isEmpty()) {
        seedIdx = classic;
      }
    }
    for (String idx : seedIdx) {
      SearchState s = empty.apply(LegalActionGenerator.start(idx));
      IncrementalValidator.Result inc = incremental.check(s);
      if (!inc.ok) {
        continue;
      }
      CostCard card = fastCost.estimate(s.completePlan(), ir);
      s.setFastCost(card);
      if (seenStates.add(s.signature())) {
        frontier.add(s);
      }
    }
    frontier = trimBeam(frontier, budget.beamWidth);

    int stepNo = 0;
    RulePolicy ruleForMark = extractRule(policy);

    while (!frontier.isEmpty() && !budget.exhausted()) {
      budget.checkTime();
      if (budget.exhausted()) {
        break;
      }
      if (budget.candidatesFull(candidatesById.size())) {
        break;
      }

      Map<String, List<LegalAction>> legalById = new HashMap<String, List<LegalAction>>();
      Map<String, CostCard> cardsById = new HashMap<String, CostCard>();
      Map<String, SearchState> stateById = new HashMap<String, SearchState>();
      double roundBest = Double.POSITIVE_INFINITY;

      for (SearchState state : frontier) {
        List<LegalAction> legal = generator.generate(state);
        CostCard card = state.fastCost();
        if (card == null) {
          card = fastCost.estimate(state.completePlan(), ir);
          state.setFastCost(card);
        }
        roundBest = Math.min(roundBest, card.estimated_ms);
        legalById.put(state.stateId(), legal);
        cardsById.put(state.stateId(), card);
        stateById.put(state.stateId(), state);
        log.begin(stepNo++, state, legal, card);
      }

      List<ActionProposal> proposals = policy.propose(frontier, legalById, cardsById, budget);
      List<SearchState> next = new ArrayList<SearchState>();

      for (ActionProposal prop : proposals) {
        if (budget.exhausted() || budget.candidatesFull(candidatesById.size())) {
          break;
        }
        SearchState state = stateById.get(prop.stateId);
        if (state == null) {
          continue;
        }
        SearchLog.Step step = findLastStep(log, state.signature());
        if (step != null) {
          recordProposal(step, prop, policy);
        }

        if (prop.action == null) {
          if (step != null) {
            step.event = "skip";
            step.reason = "null_action";
          }
          continue;
        }

        if (prop.action.isFinish()) {
          PlanEnvelope plan = state.completePlan();
          if (step != null) {
            step.completedPlanId = plan.plan_id;
          }
          addCandidate(plan, candidatesById, candidateSigs, result);
          if (ruleForMark != null) {
            ruleForMark.markFinished(state.signature());
          }
          if (!seenStates.contains(state.signature() + "#grown")) {
            LegalAction inter = firstIntersect(legalById.get(state.stateId()));
            if (inter != null) {
              SearchState grown = state.apply(inter);
              IncrementalValidator.Result inc = incremental.check(grown);
              if (inc.ok) {
                CostCard gc = fastCost.estimate(grown.completePlan(), ir);
                grown.setFastCost(gc);
                if (seenStates.add(grown.signature())) {
                  next.add(grown);
                }
              }
            }
            seenStates.add(state.signature() + "#grown");
          }
        } else {
          // §8.2: PARTITION/REPLACE may precede FINISH — still emit the access-complete
          // family once so PlanSelector can compare the base plan.
          if (RulePolicy.accessComplete(state)
              && (prop.action.kind == ActionKind.PARTITION_UNION
                  || prop.action.kind == ActionKind.REPLACE)
              && familyEmitted.add(state.signature())) {
            addCandidate(state.completePlan(), candidatesById, candidateSigs, result);
            if (ruleForMark != null) {
              ruleForMark.markFinished(state.signature());
            }
          }
          SearchState ns;
          try {
            ns = state.apply(prop.action);
          } catch (RuntimeException e) {
            if (step != null) {
              step.event = "illegal_action";
              step.reason = e.getMessage();
            }
            continue;
          }
          IncrementalValidator.Result inc = incremental.check(ns);
          if (!inc.ok) {
            if (step != null) {
              step.event = "infeasible";
              step.reason = inc.reason;
            }
            continue;
          }
          CostCard nc = fastCost.estimate(ns.completePlan(), ir);
          ns.setFastCost(nc);
          if (seenStates.add(ns.signature())) {
            next.add(ns);
          }
        }
      }

      if (options.useFastCost && roundBest < Double.POSITIVE_INFINITY) {
        budget.observeBest(roundBest);
      }

      if (next.isEmpty()) {
        budget.stop("NO_ACTION");
        break;
      }
      frontier = trimBeam(next, budget.beamWidth);
    }

    if (!budget.exhausted() && frontier.isEmpty()) {
      budget.stop("NO_ACTION");
    }

    // Classic TZ/H must remain in SafeSet under ADV eval-cap (Final margin vs non-FULL).
    if (advancedIndexesEnabled) {
      injectClassicAnchors(ir, candidatesById, candidateSigs, result);
    }
    // Default on under ADV: inject ≤2 ADV templates. Set KART_ADV_INJECT=0 to skip.
    if (advancedIndexesEnabled && advancedInjectEnabled()) {
      injectAdvancedCandidates(ir, candidatesById, candidateSigs, result);
    }

    result.candidates = new ArrayList<PlanEnvelope>(candidatesById.values());
    if (advancedIndexesEnabled && advancedValidationShortlistEnabled()) {
      int before = result.candidates.size();
      result.candidates = advancedValidationShortlist(ir, result.candidates, fastCost,
          advancedValidationCap());
      if (result.candidates.size() < before) {
        StatusLog.info("ADV", "validation shortlist kept=" + result.candidates.size()
            + " dropped=" + (before - result.candidates.size()));
      }
    }
    long validationStartMs = System.currentTimeMillis();
    result.tSearchMs = validationStartMs - searchStartMs;
    validateAll(ir, result);
    result.tValidationMs = System.currentTimeMillis() - validationStartMs;
    return result;
  }

  /**
   * Keep the CBO incumbent and safety fallback while validating only the best
   * posting-cost ADV candidates. This bounds the otherwise unbudgeted validation
   * tail; the ordinary CBO path is unchanged.
   */
  static List<PlanEnvelope> advancedValidationShortlist(BoundIr ir,
                                                         List<PlanEnvelope> candidates,
                                                         FastCost fastCost,
                                                         int requestedCap) {
    if (candidates == null || candidates.isEmpty() || fastCost == null) {
      return candidates == null ? Collections.<PlanEnvelope>emptyList()
          : new ArrayList<PlanEnvelope>(candidates);
    }
    int cap = Math.max(3, Math.min(8, requestedCap));
    List<PlanSelector.Scored> rough = new ArrayList<PlanSelector.Scored>();
    Map<String, PlanEnvelope> byPlanId = new HashMap<String, PlanEnvelope>();
    for (PlanEnvelope env : candidates) {
      if (env == null) {
        continue;
      }
      String id = env.plan_id == null ? "" : env.plan_id;
      if (!id.isEmpty() && !byPlanId.containsKey(id)) {
        byPlanId.put(id, env);
      }
      CostCard card;
      try {
        card = fastCost.estimate(env, ir);
      } catch (RuntimeException e) {
        card = new CostCard();
        card.plan_id = id;
        card.estimated_ms = Double.POSITIVE_INFINITY;
      }
      if (card.plan_id == null) {
        card.plan_id = id;
      }
      rough.add(new PlanSelector.Scored(null, card));
    }

    final AdvQueryRouter.Route route = AdvQueryRouter.route(ir);
    List<PlanSelector.Scored> adv = new ArrayList<PlanSelector.Scored>();
    PlanSelector.Scored classic = null;
    boolean preferZ = BoundIr.hasSpatial(ir)
        && PlanBuilder.vehicleEqPredicateIndex(ir) < 0;
    for (PlanSelector.Scored s : rough) {
      String id = AdvFinalCalibrator.planId(s);
      if (isAdvancedPlanId(id)) {
        adv.add(s);
      } else if (!"P_FULL".equals(id)) {
        if (preferZ && !AdvFinalCalibrator.classicUsesSpatial(id)) {
          continue;
        }
        if (classic == null || s.card.estimated_ms < classic.card.estimated_ms) {
          classic = s;
        }
      }
    }
    Collections.sort(adv, new Comparator<PlanSelector.Scored>() {
      @Override
      public int compare(PlanSelector.Scored a, PlanSelector.Scored b) {
        int c = Double.compare(AdvFinalCalibrator.predMs(a), AdvFinalCalibrator.predMs(b));
        if (c != 0) {
          return c;
        }
        return Integer.compare(
            AdvQueryRouter.preferenceRank(AdvFinalCalibrator.planId(a), route),
            AdvQueryRouter.preferenceRank(AdvFinalCalibrator.planId(b), route));
      }
    });

    Map<String, PlanEnvelope> selected = new LinkedHashMap<String, PlanEnvelope>();
    addShortlistCandidate(selected, byPlanId.get("P_FULL"), cap);
    if (classic != null) {
      addShortlistCandidate(selected, byPlanId.get(AdvFinalCalibrator.planId(classic)), cap);
    }
    // Keep standalone Z even when FastCost prefers TIME-only anchors.
    if (preferZ) {
      addShortlistCandidate(selected, byPlanId.get("P_Z"), cap);
    }
    if (ir != null && PlanBuilder.vehicleEqPredicateIndex(ir) >= 0) {
      PlanEnvelope hash = byPlanId.get("P_H");
      if (hash == null) {
        try {
          hash = PlanBuilder.buildForIndexes(ir, Collections.singleton(IndexId.HASH), null, null);
        } catch (RuntimeException ignored) {
          // A malformed or unsupported equality predicate must not break planning.
        }
      }
      addShortlistCandidate(selected, hash, cap);
    }
    for (int i = 0; i < adv.size() && i < 2; i++) {
      addShortlistCandidate(selected, byPlanId.get(AdvFinalCalibrator.planId(adv.get(i))), cap);
    }

    // Fill any remaining slots with low FastCost candidates, without dropping the
    // explicit CBO/vehicle-hash/ADV representatives above.
    Collections.sort(rough, new Comparator<PlanSelector.Scored>() {
      @Override
      public int compare(PlanSelector.Scored a, PlanSelector.Scored b) {
        return Double.compare(a.card.estimated_ms, b.card.estimated_ms);
      }
    });
    for (PlanSelector.Scored s : rough) {
      if (selected.size() >= cap) {
        break;
      }
      addShortlistCandidate(selected, byPlanId.get(AdvFinalCalibrator.planId(s)), cap);
    }
    if (selected.isEmpty()) {
      return new ArrayList<PlanEnvelope>(candidates);
    }
    return new ArrayList<PlanEnvelope>(selected.values());
  }

  private static void addShortlistCandidate(Map<String, PlanEnvelope> selected,
                                             PlanEnvelope env, int cap) {
    if (env == null || selected.size() >= cap) {
      return;
    }
    String signature = env.signature();
    String key = signature == null ? String.valueOf(env.plan_id) : signature;
    if (!selected.containsKey(key)) {
      selected.put(key, env);
    }
  }

  private static boolean isAdvancedPlanId(String planId) {
    return AdvFinalCalibrator.isAdvId(planId);
  }

  private static boolean advancedValidationShortlistEnabled() {
    String value = System.getenv("KART_ADV_VALIDATION_SHORTLIST");
    return value == null || value.trim().isEmpty() || !isFalse(value);
  }

  private static int advancedValidationCap() {
    String value = System.getenv("KART_ADV_VALIDATION_CAP");
    if (value != null && !value.trim().isEmpty()) {
      try {
        return Math.max(3, Math.min(8, Integer.parseInt(value.trim())));
      } catch (NumberFormatException ignored) {
        // Use the bounded default.
      }
    }
    // 6: FULL + Z-bearing classic + P_Z + up to 2 ADV (XZ/TS or XZTXZ/XZT).
    return 6;
  }

  private static boolean isFalse(String value) {
    String v = value == null ? "" : value.trim().toLowerCase();
    return "0".equals(v) || "false".equals(v) || "off".equals(v) || "no".equals(v);
  }

  static boolean advancedInjectEnabled() {
    String v = System.getenv("KART_ADV_INJECT");
    if (v == null || v.isEmpty()) {
      return true;
    }
    v = v.trim().toLowerCase();
    if (v.equals("0") || v.equals("false") || v.equals("off") || v.equals("no")) {
      return false;
    }
    return v.equals("1") || v.equals("true") || v.equals("on") || v.equals("yes");
  }

  /** Ensure Z / TIME+Z / HASH exist as classic anchors for Final admit. */
  private void injectClassicAnchors(BoundIr ir,
                                    Map<String, PlanEnvelope> candidatesById,
                                    Set<String> candidateSigs,
                                    SearchResult result) {
    List<List<String>> specs = new ArrayList<List<String>>();
    // Standalone Z: needed so spatial Final cannot fall back to cheap P_T-only.
    if (BoundIr.hasSpatial(ir)) {
      specs.add(Collections.singletonList(IndexId.ZORDER));
    }
    if (BoundIr.hasTemporal(ir) && BoundIr.hasSpatial(ir)) {
      specs.add(Arrays.asList(IndexId.TIME, IndexId.ZORDER));
    }
    if (PlanBuilder.vehicleEqPredicateIndex(ir) >= 0) {
      specs.add(Collections.singletonList(IndexId.HASH));
    }
    for (List<String> idxs : specs) {
      try {
        PlanEnvelope env = PlanBuilder.buildForIndexes(ir, idxs, "HASH_SET", null);
        if (env != null) {
          addCandidate(env, candidatesById, candidateSigs, result);
        }
      } catch (RuntimeException ignored) {
        // skip
      }
    }
  }

  /** Post-search ADV templates; router picks ≤2 specs so t_plan stays low. */
  private void injectAdvancedCandidates(BoundIr ir,
                                        Map<String, PlanEnvelope> candidatesById,
                                        Set<String> candidateSigs,
                                        SearchResult result) {
    List<List<String>> specs = kart.cost.AdvQueryRouter.injectSpecs(ir);
    for (List<String> idxs : specs) {
      try {
        PlanEnvelope env = PlanBuilder.buildForIndexes(ir, idxs, "HASH_SET", null);
        if (env != null) {
          addCandidate(env, candidatesById, candidateSigs, result);
        }
      } catch (RuntimeException ignored) {
        // skip illegal ADV combo for this IR
      }
    }
  }

  private static SearchLog.Step findLastStep(SearchLog log, String signature) {
    SearchLog.Step last = null;
    for (SearchLog.Step s : log.steps()) {
      if (signature.equals(s.stateSignature)) {
        last = s;
      }
    }
    return last;
  }

  private void validateAll(BoundIr ir, SearchResult result) {
    QueryCompiler compiler = new QueryCompiler(layout);
    PlanValidator validator = new PlanValidator(layout, options.skipCoverageCheck);
    for (PlanEnvelope env : result.candidates) {
      PhysicalPlan phys;
      try {
        phys = compiler.compile(env, ir);
      } catch (RuntimeException e) {
        ValidationReport report = new ValidationReport();
        report.fail("Compile", e.getMessage());
        result.rejections.add(new SearchResult.Rejection(env.plan_id, env.signature(), report));
        continue;
      }
      ValidationReport report = new ValidationReport();
      Optional<SafePlanHandle> handle = validator.validate(env, ir, phys, report);
      if (handle.isPresent()) {
        result.safePlans.add(handle.get());
      } else {
        result.rejections.add(new SearchResult.Rejection(env.plan_id, env.signature(), report));
      }
    }
  }

  public void validateInjected(BoundIr ir, PlanEnvelope env, SearchResult result) {
    QueryCompiler compiler = new QueryCompiler(layout);
    PlanValidator validator = new PlanValidator(layout, options.skipCoverageCheck);
    PhysicalPlan phys;
    try {
      phys = compiler.compile(env, ir);
    } catch (RuntimeException e) {
      ValidationReport report = new ValidationReport();
      report.fail("Compile", e.getMessage() != null ? e.getMessage() : "compile failed");
      result.rejections.add(new SearchResult.Rejection(env.plan_id, env.signature(), report));
      return;
    }
    ValidationReport report = new ValidationReport();
    Optional<SafePlanHandle> handle = validator.validate(env, ir, phys, report);
    if (handle.isPresent()) {
      result.safePlans.add(handle.get());
    } else {
      result.rejections.add(new SearchResult.Rejection(env.plan_id, env.signature(), report));
    }
  }

  private static void addCandidate(PlanEnvelope plan, Map<String, PlanEnvelope> byId,
                                   Set<String> sigs, SearchResult result) {
    if (plan == null) {
      return;
    }
    String sig = plan.signature();
    if (!sigs.add(sig)) {
      return;
    }
    String id = plan.plan_id != null ? plan.plan_id : sig;
    byId.put(id, plan);
  }

  private List<SearchState> trimBeam(List<SearchState> states, int beamWidth) {
    if (states.size() <= beamWidth) {
      return states;
    }
    if (!options.useFastCost) {
      return new ArrayList<SearchState>(states.subList(0, beamWidth));
    }
    List<SearchState> copy = new ArrayList<SearchState>(states);
    Collections.sort(copy, new Comparator<SearchState>() {
      @Override
      public int compare(SearchState a, SearchState b) {
        int c = Double.compare(a.estimatedMsOrMax(), b.estimatedMsOrMax());
        if (c != 0) {
          return c;
        }
        return a.signature().compareTo(b.signature());
      }
    });
    return new ArrayList<SearchState>(copy.subList(0, beamWidth));
  }

  private static LegalAction firstIntersect(List<LegalAction> legal) {
    if (legal == null) {
      return null;
    }
    for (LegalAction a : legal) {
      if (a.kind == ActionKind.INTERSECT) {
        return a;
      }
    }
    return null;
  }

  private static RulePolicy extractRule(ProposalPolicy policy) {
    if (policy instanceof RulePolicy) {
      return (RulePolicy) policy;
    }
    if (policy instanceof LlmProposalPolicy) {
      return ((LlmProposalPolicy) policy).rulePolicy();
    }
    return null;
  }

  private static void recordProposal(SearchLog.Step step, ActionProposal prop,
                                     ProposalPolicy policy) {
    if (prop == null) {
      step.legal = false;
      step.event = "skip";
      return;
    }
    step.selectedActionId = prop.action != null ? prop.action.actionId : prop.actionId;
    step.reason = prop.reasonCode;
    if (prop.illegal) {
      step.legal = false;
      step.event = "illegal_action";
    } else if (policy instanceof LlmProposalPolicy) {
      LlmProposalPolicy llm = (LlmProposalPolicy) policy;
      step.legal = !llm.lastIllegal;
      step.event = llm.lastEvent != null ? llm.lastEvent : (prop.fromLlm ? "ok" : "rule_fallback");
    } else if (policy instanceof BestFirstPolicy) {
      step.legal = true;
      step.event = "best_first";
    } else {
      step.legal = true;
      step.event = prop.fromLlm ? "ok" : "rule_fallback";
    }
  }
}
