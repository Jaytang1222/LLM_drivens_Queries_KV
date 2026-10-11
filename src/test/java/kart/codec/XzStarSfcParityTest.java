package kart.codec;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V2a smoke: XZ* enlarged-element length choice and non-empty SRQ ranges
 * (ported from TMan-spatial XZStarSFC).
 */
public class XzStarSfcParityTest {

  @Test
  void indexAndRangesAreDeterministicAndNonEmpty() {
    XzStarSfc sfc = XzStarSfc.create((short) 8, 0, 100, 0, 100);
    long a = sfc.indexMbr(10, 10, 20, 20, true);
    long b = sfc.indexMbr(10, 10, 20, 20, true);
    assertTrue(a == b);
    List<CodeRange> ranges = sfc.ranges(5, 5, 25, 25);
    assertFalse(ranges.isEmpty());
    boolean covers = false;
    for (CodeRange r : ranges) {
      if (r.lo <= a && a <= r.hi) {
        covers = true;
        break;
      }
    }
    assertTrue(covers, "SRQ ranges must cover indexed MBR code (XZ* design)");
  }

  @Test
  void xztIntervalEmbeddingUsesSameCore() {
    XztCodec xzt = new XztCodec((short) 8, 0L, 1_000_000L);
    long code = xzt.indexInterval(1000L, 5000L);
    assertTrue(code == xzt.indexInterval(1000L, 5000L));
    // Exact covering query (same window as indexed span) must include the code.
    List<CodeRange> ranges = xzt.rangesCovering(1000L, 5000L);
    assertFalse(ranges.isEmpty());
    boolean covers = false;
    for (CodeRange r : ranges) {
      if (r.lo <= code && code <= r.hi) {
        covers = true;
        break;
      }
    }
    assertTrue(covers, "XZT exact-window SRQ must cover indexed interval (XZ* design)");
  }

  @Test
  void tshapeIndexProducesStableKey() {
    TShapeSfc ts = TShapeSfc.create((short) 8, 0, 100, 0, 100);
    long a = ts.indexMbr(10, 10, 20, 20);
    long b = ts.indexMbr(10, 10, 20, 20);
    assertTrue(a == b);
    assertFalse(ts.ranges(5, 5, 25, 25).isEmpty());
  }

  /** V2a: LocS TShape — intersecting query must cover the indexed MBR code (no false miss). */
  @Test
  void tshapeRangesCoverIndexedIntersectingMbr() {
    TShapeSfc ts = TShapeSfc.create((short) 8, 0, 100, 0, 100);
    long code = ts.indexMbr(10, 10, 20, 20);
    boolean hit = false;
    for (CodeRange r : ts.ranges(5, 5, 25, 25)) {
      if (code >= r.lo && code <= r.hi) {
        hit = true;
        break;
      }
    }
    assertTrue(hit, "TShape query ranges must cover indexed intersecting MBR (LocS design)");
    boolean miss = false;
    for (CodeRange r : ts.ranges(80, 80, 90, 90)) {
      if (code >= r.lo && code <= r.hi) {
        miss = true;
        break;
      }
    }
    assertFalse(miss, "disjoint query should not claim the indexed MBR code");
  }
}
