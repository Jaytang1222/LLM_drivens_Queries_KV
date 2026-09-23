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
    this(null, stats, null);
  }

  public FastCost(LayoutContext layout, StatsSnapshot stats) {
    this(layout, stats, null);
  }

  public FastCost(LayoutContext layout, StatsSnapshot stats, AppConfig.CostCoeffs coeffs) {
    this.layout = layout;
    this.stats = stats;
    this.model = new CostModel(coeffs != null ? coeffs : new AppConfig.CostCoeffs());
    this.extractor = new CostFeaturesExtractor(layout, stats);
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
