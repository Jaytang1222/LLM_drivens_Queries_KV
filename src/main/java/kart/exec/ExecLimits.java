package kart.exec;

/**
 * Hard runtime limits for the executor.
 */
public final class ExecLimits {

  public int maxCandidateChunks = 200_000;
  public long maxDtwCells = 500_000_000L;
  public int fetchBatch = 500;
  public int indexParallelism = 4;

  public static ExecLimits defaults() {
    return new ExecLimits();
  }
}
