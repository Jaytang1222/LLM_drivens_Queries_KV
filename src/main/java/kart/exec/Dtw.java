package kart.exec;

/**
 * Exact DTW with Euclidean local distance, rolling two rows. Shared by Oracle and executor.
 */
public final class Dtw {

  private Dtw() {}

  public static final class Point {
    public final double x;
    public final double y;

    public Point(double x, double y) {
      this.x = x;
      this.y = y;
    }
  }

  public static final class Result {
    public final double distance;
    public final long cells;

    public Result(double distance, long cells) {
      this.distance = distance;
      this.cells = cells;
    }
  }

  public static Result distance(Point[] a, Point[] b) {
    if (a == null || b == null || a.length == 0 || b.length == 0) {
      throw new IllegalArgumentException("DTW requires non-empty trajectories");
    }
    int n = a.length;
    int m = b.length;
    double[] prev = new double[m + 1];
    double[] cur = new double[m + 1];
    for (int j = 0; j <= m; j++) {
      prev[j] = Double.POSITIVE_INFINITY;
    }
    prev[0] = 0.0;
    long cells = 0;
    for (int i = 1; i <= n; i++) {
      cur[0] = Double.POSITIVE_INFINITY;
      for (int j = 1; j <= m; j++) {
        double cost = euclid(a[i - 1], b[j - 1]);
        cur[j] = cost + Math.min(prev[j], Math.min(cur[j - 1], prev[j - 1]));
        cells++;
      }
      double[] tmp = prev;
      prev = cur;
      cur = tmp;
    }
    return new Result(prev[m], cells);
  }

  private static double euclid(Point p, Point q) {
    double dx = p.x - q.x;
    double dy = p.y - q.y;
    return Math.sqrt(dx * dx + dy * dy);
  }
}
