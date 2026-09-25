package kart.exec;

/**
 * Dispatches FULL_TRAJECTORY similarity metrics. Never silently substitutes metrics.
 */
public final class TrajectorySimilarity {

  public static final String DTW = "DTW";
  public static final String FRECHET = "FRECHET";
  public static final String HAUSDORFF = "HAUSDORFF";

  private TrajectorySimilarity() {}

  public static boolean isSupported(String metric) {
    return DTW.equals(metric) || FRECHET.equals(metric) || HAUSDORFF.equals(metric);
  }

  public static Dtw.Result distance(String metric, Dtw.Point[] a, Dtw.Point[] b) {
    if (metric == null || metric.trim().isEmpty()) {
      throw new IllegalArgumentException("similarity metric required");
    }
    if (DTW.equals(metric)) {
      return Dtw.distance(a, b);
    }
    if (FRECHET.equals(metric)) {
      return Frechet.distance(a, b);
    }
    if (HAUSDORFF.equals(metric)) {
      return Hausdorff.distance(a, b);
    }
    throw new IllegalArgumentException("unsupported similarity metric: " + metric);
  }
}
