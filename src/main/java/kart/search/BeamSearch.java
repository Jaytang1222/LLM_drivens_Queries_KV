package kart.search;

import kart.compile.LayoutContext;
import kart.compile.PhysicalPlan;
import kart.compile.QueryCompiler;
import kart.cost.CostCard;
import kart.cost.FastCost;
import kart.ir.BoundIr;
import kart.plan.PlanEnvelope;
import kart.validation.PlanValidator;
import kart.validation.SafePlanHandle;
import kart.validation.ValidationReport;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Beam search over legal plan actions with budget (T4.4) and validator wiring (T4.6).
 */
public final class BeamSearch {

  private final LayoutContext layout;
  private final FastCost fastCost;
  private final LegalActionGenerator generator = new LegalActionGenerator();

  public BeamSearch(LayoutContext layout, FastCost fastCost) {
    this.layout = layout;
    this.fastCost = fastCost != null ? fastCost : new FastCost(null);
  }

  public SearchResult search(BoundIr ir, ProposalPolicy policy, SearchBudget budget) {
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
    Map<String, PlanEnvelope> candidatesById = new HashMap<String, PlanEnvelope>();

    // Always keep P_FULL
    SearchState empty = SearchState.initial(ir);
    addCandidate(empty.completePlan(), candidatesById, candidateSigs, result);

    // Seed frontier with single-index START states (deterministic diversity)
    List<SearchState> frontier = new ArrayList<SearchState>();
    for (String idx : LegalActionGenerator.availableIndexes(ir)) {
      SearchState s = empty.apply(LegalActionGenerator.start(idx));
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

      List<SearchState> next = new ArrayList<SearchState>();
      double roundBest = Double.POSITIVE_INFINITY;

      for (SearchState state : frontier) {
        if (budget.exhausted() || budget.candidatesFull(candidatesById.size())) {
          break;
        }
        List<LegalAction> legal = generator.generate(state);
        CostCard card = state.fastCost();
        if (card == null) {
          card = fastCost.estimate(state.completePlan(), ir);
          state.setFastCost(card);
        }
        roundBest = Math.min(roundBest, card.estimated_ms);

        SearchLog.Step step = log.begin(stepNo++, state, legal, card);
        ActionSelection sel = policy.propose(state, legal, card, budget);
        recordSelection(step, sel, policy);

        if (sel.action == null) {
          step.event = "skip";
          step.reason = "null_action";
          continue;
        }

        if (sel.action.isFinish()) {
          PlanEnvelope plan = state.completePlan();
          step.completedPlanId = plan.plan_id;
          addCandidate(plan, candidatesById, candidateSigs, result);
          if (ruleForMark != null) {
            ruleForMark.markFinished(state.signature());
          }
          // Keep state for INTERSECT growth (singles → pairs → triples)
          if (!seenStates.contains(state.signature() + "#grown")) {
            LegalAction inter = firstIntersect(legal);
            if (inter != null) {
              SearchState grown = state.apply(inter);
              CostCard gc = fastCost.estimate(grown.completePlan(), ir);
              grown.setFastCost(gc);
              if (seenStates.add(grown.signature())) {
                next.add(grown);
              }
            }
            seenStates.add(state.signature() + "#grown");
          }
        } else {
          SearchState ns = state.apply(sel.action);
          CostCard nc = fastCost.estimate(ns.completePlan(), ir);
          ns.setFastCost(nc);
          if (seenStates.add(ns.signature())) {
            next.add(ns);
          }
        }
      }

      if (roundBest < Double.POSITIVE_INFINITY) {
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

    result.candidates = new ArrayList<PlanEnvelope>(candidatesById.values());
    validateAll(ir, result);
    return result;
  }

  private void validateAll(BoundIr ir, SearchResult result) {
    QueryCompiler compiler = new QueryCompiler(layout);
    PlanValidator validator = new PlanValidator(layout);
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

  /**
   * Validate an extra injected plan (e.g. incomplete suffix) into the report (T4.6).
   */
  public void validateInjected(BoundIr ir, PlanEnvelope env, SearchResult result) {
    QueryCompiler compiler = new QueryCompiler(layout);
    PlanValidator validator = new PlanValidator(layout);
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

  private static List<SearchState> trimBeam(List<SearchState> states, int beamWidth) {
    if (states.size() <= beamWidth) {
      return states;
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

  private static void recordSelection(SearchLog.Step step, ActionSelection sel,
                                      ProposalPolicy policy) {
    if (sel == null) {
      step.legal = false;
      step.event = "skip";
      return;
    }
    step.selectedActionId = sel.action != null ? sel.action.actionId : sel.requestedActionId;
    step.reason = sel.reason;
    if (sel.illegalFallback) {
      step.legal = false;
      step.event = "illegal_action";
    } else if (policy instanceof LlmProposalPolicy) {
      LlmProposalPolicy llm = (LlmProposalPolicy) policy;
      step.legal = !llm.lastIllegal;
      step.event = llm.lastEvent != null ? llm.lastEvent : (sel.fromLlm ? "ok" : "rule_fallback");
      if (sel.requestedActionId != null) {
        step.selectedActionId = sel.action != null ? sel.action.actionId : sel.requestedActionId;
      }
    } else {
      step.legal = true;
      step.event = sel.fromLlm ? "ok" : "rule_fallback";
    }
  }
}
