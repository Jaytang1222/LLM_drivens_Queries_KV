package kart.exec;

/**
 * Hard runtime limits for the executor.
 */
public final class ExecLimits {

  public int maxCandidateChunks = 200_000;
  public long maxDtwCells = 500_000_000L;
  public int fetchBatch = 500;
  /** Parallelism across independent index-branch plan nodes. */
  public int indexParallelism = 4;
  /** Parallel ScanTasks within one index node (RS-aware). */
  public int scanParallelism = 4;
  /** Max concurrent ScanTasks per RegionServer. */
  public int scanParallelismPerRs = 2;
  /** Parallelism for batched Get / fetch. */
  public int fetchParallelism = 4;
  /** Soft memory budget for spill estimate / merge (bytes). */
  public long softMemoryBytes = 512L * 1024L * 1024L;
  /**
   * Wall-clock execution deadline (ms). {@code <=0} disables.
   * Default 120s — planning budget is separate ({@code max_plan_ms}).
   */
  public long maxExecMs = 120_000L;

  public static ExecLimits defaults() {
    return new ExecLimits();
  }

  public static ExecLimits fromCostCoeffs(kart.config.AppConfig.CostCoeffs c) {
    ExecLimits lim = defaults();
    if (c == null) {
      return lim;
    }
    lim.indexParallelism = Math.max(1, c.concurrency_index);
    lim.scanParallelism = Math.max(1, c.concurrency_index);
    lim.scanParallelismPerRs = Math.max(1, c.concurrency_per_rs);
    lim.fetchParallelism = Math.max(1, c.concurrency_get);
    if (c.soft_memory_bytes > 0) {
      lim.softMemoryBytes = c.soft_memory_bytes;
    }
    if (c.max_exec_ms > 0) {
      lim.maxExecMs = c.max_exec_ms;
    }
    return lim;
  }
}
