package kart.search;

/**
 * One legal action the proposal policy may choose (design.md §8.1).
 */
public final class LegalAction {

  public final String actionId;
  public final ActionKind kind;
  /** Index id for START/INTERSECT/REPLACE; null for FINISH. */
  public final String indexId;
  public final String description;

  public LegalAction(String actionId, ActionKind kind, String indexId, String description) {
    this.actionId = actionId;
    this.kind = kind;
    this.indexId = indexId;
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
