package kart.bench;

/**
 * E1 gold scoring after label-blind inference.
 * {@code reject_expected} / {@code clarify_expected} are used only here — never to
 * choose the parse status chain.
 */
public final class ParseScore {

  private ParseScore() {}

  public static void attachGoldLabels(ParseTrialResult out, NlItem item) {
    if (out == null) {
      return;
    }
    if (item == null) {
      out.reject_expected = Boolean.FALSE;
      out.clarify_expected = Boolean.FALSE;
      return;
    }
    out.reject_expected = Boolean.valueOf(item.reject_expected);
    out.clarify_expected = Boolean.valueOf(item.clarify_expected);
  }

  /**
   * Score {@code out} against gold class. Inference fields
   * ({@code reject_actual}, {@code clarify_actual}, {@code ok_ex} from EX) must
   * already be set.
   */
  public static void scoreAgainstGold(ParseTrialResult out, NlItem item) {
    if (out == null) {
      return;
    }
    attachGoldLabels(out, item);
    if (Boolean.TRUE.equals(out.extras.get("infrastructure_failure"))) {
      out.ok_ir_valid = Boolean.FALSE;
      out.ok_ex = Boolean.FALSE;
      out.ok = false;
      return;
    }
    if (item == null) {
      out.ok_ir_valid = Boolean.FALSE;
      out.ok_ex = Boolean.FALSE;
      out.ok = false;
      return;
    }
    if (item.clarify_expected) {
      boolean hit = Boolean.TRUE.equals(out.clarify_actual);
      out.ok_ir_valid = Boolean.valueOf(hit);
      out.ok_ex = Boolean.valueOf(hit);
      out.ok = hit;
      if (!hit && (out.error == null || out.error.isEmpty())) {
        out.error = "expected NEED_CLARIFICATION";
      }
      return;
    }
    if (item.reject_expected) {
      boolean hit = Boolean.TRUE.equals(out.reject_actual);
      out.ok_ir_valid = Boolean.valueOf(hit);
      out.ok_ex = Boolean.valueOf(hit);
      out.ok = hit;
      if (!hit && (out.error == null || out.error.isEmpty())) {
        out.error = "expected reject";
      }
      return;
    }
    // supported
    if (Boolean.TRUE.equals(out.reject_actual) || Boolean.TRUE.equals(out.clarify_actual)) {
      out.ok_ir_valid = Boolean.FALSE;
      out.ok_ex = Boolean.FALSE;
      out.ok = false;
      if (out.error == null || out.error.isEmpty()) {
        out.error = Boolean.TRUE.equals(out.clarify_actual)
            ? "NEED_CLARIFICATION" : "unsupported";
      }
      return;
    }
    if (out.ok_ex == null) {
      out.ok_ir_valid = Boolean.FALSE;
      out.ok_ex = Boolean.FALSE;
      out.ok = false;
    } else {
      out.ok = Boolean.TRUE.equals(out.ok_ex);
      if (out.ok_ir_valid == null) {
        out.ok_ir_valid = out.ok_ex;
      }
    }
  }
}
