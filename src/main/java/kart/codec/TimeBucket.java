package kart.codec;

/**
 * Time bucket helpers: bucket id for timestamp, enumerate [a,b) covering buckets.
 */
public final class TimeBucket {

  private TimeBucket() {}

  public static long bucketOf(long timestampMs, long epochMs, long bucketMs) {
    if (bucketMs <= 0) {
      throw new IllegalArgumentException("bucketMs must be > 0");
    }
    long shifted = timestampMs - epochMs;
    // floor division toward -inf for negative? MVP assumes timestampMs >= epochMs
    if (shifted < 0) {
      throw new IllegalArgumentException("timestamp before epoch: " + timestampMs + " < " + epochMs);
    }
    return shifted / bucketMs;
  }

  /**
   * Half-open query [startMs, endMs): buckets b(start) .. b(end-1) inclusive.
   * Empty if endMs <= startMs.
   */
  public static long[] bucketsCovering(long startMs, long endMs, long epochMs, long bucketMs) {
    if (endMs <= startMs) {
      return new long[0];
    }
    long lo = bucketOf(startMs, epochMs, bucketMs);
    long hi = bucketOf(endMs - 1, epochMs, bucketMs);
    int n = (int) (hi - lo + 1);
    long[] out = new long[n];
    for (int i = 0; i < n; i++) {
      out[i] = lo + i;
    }
    return out;
  }
}
