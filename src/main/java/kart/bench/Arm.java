package kart.bench;

import kart.ir.BoundIr;

/**
 * One bench arm: run a BoundIR under a fixed strategy (native policy, fullscan, or future adapter).
 */
public interface Arm {
  String id();

  TrialResult run(BoundIr ir, BenchContext ctx) throws Exception;
}
