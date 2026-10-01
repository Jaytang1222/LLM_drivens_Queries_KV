package kart.cost;

import kart.compile.LayoutContext;
import kart.compile.QueryCompiler;
import kart.ir.BoundIr;
import kart.plan.PlanEnvelope;

/** Exact compiled range count + frozen-statistics estimates; no query execution. */
public final class CompiledBenefitFeatures {
  public static CostCard estimate(FastCost fast, LayoutContext layout, PlanEnvelope plan, BoundIr ir) {
    CostCard card = fast.estimate(plan, ir);
    int ranges = new QueryCompiler(layout).compile(plan, ir).scanTasks.size();
    card.features.put("scan_ranges", Integer.valueOf(ranges));
    card.features.put("seek_ranges", Integer.valueOf(ranges));
    return card;
  }
}
