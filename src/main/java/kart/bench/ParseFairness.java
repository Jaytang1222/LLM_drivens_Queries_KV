package kart.bench;

import kart.dialog.Dialog;

/**
 * Shared fairness helpers for E1 parse arms (same early-reject gate for kart/DIN/SAG).
 */
public final class ParseFairness {

  private ParseFairness() {}

  /**
   * @return unsupported reason, or null if utterance may proceed to LLM
   */
  public static String earlyReject(String utterance) {
    return Dialog.earlyUnsupportedReason(utterance);
  }

  public static ParseTrialResult rejectedTrial(NlItem item, String reason) {
    ParseTrialResult out = new ParseTrialResult();
    out.reject_actual = Boolean.TRUE;
    out.clarify_actual = Boolean.FALSE;
    out.t_parse_ms = Long.valueOf(0L);
    out.t_parse_total_ms = Long.valueOf(0L);
    out.t_parse_model_ms = Long.valueOf(0L);
    out.t_adapter_startup_ms = Long.valueOf(0L);
    out.llm_calls = Long.valueOf(0L);
    out.tokens_in = Long.valueOf(0L);
    out.tokens_out = Long.valueOf(0L);
    out.extras.put("early_reject", Boolean.TRUE);
    out.extras.put("reject_source", "shared_early_gate");
    if (reason != null) {
      out.extras.put("early_reject_reason", reason);
    }
    // Gold labels only for scoring — do not drive inference.
    ParseScore.scoreAgainstGold(out, item);
    if (!Boolean.TRUE.equals(out.ok_ex) && out.error == null) {
      out.error = reason;
    }
    return out;
  }
}
