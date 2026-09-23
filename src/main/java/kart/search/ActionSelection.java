package kart.search;

/**
 * Policy output: chosen action plus optional reason / fallback flags.
 */
public final class ActionSelection {

  public final LegalAction action;
  public final String reason;
  public final boolean fromLlm;
  public final boolean illegalFallback;
  public final String requestedActionId;

  public ActionSelection(LegalAction action, String reason, boolean fromLlm,
                         boolean illegalFallback, String requestedActionId) {
    this.action = action;
    this.reason = reason;
    this.fromLlm = fromLlm;
    this.illegalFallback = illegalFallback;
    this.requestedActionId = requestedActionId;
  }

  public static ActionSelection of(LegalAction action, String reason) {
    return new ActionSelection(action, reason, false, false, action != null ? action.actionId : null);
  }
}
