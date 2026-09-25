package kart.exec;

/**
 * Discrete Fréchet distance with Euclidean coupling.
 * Shared by Oracle and executor via {@link TrajectorySimilarity}.
 */
public final class Frechet {

  private Frechet() {}

  public static Dtw.Result distance(Dtw.Point[] a, Dtw.Point[] b) {
    if (a == null || b == null || a.length == 0 || b.length == 0) {
      throw new IllegalArgumentException("Fréchet requires non-empty trajectories");
    }
    int n = a.length;
    int m = b.length;
    double[][] ca = new double[n][m];
    long cells = 0;
    for (int i = 0; i < n; i++) {
      for (int j = 0; j < m; j++) {
        double couple = euclid(a[i], b[j]);
        cells++;
        if (i == 0 && j == 0) {
          ca[i][j] = couple;
        } else if (i == 0) {
          ca[i][j] = Math.max(ca[i][j - 1], couple);
        } else if (j == 0) {
          ca[i][j] = Math.max(ca[i - 1][j], couple);
        } else {
          double via = Math.min(ca[i - 1][j], Math.min(ca[i][j - 1], ca[i - 1][j - 1]));
          ca[i][j] = Math.max(via, couple);
        }
      }
    }
    return new Dtw.Result(ca[n - 1][m - 1], cells);
  }

  private static double euclid(Dtw.Point p, Dtw.Point q) {
    double dx = p.x - q.x;
    double dy = p.y - q.y;
    return Math.sqrt(dx * dx + dy * dy);
  }
}
