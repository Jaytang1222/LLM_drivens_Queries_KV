package kart.oracle;

import kart.data.CanonicalPoint;
import kart.data.Trajectory;
import kart.exec.Dtw;
import kart.exec.TrajectorySimilarity;
import kart.ir.BoundIr;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FullScanOracle Top-K dispatches FRECHET / HAUSDORFF consistently with TrajectorySimilarity.
 */
class FullScanOracleSimilarityTest {

  @Test
  void topKFrechetAndHausdorffMatchDispatcher() {
    Trajectory ref = traj(1L, "R",
        xy(0, 0, 0), xy(1, 1, 0), xy(2, 2, 0));
    Trajectory a = traj(2L, "A",
        xy(0, 0, 0), xy(1, 1.1, 0), xy(2, 2.2, 0));
    Trajectory b = traj(3L, "B",
        xy(0, 10, 0), xy(1, 11, 0), xy(2, 12, 0));
    FullScanOracle oracle = new FullScanOracle(Arrays.asList(ref, a, b));

    BoundIr irF = topK("FRECHET", 1L, 2);
    FullScanOracle.Answer ansF = oracle.evaluate(irF);
    assertEquals(2, ansF.topK.size());
    Dtw.Point[] refPts = toDtw(ref);
    Dtw.Result daF = TrajectorySimilarity.distance("FRECHET", toDtw(a), refPts);
    Dtw.Result dbF = TrajectorySimilarity.distance("FRECHET", toDtw(b), refPts);
    assertEquals(daF.distance, ansF.topK.get(0).distance, 1e-9);
    assertEquals("A", ansF.topK.get(0).trajectoryId);
    assertTrue(daF.distance < dbF.distance);

    BoundIr irH = topK("HAUSDORFF", 1L, 2);
    FullScanOracle.Answer ansH = oracle.evaluate(irH);
    assertEquals("A", ansH.topK.get(0).trajectoryId);
    Dtw.Result daH = TrajectorySimilarity.distance("HAUSDORFF", toDtw(a), refPts);
    assertEquals(daH.distance, ansH.topK.get(0).distance, 1e-9);
  }

  private static BoundIr topK(String metric, long refTid, int k) {
    BoundIr ir = new BoundIr();
    ir.result = new BoundIr.Result();
    ir.result.mode = "TOP_K";
    ir.result.k = k;
    ir.similarity = new BoundIr.Similarity();
    ir.similarity.metric = metric;
    ir.similarity.reference_tid = refTid;
    ir.similarity.exclude_reference = true;
    ir.similarity.scope = "FULL_TRAJECTORY";
    return ir;
  }

  private static Trajectory traj(long tid, String id, double[]... pts) {
    List<CanonicalPoint> list = new ArrayList<CanonicalPoint>();
    for (int i = 0; i < pts.length; i++) {
      double[] p = pts[i];
      list.add(new CanonicalPoint("v", id, tid, i, (long) p[0], p[1], p[2]));
    }
    return new Trajectory("v", id, tid, list);
  }

  private static double[] xy(long t, double x, double y) {
    return new double[]{t, x, y};
  }

  private static Dtw.Point[] toDtw(Trajectory t) {
    Dtw.Point[] out = new Dtw.Point[t.size()];
    int i = 0;
    for (CanonicalPoint c : t.points()) {
      out[i++] = new Dtw.Point(c.xM, c.yM);
    }
    return out;
  }
}
