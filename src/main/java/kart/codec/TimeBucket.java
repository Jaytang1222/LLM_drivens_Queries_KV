package kart.codec;

/**
 * Time bucket helpers: bucket id for timestamp, enumerate [a,b) covering buckets.
 * Index domain is {@code [epochMs, +∞)}; query scan ranges clamp to that domain.
 */
public final class TimeBucket {

  private TimeBucket() {}

  public static long bucketOf(long timestampMs, long epochMs, long bucketMs) {
    if (bucketMs <= 0) {
      throw new IllegalArgumentException("bucketMs must be > 0");
    }
    long shifted = timestampMs - epochMs;
    // Index keys are unsigned from epoch; building/index paths must not pass pre-epoch.
    if (shifted < 0) {
      throw new IllegalArgumentException("timestamp before epoch: " + timestampMs + " < " + epochMs);
    }
    return shifted / bucketMs;
  }

  /**
   * Half-open query {@code [startMs, endMs)}: buckets covering the portion that
   * intersects the index domain {@code [epochMs, +∞)}.
   * <ul>
   *   <li>Empty if {@code endMs <= startMs}</li>
   *   <li>Empty if the interval lies entirely before {@code epochMs}</li>
   *   <li>Intervals that cross epoch clamp the <em>index access</em> start to epoch;
   *       callers must still apply the original BoundIR half-open filter precisely</li>
   * </ul>
   */
  public static long[] bucketsCovering(long startMs, long endMs, long epochMs, long bucketMs) {
    if (bucketMs <= 0) {
      throw new IllegalArgumentException("bucketMs must be > 0");
    }
    if (endMs <= startMs) {
      return new long[0];
    }
    long accessStart = Math.max(startMs, epochMs);
    if (endMs <= accessStart) {
      return new long[0];
    }
    long lo = bucketOf(accessStart, epochMs, bucketMs);
    long hi = bucketOf(endMs - 1, epochMs, bucketMs);
    int n = (int) (hi - lo + 1);
    if (n <= 0) {
      return new long[0];
    }
    long[] out = new long[n];
    for (int i = 0; i < n; i++) {
      out[i] = lo + i;
    }
    return out;
  }
}
