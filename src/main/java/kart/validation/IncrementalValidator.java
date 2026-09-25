package kart.validation;

import kart.ir.BoundIr;
import kart.plan.PlanBuilder;
import kart.search.IndexId;
import kart.search.SearchState;

import java.util.ArrayList;
import java.util.List;

/**
 * Mid-search feasibility check (IMPLEMENTATION_PLAN §11.5 {@code incremental_check}).
 * Incomplete plans must not execute; only expandability / unmet obligations are checked here.
 */
public final class IncrementalValidator {

  public static final class Result {
    public final boolean ok;
    public final String reason;

    public Result(boolean ok, String reason) {
      this.ok = ok;
      this.reason = reason;
    }

    public static Result pass() {
      return new Result(true, null);
    }

    public static Result fail(String reason) {
      return new Result(false, reason);
    }
  }

  /**
   * Predicate-coverage obligations still open on this access subgraph
   * (not "unused index ids").
   */
  public static List<String> predicateObligations(SearchState state) {
    BoundIr ir = state.ir();
    List<String> out = new ArrayList<String>();
    if (ir.temporal != null && !state.uses(IndexId.TIME) && !state.hasFullCoveringPartition()) {
      // Covered only when TIME is in the access set, or a proved TIME partition is active,
      // or the plan will be P_FULL (empty access) — empty access clears index obligations
      // because ExactSTFilter + FULL_SCAN covers all predicates.
      if (!state.usedIndexes().isEmpty()) {
        out.add("cover:/temporal");
      }
    }
    if (ir.spatial != null && !state.uses(IndexId.ZORDER) && !state.hasSpatialPartition()) {
      if (!state.usedIndexes().isEmpty()) {
        out.add("cover:/spatial");
      }
    }
    int vIdx = PlanBuilder.vehicleEqPredicateIndex(ir);
    if (vIdx >= 0 && !state.uses(IndexId.HASH) && !state.usedIndexes().isEmpty()) {
      out.add("cover:/predicates/" + vIdx);
    }
    // Suffix obligations always remain until FINISH/complete_tail (informational).
    out.add("suffix:exact_st_filter");
    if (ir.result != null && "TOP_K".equals(ir.result.mode)) {
      out.add("suffix:topk_chain");
    }
    return out;
  }

  public Result check(SearchState state) {
    if (state == null || state.ir() == null) {
      return Result.fail("INFEASIBLE_EXTENSION:null_state");
    }
    BoundIr ir = state.ir();
    for (String idx : state.usedIndexes()) {
      List<String> avail = kart.search.LegalActionGenerator.availableIndexes(ir);
      if (!avail.contains(idx) && !IndexId.TIME.equals(idx) && !IndexId.ZORDER.equals(idx)
          && !IndexId.HASH.equals(idx)) {
        return Result.fail("INFEASIBLE_EXTENSION:unknown_index:" + idx);
      }
      if (!avail.contains(idx)) {
        return Result.fail("INFEASIBLE_EXTENSION:index_not_implied_by_ir:" + idx);
      }
    }
    if (state.mergeImpl() != null
        && !"HASH_SET".equals(state.mergeImpl())
        && !"SORT_MERGE".equals(state.mergeImpl())) {
      return Result.fail("INFEASIBLE_EXTENSION:bad_merge:" + state.mergeImpl());
    }
    if (state.partitionKind() != null
        && !SearchState.PARTITION_TIME_BIPART.equals(state.partitionKind())
        && !SearchState.PARTITION_Z_QUAD.equals(state.partitionKind())) {
      return Result.fail("INFEASIBLE_EXTENSION:unknown_partition:" + state.partitionKind());
    }
    if (SearchState.PARTITION_TIME_BIPART.equals(state.partitionKind())) {
      if (ir.temporal == null) {
        return Result.fail("INFEASIBLE_EXTENSION:time_partition_without_temporal");
      }
      if (!state.uses(IndexId.TIME)) {
        return Result.fail("INFEASIBLE_EXTENSION:time_partition_requires_time_access");
      }
    }
    if (SearchState.PARTITION_Z_QUAD.equals(state.partitionKind())) {
      if (ir.spatial == null) {
        return Result.fail("INFEASIBLE_EXTENSION:z_partition_without_spatial");
      }
      if (!state.uses(IndexId.ZORDER)) {
        return Result.fail("INFEASIBLE_EXTENSION:z_partition_requires_zorder_access");
      }
    }
    if (state.usedIndexes().size() >= 2 && state.mergeImpl() == null) {
      // Merge choice not yet made — still expandable via CHOOSE_MERGE; not infeasible.
    }
    return Result.pass();
  }
}
