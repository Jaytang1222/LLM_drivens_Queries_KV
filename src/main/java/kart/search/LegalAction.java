package kart.search;

/**
 * One legal action the proposal policy may choose (IMPLEMENTATION_PLAN §11.3).
 */
public final class LegalAction {

  public final String actionId;
  public final ActionKind kind;
  /** Index id for START/INTERSECT/REPLACE; null otherwise. */
  public final String indexId;
  /** HASH_SET or SORT_MERGE for CHOOSE_MERGE. */
  public final String mergeImpl;
  /** System partition kind for PARTITION_UNION. */
  public final String partitionKind;
  public final String description;

  public LegalAction(String actionId, ActionKind kind, String indexId, String description) {
    this(actionId, kind, indexId, null, null, description);
  }

  public LegalAction(String actionId, ActionKind kind, String indexId,
                     String mergeImpl, String partitionKind, String description) {
    this.actionId = actionId;
    this.kind = kind;
    this.indexId = indexId;
    this.mergeImpl = mergeImpl;
    this.partitionKind = partitionKind;
    this.description = description;
  }

  public boolean isFinish() {
    return kind == ActionKind.FINISH;
  }

  @Override
  public String toString() {
    return actionId + "(" + kind + (indexId != null ? ":" + indexId : "") + ")";
  }
}
