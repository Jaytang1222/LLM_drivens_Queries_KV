package kart.search;

import kart.config.AppConfig;

/**
 * Planning-time budget (design.md §8.3).
 */
public final class SearchBudget {

  public final int maxLlmCalls;
  public final int maxCandidates;
  public final long maxPlanMs;
  public final int beamWidth;
  public final int stagnationSteps;

  private int llmCalls;
  private final long startMs;
  private int stepsWithoutImprove;
  private double bestEstimatedMs = Double.POSITIVE_INFINITY;
  private String stopReason;

  public SearchBudget(int maxLlmCalls, int maxCandidates, long maxPlanMs,
                      int beamWidth, int stagnationSteps) {
    this.maxLlmCalls = maxLlmCalls;
    this.maxCandidates = maxCandidates;
    this.maxPlanMs = maxPlanMs;
    this.beamWidth = beamWidth;
    this.stagnationSteps = stagnationSteps;
    this.startMs = System.currentTimeMillis();
  }

  public static SearchBudget from(AppConfig.PlannerConfig cfg) {
    if (cfg == null) {
      cfg = AppConfig.PlannerConfig.defaults();
    }
    return new SearchBudget(cfg.max_llm_calls, cfg.max_candidates, cfg.max_plan_ms,
        cfg.beam_width, cfg.stagnation_steps);
  }

  public static SearchBudget defaults() {
    return from(AppConfig.PlannerConfig.defaults());
  }

  public boolean exhausted() {
    return stopReason != null;
  }

  public String stopReason() {
    return stopReason;
  }

  public int llmCalls() {
    return llmCalls;
  }

  public boolean canCallLlm() {
    return llmCalls < maxLlmCalls && !exhausted();
  }

  public void recordLlmCall() {
    llmCalls++;
    if (llmCalls >= maxLlmCalls) {
      // Do not stop search — only LLM calls; RulePolicy continues.
    }
  }

  public boolean candidatesFull(int n) {
    if (n >= maxCandidates) {
      stop("CANDIDATE_BUDGET");
      return true;
    }
    return false;
  }

  public void checkTime() {
    if (System.currentTimeMillis() - startMs >= maxPlanMs) {
      stop("TIME_BUDGET");
    }
  }

  /**
   * Record best fast-cost seen this round. If no improvement for
   * {@code stagnationSteps} rounds, stop with STAGNATION.
   */
  public void observeBest(double estimatedMs) {
    if (estimatedMs < bestEstimatedMs - 1e-9) {
      bestEstimatedMs = estimatedMs;
      stepsWithoutImprove = 0;
    } else {
      stepsWithoutImprove++;
      if (stepsWithoutImprove >= stagnationSteps) {
        stop("STAGNATION");
      }
    }
  }

  public void stop(String reason) {
    if (stopReason == null) {
      stopReason = reason;
    }
  }

  public long elapsedMs() {
    return System.currentTimeMillis() - startMs;
  }
}
