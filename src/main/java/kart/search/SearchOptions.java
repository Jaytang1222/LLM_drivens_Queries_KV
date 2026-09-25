package kart.search;

import kart.config.AppConfig;

/**
 * Planner search/select knobs. Defaults match the production / comparative path.
 * Ablation overlays set these via {@link AppConfig.PlannerConfig} only.
 */
public final class SearchOptions {

  public final boolean useFastCost;
  public final boolean allowIntersect;
  public final boolean skipCoverageCheck;
  public final String finalSelect;

  public SearchOptions(boolean useFastCost, boolean allowIntersect,
                       boolean skipCoverageCheck, String finalSelect) {
    this.useFastCost = useFastCost;
    this.allowIntersect = allowIntersect;
    this.skipCoverageCheck = skipCoverageCheck;
    this.finalSelect = finalSelect == null || finalSelect.trim().isEmpty()
        ? AppConfig.PlannerConfig.FINAL_SELECT_ESTIMATED_MS
        : finalSelect.trim();
  }

  public static SearchOptions defaults() {
    return from(null);
  }

  public static SearchOptions from(AppConfig.PlannerConfig planner) {
    if (planner == null) {
      return new SearchOptions(true, true, false,
          AppConfig.PlannerConfig.FINAL_SELECT_ESTIMATED_MS);
    }
    return new SearchOptions(
        planner.use_fast_cost,
        planner.allow_intersect,
        planner.skip_coverage_check,
        planner.final_select);
  }

  public boolean selectByPlanId() {
    return AppConfig.PlannerConfig.FINAL_SELECT_PLAN_ID.equalsIgnoreCase(finalSelect);
  }
}
