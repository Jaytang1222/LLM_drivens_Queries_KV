package kart.codec;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** Overlapping-interval recall for XZT (catches XZ* SRQ false negatives). */
public class XztOverlapRecallTest {

  private static boolean covers(List<CodeRange> ranges, long code) {
    for (CodeRange r : ranges) {
      if (code >= r.lo && code <= r.hi) {
        return true;
      }
    }
    return false;
  }

  @Test
  void overlappingIntervalsCoveredOnUnitHorizon() {
    // Reproduce reported gap class: multi-day query on long horizon.
    long epoch = 1_200_000_000_000L;
    long horizon = epoch + 3650L * 24L * 3600L * 1000L;
    XztCodec xzt = new XztCodec((short) 12, epoch, horizon);
    long qs = 1_202_068_200_000L;
    long qe = 1_202_250_266_000L;
    List<CodeRange> ranges = xzt.rangesCovering(qs, qe);
    int miss = 0;
    int total = 0;
    // Sample overlapping intervals within/near the query.
    Random rnd = new Random(42);
    for (int i = 0; i < 2000; i++) {
      long a = qs - rnd.nextInt(3) * 86_400_000L + rnd.nextInt(5) * 3_600_000L;
      long b = a + 600_000L + rnd.nextInt(10) * 3_600_000L;
      if (b < a) {
        long t = a;
        a = b;
        b = t;
      }
      if (a > qe || b < qs) {
        continue; // non-overlapping
      }
      total++;
      long code = xzt.indexInterval(a, b);
      if (!covers(ranges, code)) {
        miss++;
        if (miss <= 5) {
          System.err.println("FN code=" + code + " interval=[" + a + "," + b + "]");
        }
      }
    }
    assertTrue(total > 100, "sample size");
    assertTrue(miss == 0, "XZT overlap SRQ FN=" + miss + "/" + total);
  }

  @Test
  void xzStarSpatialOverlappingMbrsCovered() {
    XzStarSfc sfc = XzStarSfc.create((short) 10, 0, 100, 0, 100);
    List<CodeRange> ranges = sfc.ranges(20, 20, 40, 40);
    Random rnd = new Random(7);
    int miss = 0;
    int total = 0;
    for (int i = 0; i < 1500; i++) {
      double x0 = 10 + rnd.nextDouble() * 40;
      double y0 = 10 + rnd.nextDouble() * 40;
      double x1 = x0 + 1 + rnd.nextDouble() * 15;
      double y1 = y0 + 1 + rnd.nextDouble() * 15;
      // require geometric overlap with query [20,40]^2
      if (x1 < 20 || x0 > 40 || y1 < 20 || y0 > 40) {
        continue;
      }
      total++;
      long code = sfc.indexMbr(x0, y0, x1, y1, true);
      if (!covers(ranges, code)) {
        miss++;
      }
    }
    assertTrue(total > 100);
    if (miss > 0) {
      fail("XZ* spatial overlap SRQ FN=" + miss + "/" + total);
    }
  }
}
