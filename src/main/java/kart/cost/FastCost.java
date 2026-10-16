package kart.cost;

import kart.catalog.StatsSnapshot;
import kart.compile.LayoutContext;
import kart.config.AppConfig;
import kart.ir.BoundIr;
import kart.plan.PlanEnvelope;

import java.util.HashMap;
import java.util.Map;

/**
 * Fast-cost estimator used during plan search (T4.5 / T5.2).
 * Shares {@link CostModel} formula with Final; may approximate before compile.
 * Memoizes by plan signature within one estimator instance (typically one query run).
 */
public final class FastCost {

  private final LayoutContext layout;
  private final StatsSnapshot stats;
  private final CostModel model;
  private final CostFeaturesExtractor extractor;
  private final Map<String, CostCard> memo = new HashMap<String, CostCard>();

  public FastCost(StatsSnapshot stats) {
    this(null, stats, null, null);
  }

  public FastCost(LayoutContext layout, StatsSnapshot stats) {
    this(layout, stats, null, null);
  }

  public FastCost(LayoutContext layout, StatsSnapshot stats, AppConfig.CostCoeffs coeffs) {
    this(layout, stats, coeffs, null);
  }

  public FastCost(LayoutContext layout, StatsSnapshot stats, AppConfig.CostCoeffs coeffs,
                  RegionMapping regionMapping) {
    this.layout = layout;
    this.stats = stats;
    AppConfig.CostCoeffs c = coeffs != null ? coeffs : new AppConfig.CostCoeffs();
    RegionMapping rm = regionMapping != null ? regionMapping : new RegionMapping.ShardFallback();
    this.model = new CostModel(c, rm);
    long soft = c.soft_memory_bytes > 0 ? c.soft_memory_bytes : (512L * 1024L * 1024L);
    this.extractor = new CostFeaturesExtractor(layout, stats, rm, soft);
  }

  public CostModel model() {
    return model;
  }

  public StatsSnapshot stats() {
    return stats;
  }

  /**
   * Estimate without BoundIR (degrades to structure-only / conservative).
   * Prefer {@link #estimate(PlanEnvelope, BoundIr)}.
   */
  public CostCard estimate(PlanEnvelope plan) {
    return estimate(plan, null);
  }

  public CostCard estimate(PlanEnvelope plan, BoundIr ir) {
    String key = memoKey(plan, ir);
    CostCard hit = memo.get(key);
    if (hit != null) {
      return hit;
    }
    CostFeatures features = extractor.extractFast(plan, ir);
    CostCard card = model.estimateFast(features);
    memo.put(key, card);
    return card;
  }

  private static String memoKey(PlanEnvelope plan, BoundIr ir) {
    String sig = plan != null ? plan.signature() : "";
    String qid = ir != null && ir.query_id != null ? ir.query_id : "";
    return qid + '\0' + sig;
  }
}
