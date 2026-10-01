package kart.bench;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Label helpers for the CBO opportunity census ({@code docs/cbo_opportunity_census_guide.md}).
 * Pure logic — no HBase / LLM.
 */
public final class OpportunityCensusLabels {

  /** Provisional hybrid extra-plan threshold from pilot (ms). Not a net-gain claim. */
  public static final long PROVISIONAL_HYBRID_EXTRA_PLAN_MS = 900L;
  /** Provisional pure-KART extra-plan threshold from pilot (ms). */
  public static final long PROVISIONAL_KART_EXTRA_PLAN_MS = 3700L;

  private OpportunityCensusLabels() {}

  public static long executionSavingMs(Long cboExecMs, Long candidateExecMs) {
    if (cboExecMs == null || candidateExecMs == null) {
      return Long.MIN_VALUE;
    }
    return cboExecMs.longValue() - candidateExecMs.longValue();
  }

  /**
   * Tags for one query given measured savings of validated non-full candidates.
   * {@code proposal_miss} is never emitted here (requires a later LLM arm run).
   */
  public static List<String> labelQuery(boolean hadOracleOrExecFailure,
                                        boolean censored,
                                        boolean insufficientRepeats,
                                        List<Long> candidateMissSavingsMs,
                                        List<Long> selectionMissSavingsMs,
                                        long hybridExtraPlanMs) {
    List<String> tags = new ArrayList<String>();
    if (hadOracleOrExecFailure) {
      tags.add("oracle_or_execution_failure");
    }
    if (censored) {
      tags.add("censored");
    }
    if (insufficientRepeats) {
      tags.add("insufficient_repeats");
    }
    long bestSearch = maxPositive(candidateMissSavingsMs);
    long bestSelect = maxPositive(selectionMissSavingsMs);
    long best = Math.max(bestSearch, bestSelect);
    if (best > 0L) {
      if (bestSearch >= bestSelect && bestSearch > 0L) {
        tags.add("search_miss_with_exec_gain");
      }
      if (bestSelect > 0L) {
        tags.add("selection_miss_with_exec_gain");
      }
      if (best < hybridExtraPlanMs) {
        tags.add("candidate_fast_but_overhead_dominates");
      }
    } else if (!hadOracleOrExecFailure && !censored) {
      tags.add("no_faster_safe_plan");
    }
    return tags;
  }

  private static long maxPositive(List<Long> xs) {
    if (xs == null || xs.isEmpty()) {
      return 0L;
    }
    long m = 0L;
    for (Long x : xs) {
      if (x != null && x.longValue() > m) {
        m = x.longValue();
      }
    }
    return m;
  }

  public static List<Long> emptyLongs() {
    return Collections.emptyList();
  }
}
