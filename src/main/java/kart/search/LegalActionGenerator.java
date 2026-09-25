package kart.search;

import kart.ir.BoundIr;
import kart.plan.PlanBuilder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Generates legal actions from IR (IMPLEMENTATION_PLAN §11.3).
 */
public final class LegalActionGenerator {

  private final boolean allowIntersect;

  public LegalActionGenerator() {
    this(true);
  }

  public LegalActionGenerator(boolean allowIntersect) {
    this.allowIntersect = allowIntersect;
  }

  public List<LegalAction> generate(SearchState state) {
    BoundIr ir = state.ir();
    List<String> available = availableIndexes(ir);
    List<LegalAction> out = new ArrayList<LegalAction>();

    boolean empty = state.usedIndexes().isEmpty();
    for (String idx : available) {
      if (empty) {
        if (!state.uses(idx)) {
          out.add(start(idx));
        }
      } else {
        if (!state.uses(idx) && allowIntersect) {
          out.add(intersect(idx));
        }
        if (!(state.usedIndexes().size() == 1 && state.uses(idx))) {
          out.add(replace(idx));
        }
      }
    }

    if (state.usedIndexes().size() >= 2) {
      if (state.mergeImpl() == null || !SearchState.MERGE_HASH_SET.equals(state.mergeImpl())) {
        out.add(chooseMerge(SearchState.MERGE_HASH_SET));
      }
      if (state.mergeImpl() == null || !SearchState.MERGE_SORT_MERGE.equals(state.mergeImpl())) {
        out.add(chooseMerge(SearchState.MERGE_SORT_MERGE));
      }
    }

    // System-proved partitions only (LLM cannot invent geometry).
    if (ir.temporal != null && state.partitionKind() == null
        && (empty || state.uses(IndexId.TIME))) {
      out.add(partitionUnion(SearchState.PARTITION_TIME_BIPART));
    }
    if (ir.spatial != null && state.partitionKind() == null
        && (empty || state.uses(IndexId.ZORDER))) {
      out.add(partitionUnion(SearchState.PARTITION_Z_QUAD));
    }

    // FINISH only when multi-index plans have chosen a merge implementation.
    if (state.usedIndexes().size() < 2 || state.mergeImpl() != null) {
      out.add(finish());
    }
    Collections.sort(out, ACTION_ORDER);
    return out;
  }

  public static List<String> availableIndexes(BoundIr ir) {
    List<String> out = new ArrayList<String>();
    if (ir.temporal != null) {
      out.add(IndexId.TIME);
    }
    if (ir.spatial != null) {
      out.add(IndexId.ZORDER);
    }
    if (PlanBuilder.vehicleEqPredicateIndex(ir) >= 0) {
      out.add(IndexId.HASH);
    }
    return out;
  }

  public static LegalAction start(String indexId) {
    return new LegalAction("start_" + indexId, ActionKind.START, indexId,
        "START access with " + indexId);
  }

  public static LegalAction intersect(String indexId) {
    return new LegalAction("intersect_" + indexId, ActionKind.INTERSECT, indexId,
        "INTERSECT " + indexId);
  }

  public static LegalAction replace(String indexId) {
    return new LegalAction("replace_" + indexId, ActionKind.REPLACE, indexId,
        "REPLACE access with " + indexId);
  }

  public static LegalAction chooseMerge(String mergeImpl) {
    return new LegalAction("choose_merge_" + mergeImpl.toLowerCase(),
        ActionKind.CHOOSE_MERGE, null, mergeImpl, null,
        "CHOOSE_MERGE_IMPLEMENTATION " + mergeImpl);
  }

  public static LegalAction partitionUnion(String partitionKind) {
    return new LegalAction("partition_union_" + partitionKind.toLowerCase(),
        ActionKind.PARTITION_UNION, null, null, partitionKind,
        "PARTITION_UNION system-proved " + partitionKind);
  }

  public static LegalAction finish() {
    return new LegalAction("finish", ActionKind.FINISH, null,
        "FINISH and complete plan suffix");
  }

  public static LegalAction findById(List<LegalAction> legal, String actionId) {
    if (actionId == null) {
      return null;
    }
    for (LegalAction a : legal) {
      if (actionId.equals(a.actionId)) {
        return a;
      }
    }
    return null;
  }

  private static final Comparator<LegalAction> ACTION_ORDER = new Comparator<LegalAction>() {
    @Override
    public int compare(LegalAction a, LegalAction b) {
      int kc = Integer.compare(kindRank(a.kind), kindRank(b.kind));
      if (kc != 0) {
        return kc;
      }
      int ic = Integer.compare(IndexId.order(a.indexId), IndexId.order(b.indexId));
      if (ic != 0) {
        return ic;
      }
      return a.actionId.compareTo(b.actionId);
    }
  };

  private static int kindRank(ActionKind k) {
    switch (k) {
      case START:
        return 0;
      case CHOOSE_MERGE:
        return 1;
      case INTERSECT:
        return 2;
      case PARTITION_UNION:
        return 3;
      case REPLACE:
        return 4;
      case FINISH:
        return 5;
      default:
        return 9;
    }
  }
}
