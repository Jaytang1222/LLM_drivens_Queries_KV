package kart.exec;

import kart.data.Chunk;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TopKLowerBoundTest {
  @Test void chunkSubsetBoundNeverExceedsTrueDistance() {
    Random rnd = new Random(7);
    for (int trial = 0; trial < 500; trial++) {
      Dtw.Point[] ref = walk(rnd, 1 + rnd.nextInt(40));
      Dtw.Point[] cand = walk(rnd, 1 + rnd.nextInt(300));
      int from = rnd.nextInt(cand.length);
      int to = from + 1 + rnd.nextInt(cand.length - from);
      List<Chunk.DecodedPoint> subset = new ArrayList<Chunk.DecodedPoint>();
      for (int i = from; i < to; i++) {
        subset.add(new Chunk.DecodedPoint(i, cand[i].x, cand[i].y));
      }
      for (String m : new String[] {TrajectorySimilarity.DTW, TrajectorySimilarity.FRECHET, TrajectorySimilarity.HAUSDORFF}) {
        double lb = Coordinator.lowerBound(subset, ref, TrajectorySimilarity.DTW.equals(m));
        double d = TrajectorySimilarity.distance(m, cand, ref).distance;
        assertTrue(lb <= d + 1e-9 * Math.max(1.0, d), m + " lb=" + lb + " d=" + d);
      }
    }
  }

  private static Dtw.Point[] walk(Random rnd, int n) {
    Dtw.Point[] out = new Dtw.Point[n];
    double x = rnd.nextDouble() * 5000;
    double y = rnd.nextDouble() * 5000;
    for (int i = 0; i < n; i++) {
      x += rnd.nextGaussian() * 50;
      y += rnd.nextGaussian() * 50;
      out[i] = new Dtw.Point(x, y);
    }
    return out;
  }
}
