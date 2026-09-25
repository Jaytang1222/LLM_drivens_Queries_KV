package kart.bench;

/**
 * Reserved NL → DraftIR/BoundIR arm (DIN/SAG adapters later). Not exercised by default suites.
 */
public interface ParseArm {
  String id();

  ParseTrialResult parse(NlItem item, BenchContext ctx) throws Exception;
}
