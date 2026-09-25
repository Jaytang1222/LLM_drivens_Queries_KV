package kart.search;

import kart.cost.CostCard;
import kart.ir.BoundIr;
import kart.plan.PlanBuilder;
import kart.plan.PlanEnvelope;
import kart.validation.IncrementalValidator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Partial access subgraph during beam search (IMPLEMENTATION_PLAN §11).
 */
public final class SearchState {

  public static final String PARTITION_TIME_BIPART = "TIME_BIPART";
  public static final String PARTITION_Z_QUAD = "Z_QUAD";
  public static final String MERGE_HASH_SET = "HASH_SET";
  public static final String MERGE_SORT_MERGE = "SORT_MERGE";

  private static final AtomicInteger ID_SEQ = new AtomicInteger(0);

  private final String stateId;
  private final BoundIr ir;
  private final Set<String> usedIndexes;
  private final String mergeImpl;
  private final String partitionKind;
  private CostCard fastCost;

  private SearchState(BoundIr ir, Set<String> usedIndexes, CostCard fastCost,
                      String mergeImpl, String partitionKind, String stateId) {
    this.ir = ir;
    this.usedIndexes = Collections.unmodifiableSet(new LinkedHashSet<String>(usedIndexes));
    this.fastCost = fastCost;
    this.mergeImpl = mergeImpl;
    this.partitionKind = partitionKind;
    this.stateId = stateId != null ? stateId : ("s" + ID_SEQ.incrementAndGet());
  }

  public static SearchState initial(BoundIr ir) {
    return new SearchState(ir, new LinkedHashSet<String>(), null, null, null, "s0");
  }

  public String stateId() {
    return stateId;
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

  public String mergeImpl() {
    return mergeImpl;
  }

  public String partitionKind() {
    return partitionKind;
  }

  public boolean hasFullCoveringPartition() {
    return PARTITION_TIME_BIPART.equals(partitionKind);
  }

  public boolean hasSpatialPartition() {
    return PARTITION_Z_QUAD.equals(partitionKind);
  }

  public CostCard fastCost() {
    return fastCost;
  }

  public void setFastCost(CostCard fastCost) {
    this.fastCost = fastCost;
  }

  /** Uncovered predicate obligations (IMPLEMENTATION_PLAN §11). */
  public List<String> obligations() {
    return IncrementalValidator.predicateObligations(this);
  }

  /** Canonical signature of the access set + merge/partition. */
  public String signature() {
    StringBuilder sb = new StringBuilder("ACCESS:{");
    if (!usedIndexes.isEmpty()) {
      TreeSet<String> sorted = new TreeSet<String>(usedIndexes);
      boolean first = true;
      for (String id : sorted) {
        if (!first) {
          sb.append(',');
        }
        first = false;
        sb.append(id);
      }
    }
    sb.append('}');
    if (mergeImpl != null) {
      sb.append("|merge=").append(mergeImpl);
    }
    if (partitionKind != null) {
      sb.append("|part=").append(partitionKind);
    }
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
    String nextMerge = mergeImpl;
    String nextPart = partitionKind;
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
        nextMerge = null;
        nextPart = null;
        break;
      case CHOOSE_MERGE:
        if (usedIndexes.size() < 2) {
          throw new IllegalStateException("CHOOSE_MERGE requires >=2 indexes");
        }
        if (action.mergeImpl == null) {
          throw new IllegalArgumentException("CHOOSE_MERGE requires mergeImpl");
        }
        nextMerge = action.mergeImpl;
        break;
      case PARTITION_UNION:
        if (action.partitionKind == null) {
          throw new IllegalArgumentException("PARTITION_UNION requires system partition kind");
        }
        nextPart = action.partitionKind;
        if (SearchState.PARTITION_TIME_BIPART.equals(nextPart) && !next.contains(IndexId.TIME)) {
          next.add(IndexId.TIME);
        }
        if (SearchState.PARTITION_Z_QUAD.equals(nextPart) && !next.contains(IndexId.ZORDER)) {
          next.add(IndexId.ZORDER);
        }
        break;
      default:
        throw new IllegalStateException("unexpected kind " + action.kind);
    }
    return new SearchState(ir, next, null, nextMerge, nextPart, null);
  }

  /**
   * Complete this access subgraph into a logical plan (constructor suffix).
   * When {@code mergeImpl} is null and multiple indexes are used, PlanBuilder marks
   * {@code provisional_merge=true} for FastCost only — PlanValidator rejects those plans
   * (FINISH is illegal until {@link ActionKind#CHOOSE_MERGE}).
   */
  public PlanEnvelope completePlan() {
    boolean t = usedIndexes.contains(IndexId.TIME);
    boolean z = usedIndexes.contains(IndexId.ZORDER);
    boolean h = usedIndexes.contains(IndexId.HASH);
    // Do not silently invent HASH_SET: pass null so PlanBuilder tags provisional_merge.
    return PlanBuilder.buildForAccess(ir, t, z, h, mergeImpl, partitionKind);
  }

  public double estimatedMsOrMax() {
    if (fastCost == null) {
      return Double.POSITIVE_INFINITY;
    }
    return fastCost.estimated_ms;
  }
}
