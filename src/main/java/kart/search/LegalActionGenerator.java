package kart.search;

import kart.ir.BoundIr;
import kart.plan.PlanBuilder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Generates legal START / INTERSECT / REPLACE / FINISH actions from IR (T4.1).
 * temporal → {@link IndexId#TIME}, spatial → {@link IndexId#ZORDER},
 * vehicle_id EQ → {@link IndexId#HASH}. FINISH is always legal.
 */
public final class LegalActionGenerator {

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
        if (!state.uses(idx)) {
          out.add(intersect(idx));
        }
        // REPLACE to any available index that is not the sole current set
        if (!(state.usedIndexes().size() == 1 && state.uses(idx))) {
          out.add(replace(idx));
        }
      }
    }
    out.add(finish());
    Collections.sort(out, ACTION_ORDER);
    return out;
  }

  /** Indexes implied by IR predicates, in RulePolicy order. */
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
      return Integer.compare(IndexId.order(a.indexId), IndexId.order(b.indexId));
    }
  };

  /** START → INTERSECT → REPLACE → FINISH (RulePolicy family order). */
  private static int kindRank(ActionKind k) {
    switch (k) {
      case START:
        return 0;
      case INTERSECT:
        return 1;
      case REPLACE:
        return 2;
      case FINISH:
        return 3;
      default:
        return 9;
    }
  }
}
