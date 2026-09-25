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
    out.reject_expected = item != null && item.reject_expected;
    out.reject_actual = true;
    out.clarify_actual = false;
    out.clarify_expected = item != null && item.clarify_expected;
    out.t_parse_ms = Long.valueOf(0L);
    out.t_parse_total_ms = Long.valueOf(0L);
    out.t_parse_model_ms = Long.valueOf(0L);
    out.t_adapter_startup_ms = Long.valueOf(0L);
    out.llm_calls = Long.valueOf(0L);
    out.tokens_in = Long.valueOf(0L);
    out.tokens_out = Long.valueOf(0L);
    if (item != null && item.reject_expected) {
      out.ok_ir_valid = true;
      out.ok_ex = true;
      out.ok = true;
    } else {
      out.ok_ir_valid = false;
      out.ok_ex = false;
      out.ok = false;
      out.error = reason;
    }
    return out;
  }
}
