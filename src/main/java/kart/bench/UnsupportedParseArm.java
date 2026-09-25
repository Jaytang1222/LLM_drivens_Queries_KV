package kart.bench;

/** Placeholder ParseArm for DIN/SAG until third_party bridges land. */
public final class UnsupportedParseArm implements ParseArm {

  private final String id;
  private final String reason;

  public UnsupportedParseArm(String id, String reason) {
    this.id = id;
    this.reason = reason != null ? reason : "not implemented";
  }

  @Override
  public String id() {
    return id;
  }

  @Override
  public ParseTrialResult parse(NlItem item, BenchContext ctx) {
    ParseTrialResult r = new ParseTrialResult();
    r.ok = false;
    r.ok_ex = false;
    r.ok_ir_valid = false;
    r.reject_expected = item != null && item.reject_expected;
    r.reject_actual = false;
    r.error = "unsupported parse arm '" + id + "': " + reason;
    return r;
  }
}
