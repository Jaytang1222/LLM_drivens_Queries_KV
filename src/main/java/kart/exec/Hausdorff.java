package kart.exec;

/**
 * Symmetric Hausdorff distance on trajectory point sets (unordered).
 * {@code H(A,B) = max(h(A,B), h(B,A))} where
 * {@code h(A,B) = max_{a in A} min_{b in B} ||a-b||}.
 */
public final class Hausdorff {

  private Hausdorff() {}

  public static Dtw.Result distance(Dtw.Point[] a, Dtw.Point[] b) {
    if (a == null || b == null || a.length == 0 || b.length == 0) {
      throw new IllegalArgumentException("Hausdorff requires non-empty trajectories");
    }
    Directed left = directed(a, b);
    Directed right = directed(b, a);
    long cells = left.cells + right.cells;
    return new Dtw.Result(Math.max(left.distance, right.distance), cells);
  }

  private static Directed directed(Dtw.Point[] from, Dtw.Point[] to) {
    double maxMin = 0.0;
    long cells = 0;
    for (int i = 0; i < from.length; i++) {
      double min = Double.POSITIVE_INFINITY;
      for (int j = 0; j < to.length; j++) {
        double d = euclid(from[i], to[j]);
        cells++;
        if (d < min) {
          min = d;
        }
      }
      if (min > maxMin) {
        maxMin = min;
      }
    }
    return new Directed(maxMin, cells);
  }

  private static double euclid(Dtw.Point p, Dtw.Point q) {
    double dx = p.x - q.x;
    double dy = p.y - q.y;
    return Math.sqrt(dx * dx + dy * dy);
  }

  private static final class Directed {
    final double distance;
    final long cells;

    Directed(double distance, long cells) {
      this.distance = distance;
      this.cells = cells;
    }
  }
}
