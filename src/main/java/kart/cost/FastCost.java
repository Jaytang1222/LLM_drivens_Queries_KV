package kart.cost;

import kart.catalog.StatsSnapshot;
import kart.compile.LayoutContext;
import kart.config.AppConfig;
import kart.ir.BoundIr;
import kart.plan.PlanEnvelope;

/**
 * Fast-cost estimator used during plan search (T4.5 / T5.2).
 * Shares {@link CostModel} formula with Final; may approximate before compile.
 */
public final class FastCost {

  private final LayoutContext layout;
  private final StatsSnapshot stats;
  private final CostModel model;
  private final CostFeaturesExtractor extractor;

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
    CostFeatures features = extractor.extractFast(plan, ir);
    return model.estimateFast(features);
  }
}
