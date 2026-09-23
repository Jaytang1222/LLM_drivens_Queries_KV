package kart.codec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Morton (Z-order) interleave for L-bit cell coordinates, and rectangle → Morton range decomposition.
 */
public final class ZOrder {

  private ZOrder() {}

  public static int cellsPerAxis(int level) {
    if (level < 1 || level > 16) {
      throw new IllegalArgumentException("level must be 1..16");
    }
    return 1 << level;
  }

  public static int quantize(double v, double min, double max, int level) {
    int n = cellsPerAxis(level);
    if (max <= min) {
      throw new IllegalArgumentException("domain max must be > min");
    }
    double cell = (max - min) / n;
    int c = (int) Math.floor((v - min) / cell);
    if (c < 0) {
      c = 0;
    }
    if (c >= n) {
      c = n - 1;
    }
    return c;
  }

  /** Interleave: cx on even bits, cy on odd bits (design.md §4.4). */
  public static long interleave(int cx, int cy, int level) {
    int mask = (1 << level) - 1;
    cx &= mask;
    cy &= mask;
    long z = 0;
    for (int i = 0; i < level; i++) {
      long bx = (cx >> i) & 1L;
      long by = (cy >> i) & 1L;
      z |= bx << (2 * i);
      z |= by << (2 * i + 1);
    }
    return z;
  }

  public static int[] deinterleave(long z, int level) {
    int cx = 0;
    int cy = 0;
    for (int i = 0; i < level; i++) {
      cx |= (int) (((z >> (2 * i)) & 1L) << i);
      cy |= (int) (((z >> (2 * i + 1)) & 1L) << i);
    }
    return new int[]{cx, cy};
  }

  public static final class CellRect {
    public final int cxMin;
    public final int cyMin;
    public final int cxMax;
    public final int cyMax;

    public CellRect(int cxMin, int cyMin, int cxMax, int cyMax) {
      this.cxMin = cxMin;
      this.cyMin = cyMin;
      this.cxMax = cxMax;
      this.cyMax = cyMax;
    }
  }

  public static final class MortonRange {
    public final long lo; // inclusive
    public final long hi; // inclusive

    public MortonRange(long lo, long hi) {
      this.lo = lo;
      this.hi = hi;
    }

    @Override
    public String toString() {
      return "[" + lo + "," + hi + "]";
    }
  }

  /**
   * Map meter rectangle (closed) to cell rectangle within domain.
   */
  public static CellRect metersToCells(
      double minX, double minY, double maxX, double maxY,
      double xmin, double ymin, double xmax, double ymax, int level) {
    int cx0 = quantize(minX, xmin, xmax, level);
    int cx1 = quantize(maxX, xmin, xmax, level);
    int cy0 = quantize(minY, ymin, ymax, level);
    int cy1 = quantize(maxY, ymin, ymax, level);
    return new CellRect(Math.min(cx0, cx1), Math.min(cy0, cy1), Math.max(cx0, cx1), Math.max(cy0, cy1));
  }

  /**
   * Decompose cell rectangle into merged Morton ranges; merge further if count > maxRanges (never drop cells).
   */
  public static List<MortonRange> decompose(CellRect cells, int level, int maxRanges) {
    List<MortonRange> ranges = new ArrayList<MortonRange>();
    decomposeRec(0, 0, cellsPerAxis(level), cells, level, ranges);
    List<MortonRange> merged = mergeAdjacent(ranges);
    while (merged.size() > maxRanges) {
      merged = mergeWidestPair(merged);
    }
    return merged;
  }

  private static void decomposeRec(int ox, int oy, int size, CellRect q, int level, List<MortonRange> out) {
    int qx0 = q.cxMin;
    int qy0 = q.cyMin;
    int qx1 = q.cxMax;
    int qy1 = q.cyMax;
    int ex = ox + size - 1;
    int ey = oy + size - 1;
    // no overlap
    if (ex < qx0 || ox > qx1 || ey < qy0 || oy > qy1) {
      return;
    }
    // fully covered
    if (ox >= qx0 && ex <= qx1 && oy >= qy0 && ey <= qy1) {
      long zLo = interleave(ox, oy, level);
      long zHi = interleave(ex, ey, level);
      // For a full power-of-two square aligned to the grid, Morton codes are contiguous
      // from the SW corner of the square through the NE corner only when size==1 or the
      // square is a complete z-order block. Use explicit min/max over corners for safety
      // when size > 1 by emitting the contiguous range of the z-order child block:
      // For aligned power-of-two blocks, range is [interleave(ox,oy), interleave(ox,oy) + size*size - 1].
      long base = interleave(ox, oy, level);
      long span = (long) size * (long) size;
      out.add(new MortonRange(base, base + span - 1));
      return;
    }
    if (size == 1) {
      long z = interleave(ox, oy, level);
      out.add(new MortonRange(z, z));
      return;
    }
    int half = size / 2;
    decomposeRec(ox, oy, half, q, level, out);
    decomposeRec(ox + half, oy, half, q, level, out);
    decomposeRec(ox, oy + half, half, q, level, out);
    decomposeRec(ox + half, oy + half, half, q, level, out);
  }

  static List<MortonRange> mergeAdjacent(List<MortonRange> in) {
    if (in.isEmpty()) {
      return in;
    }
    Collections.sort(in, (a, b) -> Long.compare(a.lo, b.lo));
    List<MortonRange> out = new ArrayList<MortonRange>();
    MortonRange cur = in.get(0);
    for (int i = 1; i < in.size(); i++) {
      MortonRange n = in.get(i);
      if (n.lo <= cur.hi + 1) {
        cur = new MortonRange(cur.lo, Math.max(cur.hi, n.hi));
      } else {
        out.add(cur);
        cur = n;
      }
    }
    out.add(cur);
    return out;
  }

  private static List<MortonRange> mergeWidestPair(List<MortonRange> in) {
    if (in.size() <= 1) {
      return in;
    }
    // Merge the pair of consecutive ranges with the smallest gap (most compact merge)
    int best = 0;
    long bestGap = Long.MAX_VALUE;
    for (int i = 0; i < in.size() - 1; i++) {
      long gap = in.get(i + 1).lo - in.get(i).hi;
      if (gap < bestGap) {
        bestGap = gap;
        best = i;
      }
    }
    List<MortonRange> out = new ArrayList<MortonRange>();
    for (int i = 0; i < in.size(); i++) {
      if (i == best) {
        out.add(new MortonRange(in.get(i).lo, in.get(i + 1).hi));
        i++;
      } else {
        out.add(in.get(i));
      }
    }
    return out;
  }

  /** True if every cell in rect is covered by the union of ranges. */
  public static boolean coversAllCells(CellRect cells, List<MortonRange> ranges, int level) {
    for (int cx = cells.cxMin; cx <= cells.cxMax; cx++) {
      for (int cy = cells.cyMin; cy <= cells.cyMax; cy++) {
        long z = interleave(cx, cy, level);
        if (!inRanges(z, ranges)) {
          return false;
        }
      }
    }
    return true;
  }

  private static boolean inRanges(long z, List<MortonRange> ranges) {
    for (MortonRange r : ranges) {
      if (z >= r.lo && z <= r.hi) {
        return true;
      }
    }
    return false;
  }
}
