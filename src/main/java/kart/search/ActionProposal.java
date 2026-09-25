package kart.search;

/**
 * One frontier proposal entry (IMPLEMENTATION_PLAN §11.4).
 */
public final class ActionProposal {

  public final String stateId;
  public final String actionId;
  public final String reasonCode;
  public final LegalAction action;
  public final boolean illegal;
  public final boolean fromLlm;

  public ActionProposal(String stateId, String actionId, String reasonCode,
                       LegalAction action, boolean illegal, boolean fromLlm) {
    this.stateId = stateId;
    this.actionId = actionId;
    this.reasonCode = reasonCode;
    this.action = action;
    this.illegal = illegal;
    this.fromLlm = fromLlm;
  }
}
