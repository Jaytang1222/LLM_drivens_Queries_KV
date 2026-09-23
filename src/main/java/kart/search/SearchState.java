package kart.search;

import kart.cost.CostCard;
import kart.ir.BoundIr;
import kart.plan.PlanBuilder;
import kart.plan.PlanEnvelope;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Partial access subgraph during beam search (design.md §8.1).
 */
public final class SearchState {

  private final BoundIr ir;
  /** Ordered unique index ids currently in the access subgraph. */
  private final Set<String> usedIndexes;
  private CostCard fastCost;

  private SearchState(BoundIr ir, Set<String> usedIndexes, CostCard fastCost) {
    this.ir = ir;
    this.usedIndexes = Collections.unmodifiableSet(new LinkedHashSet<String>(usedIndexes));
    this.fastCost = fastCost;
  }

  public static SearchState initial(BoundIr ir) {
    return new SearchState(ir, new LinkedHashSet<String>(), null);
  }

  public BoundIr ir() {
    return ir;
  }

  public Set<String> usedIndexes() {
    return usedIndexes;
  }

  public boolean uses(String indexId) {
    return usedIndexes.contains(indexId);
  }

  public CostCard fastCost() {
    return fastCost;
  }

  public void setFastCost(CostCard fastCost) {
    this.fastCost = fastCost;
  }

  /** Uncovered predicate obligations (index ids still available but unused). */
  public List<String> obligations() {
    List<String> all = LegalActionGenerator.availableIndexes(ir);
    List<String> out = new ArrayList<String>();
    for (String id : all) {
      if (!usedIndexes.contains(id)) {
        out.add(id);
      }
    }
    return out;
  }

  /** Canonical signature of the access set (order-independent). */
  public String signature() {
    if (usedIndexes.isEmpty()) {
      return "ACCESS:{}";
    }
    TreeSet<String> sorted = new TreeSet<String>(usedIndexes);
    StringBuilder sb = new StringBuilder("ACCESS:{");
    boolean first = true;
    for (String id : sorted) {
      if (!first) {
        sb.append(',');
      }
      first = false;
      sb.append(id);
    }
    sb.append('}');
    return sb.toString();
  }

  public SearchState apply(LegalAction action) {
    if (action == null) {
      throw new IllegalArgumentException("action required");
    }
    if (action.kind == ActionKind.FINISH) {
      return this;
    }
    Set<String> next = new LinkedHashSet<String>(usedIndexes);
    switch (action.kind) {
      case START:
        if (!usedIndexes.isEmpty()) {
          throw new IllegalStateException("START only from empty access: " + signature());
        }
        if (action.indexId == null) {
          throw new IllegalArgumentException("START requires index");
        }
        next.add(action.indexId);
        break;
      case INTERSECT:
        if (usedIndexes.isEmpty()) {
          throw new IllegalStateException("INTERSECT requires non-empty access");
        }
        if (action.indexId == null || usedIndexes.contains(action.indexId)) {
          throw new IllegalArgumentException("INTERSECT needs unused index");
        }
        next.add(action.indexId);
        break;
      case REPLACE:
        if (action.indexId == null) {
          throw new IllegalArgumentException("REPLACE requires index");
        }
        next.clear();
        next.add(action.indexId);
        break;
      default:
        throw new IllegalStateException("unexpected kind " + action.kind);
    }
    return new SearchState(ir, next, null);
  }

  /** Complete this access subgraph into a logical plan (constructor suffix). */
  public PlanEnvelope completePlan() {
    boolean t = usedIndexes.contains(IndexId.TIME);
    boolean z = usedIndexes.contains(IndexId.ZORDER);
    boolean h = usedIndexes.contains(IndexId.HASH);
    return PlanBuilder.buildForAccess(ir, t, z, h);
  }

  public double estimatedMsOrMax() {
    if (fastCost == null) {
      return Double.POSITIVE_INFINITY;
    }
    return fastCost.estimated_ms;
  }
}
