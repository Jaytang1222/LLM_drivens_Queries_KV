package kart.bench;

import kart.ir.BoundIr;

/** Placeholder for future external adapters (DIN/SAG/Bao/LLMOpt). */
public final class UnsupportedArm implements Arm {

  private final String id;
  private final String reason;

  public UnsupportedArm(String id, String reason) {
    this.id = id;
    this.reason = reason != null ? reason : "not implemented";
  }

  @Override
  public String id() {
    return id;
  }

  @Override
  public TrialResult run(BoundIr ir, BenchContext ctx) {
    return TrialResult.fail("unsupported arm '" + id + "': " + reason);
  }
}
