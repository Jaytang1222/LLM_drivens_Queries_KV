package kart.bench;

import kart.cost.CostCard;
import kart.ir.BoundIr;
import kart.plan.PlanBuilder;
import kart.plan.PlanEnvelope;
import kart.query.QueryEngine;
import kart.validation.SafePlanHandle;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Shared SafePlan pool for E2 selection experiments (§6.10.3).
 *
 * Enumeration uses the same {@link PlanBuilder#buildCandidates(BoundIr)} family and
 * the same validator/cost path as {@link QueryEngine#runFixed} — not beam search.
 * Selectors must only pick among this pool; report {@code pool_regret_ms} separately
 * from native-search arm-local regret.
 */
public final class SharedCandidatePool {

  /** Catalog / provenance stamp for shared-pool enumeration. */
  public static final String ENUM_VERSION = "shared_pool_enum/v1";

  private SharedCandidatePool() {}

  /**
   * Validate every PlanBuilder candidate once. Rejected templates stay out of the
   * pool but are recorded on {@code rr.rejections}.
   */
  public static QueryEngine.RunResult enumerate(QueryEngine engine, BoundIr ir)
      throws IOException {
    QueryEngine.RunResult pool = new QueryEngine.RunResult();
    pool.ir = ir;
    pool.planOnly = true;
    if (engine == null || ir == null) {
      return pool;
    }
    double bestMs = Double.POSITIVE_INFINITY;
    for (PlanEnvelope env : PlanBuilder.buildCandidates(ir)) {
      if (env == null) {
        continue;
      }
      QueryEngine.RunResult one = engine.runFixed(ir, null, true, env);
      if (one == null) {
        continue;
      }
      pool.candidates.add(env);
      if (one.rejections != null) {
        pool.rejections.addAll(one.rejections);
      }
      if (one.rejectionReports != null) {
        pool.rejectionReports.addAll(one.rejectionReports);
      }
      if (one.selected != null) {
        pool.safe.add(one.selected);
        if (one.selectedCost != null) {
          pool.costCards.add(one.selectedCost);
          if (one.selectedCost.estimated_ms < bestMs) {
            bestMs = one.selectedCost.estimated_ms;
          }
        }
      }
    }
    if (!Double.isInfinite(bestMs)) {
      pool.best_safe_estimated_ms = Double.valueOf(bestMs);
    }
    // Default selection = cost argmin (CBO-on-pool).
    selectArgminCost(pool);
    return pool;
  }

  /** Pick lowest {@code estimated_ms}; ties broken by plan_id ascending. */
  public static boolean selectArgminCost(QueryEngine.RunResult pool) {
    if (pool == null || pool.costCards == null || pool.costCards.isEmpty()) {
      return false;
    }
    CostCard best = null;
    for (CostCard c : pool.costCards) {
      if (c == null || c.plan_id == null) {
        continue;
      }
      if (best == null
          || c.estimated_ms < best.estimated_ms
          || (c.estimated_ms == best.estimated_ms
          && c.plan_id.compareTo(best.plan_id) < 0)) {
        best = c;
      }
    }
    return best != null && QueryEngine.reselectByPlanId(pool, best.plan_id);
  }

  /**
   * Bao-style surrogate: {@code reward = estimated_ms / max(0.1, family_prior)}.
   * Missing costs → large penalty.
   */
  public static boolean selectBaoSurrogate(QueryEngine.RunResult pool,
                                           Map<String, Double> familyWeight) {
    if (pool == null || pool.safe == null || pool.safe.isEmpty()) {
      return false;
    }
    List<String> ids = planIds(pool);
    if (ids.isEmpty()) {
      return false;
    }
    Map<String, Double> costByPid = costByPlanId(pool);
    String chosen = null;
    double bestReward = Double.POSITIVE_INFINITY;
    for (String pid : ids) {
      double cost = costByPid.containsKey(pid)
          ? costByPid.get(pid).doubleValue() : 1.0e12;
      double prior = 1.0;
      if (familyWeight != null && familyWeight.containsKey(familyOf(pid))) {
        prior = Math.max(0.1, familyWeight.get(familyOf(pid)).doubleValue());
      }
      double reward = cost / prior;
      if (reward < bestReward
          || (reward == bestReward && (chosen == null || pid.compareTo(chosen) < 0))) {
        bestReward = reward;
        chosen = pid;
      }
    }
    return chosen != null && QueryEngine.reselectByPlanId(pool, chosen);
  }

  public static List<String> planIds(QueryEngine.RunResult pool) {
    List<String> ids = new ArrayList<String>();
    if (pool == null || pool.safe == null) {
      return ids;
    }
    for (SafePlanHandle h : pool.safe) {
      if (h != null && h.plan() != null && h.plan().plan_id != null
          && !ids.contains(h.plan().plan_id)) {
        ids.add(h.plan().plan_id);
      }
    }
    Collections.sort(ids);
    return ids;
  }

  public static Map<String, Double> costByPlanId(QueryEngine.RunResult pool) {
    Map<String, Double> out = new LinkedHashMap<String, Double>();
    if (pool == null || pool.costCards == null) {
      return out;
    }
    for (CostCard c : pool.costCards) {
      if (c != null && c.plan_id != null) {
        out.put(c.plan_id, Double.valueOf(c.estimated_ms));
      }
    }
    return out;
  }

  /** Regret vs shared-pool best cost (not arm-local search regret). */
  public static Double poolRegretMs(QueryEngine.RunResult pool) {
    if (pool == null || pool.selectedCost == null || pool.best_safe_estimated_ms == null) {
      return null;
    }
    return Double.valueOf(
        pool.selectedCost.estimated_ms - pool.best_safe_estimated_ms.doubleValue());
  }

  public static Map<String, Object> catalogRow(QueryEngine.RunResult pool) {
    Map<String, Object> row = new LinkedHashMap<String, Object>();
    List<Map<String, Object>> cards = new ArrayList<Map<String, Object>>();
    if (pool != null && pool.costCards != null) {
      List<CostCard> sorted = new ArrayList<CostCard>(pool.costCards);
      Collections.sort(sorted, new Comparator<CostCard>() {
        @Override
        public int compare(CostCard a, CostCard b) {
          String pa = a == null || a.plan_id == null ? "" : a.plan_id;
          String pb = b == null || b.plan_id == null ? "" : b.plan_id;
          return pa.compareTo(pb);
        }
      });
      for (CostCard c : sorted) {
        if (c == null) {
          continue;
        }
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("plan_id", c.plan_id);
        m.put("family", familyOf(c.plan_id));
        m.put("estimated_ms", Double.valueOf(c.estimated_ms));
        m.put("uncertainty", c.uncertainty == null ? null : c.uncertainty.label);
        cards.add(m);
      }
    }
    row.put("enumerator", "PlanBuilder.buildCandidates+runFixed");
    row.put("enum_version", ENUM_VERSION);
    row.put("n_candidates_built", Integer.valueOf(
        pool == null || pool.candidates == null ? 0 : pool.candidates.size()));
    row.put("n_safe", Integer.valueOf(pool == null || pool.safe == null ? 0 : pool.safe.size()));
    row.put("n_rejected", Integer.valueOf(
        pool == null || pool.rejections == null ? 0 : pool.rejections.size()));
    row.put("cards", cards);
    return row;
  }

  static String familyOf(String planId) {
    if (planId == null) {
      return "?";
    }
    String p = planId.toUpperCase(Locale.ROOT);
    if (p.startsWith("P_TZH") || p.contains("TZH")) {
      return "TZH";
    }
    if (p.startsWith("P_TZ") || p.contains("_TZ")) {
      return "TZ";
    }
    if (p.startsWith("P_TH") || p.contains("_TH")) {
      return "TH";
    }
    if (p.startsWith("P_ZH") || p.contains("_ZH")) {
      return "ZH";
    }
    if (p.startsWith("P_T") || p.equals("P_T") || p.contains("TIME")) {
      return "T";
    }
    if (p.startsWith("P_Z")) {
      return "Z";
    }
    if (p.startsWith("P_H")) {
      return "H";
    }
    if (p.contains("FULL")) {
      return "FULL";
    }
    return p;
  }
}
