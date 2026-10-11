package kart.codec;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Java port of TMan-spatial {@code index.XZStarSFC} (XZ* encode + SRQ ranges).
 * Source: experiments/third_party/tman-spatial/.../XZStarSFC.scala + ElementKNN.scala.
 * Default beta=1 (2×2 shape) matches XZStarIndex wrapper.
 */
public final class XzStarSfc {

  private static final double LOG_HALF = Math.log(0.5);
  private static final int[] PS_MAXIMUM = {0, 10, 0, 1, 0, 2, 9, 3, 0, 8, 0, 5, 0, 6, 7, 4};
  private static final int[] POSITION_INDEX = {3, 5, 7, 15, 11, 13, 14, 9, 6, 1};

  private final short g;
  private final int beta;
  private final double xLo;
  private final double xHi;
  private final double yLo;
  private final double yHi;
  private final double xSize;
  private final double ySize;

  public XzStarSfc(short g, double xLo, double xHi, double yLo, double yHi, int beta) {
    if (g < 1) {
      throw new IllegalArgumentException("g must be >= 1");
    }
    this.g = g;
    this.beta = beta;
    this.xLo = xLo;
    this.xHi = xHi;
    this.yLo = yLo;
    this.yHi = yHi;
    this.xSize = xHi - xLo;
    this.ySize = yHi - yLo;
    if (xSize <= 0 || ySize <= 0) {
      throw new IllegalArgumentException("bounds must be ordered");
    }
  }

  /** Factory matching XZStarIndex(beta=1). */
  public static XzStarSfc create(short g, double xLo, double xHi, double yLo, double yHi) {
    return new XzStarSfc(g, xLo, xHi, yLo, yHi, 1);
  }

  public long indexMbr(double xmin, double ymin, double xmax, double ymax, boolean lenient) {
    return index2(xmin, ymin, xmax, ymax, lenient)[0];
  }

  /** Returns {sequenceCode, posCode, length}. */
  public long[] index2(double xmin, double ymin, double xmax, double ymax, boolean lenient) {
    double[] n = normalize(xmin, ymin, xmax, ymax, lenient);
    double nxmin = n[0];
    double nymin = n[1];
    double nxmax = n[2];
    double nymax = n[3];
    double maxDim = Math.max(nxmax - nxmin, nymax - nymin);
    if (maxDim <= 0) {
      maxDim = Math.pow(0.5, g);
    }
    int l1 = (int) Math.floor(Math.log(maxDim) / LOG_HALF);
    int length;
    if (l1 >= g) {
      length = g;
    } else {
      double w2 = Math.pow(0.5, l1 + 1);
      if (predicate(nxmin, nxmax, w2) && predicate(nymin, nymax, w2)) {
        length = l1 + 1;
      } else {
        length = l1;
      }
    }
    if (length < 0) {
      length = 0;
    }
    if (length > g) {
      length = g;
    }
    double w = Math.pow(0.5, length);
    double x = Math.floor(nxmin / w) * w;
    double y = Math.floor(nymin / w) * w;
    long pc = signatureRect(
        x * xSize + xLo,
        y * ySize + yLo,
        (x + 2 * w) * xSize + xLo,
        (y + 2 * w) * ySize + yLo,
        xmin, ymin, xmax, ymax);
    long cs = sequenceCode(nxmin, nymin, length, pc);
    return new long[] {cs, pc, length};
  }

  private static boolean predicate(double min, double max, double w2) {
    return max <= (Math.floor(min / w2) * w2) + (2 * w2);
  }

  long signatureRect(double x1, double y1, double x2, double y2,
                     double gx1, double gy1, double gx2, double gy2) {
    ArrayDeque<Element2> remaining = new ArrayDeque<Element2>((int) Math.pow(4, beta));
    Element2 root = new Element2(x1, y1, x2, y2, 1, 0L);
    for (Element2 c : root.children()) {
      remaining.add(c);
    }
    Element2 levelTerminator = new Element2(-1, -1, -1, -1, 0, 0L);
    remaining.add(levelTerminator);
    int level = 1;
    int sig = 0;
    while (!remaining.isEmpty()) {
      Element2 next = remaining.poll();
      if (next == levelTerminator || (next.xmin < 0 && next.level == 0)) {
        if (!remaining.isEmpty() && level < beta) {
          level++;
          remaining.add(levelTerminator);
        }
      } else if (rectsOverlap(next.xmin, next.ymin, next.xmax, next.ymax, gx1, gy1, gx2, gy2)) {
        if (level < beta) {
          for (Element2 c : next.children()) {
            remaining.add(c);
          }
        } else {
          sig |= (1 << (int) next.code);
        }
      }
    }
    return PS_MAXIMUM[sig & 15];
  }

  private static boolean rectsOverlap(double ax1, double ay1, double ax2, double ay2,
                                     double bx1, double by1, double bx2, double by2) {
    return ax2 >= bx1 && ay2 >= by1 && ax1 <= bx2 && ay1 <= by2;
  }

  long sequenceCode(double x, double y, int length, long posCode) {
    double xmin = 0.0;
    double ymin = 0.0;
    double xmax = 1.0;
    double ymax = 1.0;
    long cs = 0L;
    for (int i = 1; i <= length; i++) {
      double xCenter = (xmin + xmax) / 2.0;
      double yCenter = (ymin + ymax) / 2.0;
      if (x < xCenter && y < yCenter) {
        cs += 9L;
        xmax = xCenter;
        ymax = yCenter;
      } else if (x >= xCenter && y < yCenter) {
        cs += 9L + 1L * isLevel(i);
        xmin = xCenter;
        ymax = yCenter;
      } else if (x < xCenter && y >= yCenter) {
        cs += 9L + 2L * isLevel(i);
        xmax = xCenter;
        ymin = yCenter;
      } else {
        cs += 9L + 3L * isLevel(i);
        xmin = xCenter;
        ymin = yCenter;
      }
    }
    return cs - 10L + posCode;
  }

  private long isLevel(int i) {
    return (39L * (long) Math.pow(4, g - i) - 9L) / 3L;
  }

  double[] normalize(double xmin, double ymin, double xmax, double ymax, boolean lenient) {
    if (xmin > xmax || ymin > ymax) {
      throw new IllegalArgumentException("Bounds must be ordered");
    }
    if (!lenient && (xmin < xLo || xmax > xHi || ymin < yLo || ymax > yHi)) {
      throw new IllegalArgumentException("Values out of bounds");
    }
    double bxmin = clamp(xmin, xLo, xHi);
    double bymin = clamp(ymin, yLo, yHi);
    double bxmax = clamp(xmax, xLo, xHi);
    double bymax = clamp(ymax, yLo, yHi);
    return new double[] {
        (bxmin - xLo) / xSize,
        (bymin - yLo) / ySize,
        (bxmax - xLo) / xSize,
        (bymax - yLo) / ySize
    };
  }

  private static double clamp(double v, double lo, double hi) {
    if (v < lo) {
      return lo;
    }
    if (v > hi) {
      return hi;
    }
    return v;
  }

  public List<CodeRange> ranges(double lng1, double lat1, double lng2, double lat2) {
    double qxmin = Math.min(lng1, lng2);
    double qymin = Math.min(lat1, lat2);
    double qxmax = Math.max(lng1, lng2);
    double qymax = Math.max(lat1, lat2);
    // One resolution cell of padding; XZ* enlarged elements sit on a 2× grid.
    double padX = xSize / Math.pow(2.0, g);
    double padY = ySize / Math.pow(2.0, g);
    qxmin = Math.max(xLo, qxmin - padX);
    qymin = Math.max(yLo, qymin - padY);
    qxmax = Math.min(xHi, qxmax + padX);
    qymax = Math.min(yHi, qymax + padY);
    QueryWindow window = new QueryWindow(qxmin, qymin, qxmax, qymax);
    List<CodeRange> ranges = new ArrayList<CodeRange>(100);
    ArrayDeque<ElementKnn> remaining = new ArrayDeque<ElementKnn>(200);
    ElementKnn levelStop = new ElementKnn(-1, -1, -1, -1, -1, g, -1L);
    ElementKnn root = new ElementKnn(xLo, yLo, xHi, yHi, 0, g, 0L);
    root.split();
    remaining.addAll(root.children);
    remaining.add(levelStop);
    short level = 1;
    while (!remaining.isEmpty()) {
      ElementKnn next = remaining.poll();
      if (next.level < 0) {
        if (!remaining.isEmpty() && level < g) {
          level = (short) (level + 1);
          remaining.add(levelStop);
        }
      } else if (next.isContained(window)) {
        long min = next.elementCode + 1L;
        long max = next.elementCode + next.isAt(level) - 1L;
        ranges.add(new CodeRange(min, max, true));
      } else if (next.insertion(window)) {
        ranges.addAll(next.insertCodes(window));
        if (level < g) {
          next.split();
          remaining.addAll(next.children);
        }
      }
    }
    // XZ* SRQ can leave holes inside the returned span; fill them so overlapping
    // indexed MBRs are not missed (over-fetch preferred to false negatives).
    return fillInternalGaps(mergeRanges(ranges));
  }

  /** Collapse holes between the first and last SRQ range (soundness > selectivity). */
  static List<CodeRange> fillInternalGaps(List<CodeRange> ranges) {
    if (ranges == null || ranges.size() <= 1) {
      return ranges;
    }
    long lo = ranges.get(0).lo;
    long hi = ranges.get(0).hi;
    boolean contained = ranges.get(0).contained;
    for (int i = 1; i < ranges.size(); i++) {
      CodeRange r = ranges.get(i);
      lo = Math.min(lo, r.lo);
      hi = Math.max(hi, r.hi);
      contained = contained && r.contained;
    }
    List<CodeRange> out = new ArrayList<CodeRange>(1);
    out.add(new CodeRange(lo, hi, contained));
    return out;
  }

  private static List<CodeRange> mergeRanges(List<CodeRange> ranges) {
    if (ranges.isEmpty()) {
      return ranges;
    }
    Collections.sort(ranges, new Comparator<CodeRange>() {
      @Override
      public int compare(CodeRange a, CodeRange b) {
        if (a.lo < b.lo) {
          return -1;
        }
        if (a.lo > b.lo) {
          return 1;
        }
        return Long.compare(a.hi, b.hi);
      }
    });
    List<CodeRange> result = new ArrayList<CodeRange>();
    CodeRange current = ranges.get(0);
    for (int i = 1; i < ranges.size(); i++) {
      CodeRange range = ranges.get(i);
      if (range.lo <= current.hi + 1) {
        current = new CodeRange(current.lo, Math.max(current.hi, range.hi),
            current.contained && range.contained);
      } else {
        result.add(current);
        current = range;
      }
    }
    result.add(current);
    return result;
  }

  private final class Element2 {
    final double xmin;
    final double ymin;
    final double xmax;
    final double ymax;
    final int level;
    final long code;

    Element2(double xmin, double ymin, double xmax, double ymax, int level, long code) {
      this.xmin = xmin;
      this.ymin = ymin;
      this.xmax = xmax;
      this.ymax = ymax;
      this.level = level;
      this.code = code;
    }

    List<Element2> children() {
      double xCenter = (xmin + xmax) / 2.0;
      double yCenter = (ymin + ymax) / 2.0;
      long step = (long) Math.pow(4, beta - level);
      List<Element2> out = new ArrayList<Element2>(4);
      out.add(new Element2(xmin, ymin, xCenter, yCenter, level + 1, code));
      out.add(new Element2(xCenter, ymin, xmax, yCenter, level + 1, code + 1L * step));
      out.add(new Element2(xmin, yCenter, xCenter, ymax, level + 1, code + 2L * step));
      out.add(new Element2(xCenter, yCenter, xmax, ymax, level + 1, code + 3L * step));
      return out;
    }
  }

  private static final class QueryWindow {
    final double xmin;
    final double ymin;
    final double xmax;
    final double ymax;

    QueryWindow(double xmin, double ymin, double xmax, double ymax) {
      this.xmin = xmin;
      this.ymin = ymin;
      this.xmax = xmax;
      this.ymax = ymax;
    }
  }

  private final class ElementKnn {
    final double xmin;
    final double ymin;
    final double xmax;
    final double ymax;
    final int level;
    final int maxG;
    final long elementCode;
    final double xLength;
    final double yLength;
    final List<ElementKnn> children = new ArrayList<ElementKnn>(4);

    ElementKnn(double xmin, double ymin, double xmax, double ymax, int level, int maxG, long elementCode) {
      this.xmin = xmin;
      this.ymin = ymin;
      this.xmax = xmax;
      this.ymax = ymax;
      this.level = level;
      this.maxG = maxG;
      this.elementCode = elementCode;
      this.xLength = xmax - xmin;
      this.yLength = ymax - ymin;
    }

    boolean insertion(QueryWindow window) {
      return window.xmax >= xmin && window.ymax >= ymin
          && window.xmin <= xmax + xLength && window.ymin <= ymax + yLength;
    }

    boolean insertion(QueryWindow window, double x1, double y1, double x2, double y2) {
      return window.xmax >= x1 && window.ymax >= y1 && window.xmin <= x2 && window.ymin <= y2;
    }

    boolean isContained(QueryWindow window) {
      return window.xmin <= xmin && window.ymin <= ymin
          && window.xmax >= xmax + xLength && window.ymax >= ymax + yLength;
    }

    List<CodeRange> insertCodes(QueryWindow window) {
      int pos = 0;
      double xeMax = xmax + xLength;
      double yeMax = ymax + yLength;
      double xCenter = xmax;
      double yCenter = ymax;
      if (insertion(window, xmin, ymin, xCenter, yCenter)) {
        pos |= 1;
      }
      if (insertion(window, xCenter, ymin, xeMax, yCenter)) {
        pos |= 2;
      }
      if (insertion(window, xmin, yCenter, xCenter, yeMax)) {
        pos |= 4;
      }
      if (insertion(window, xCenter, yCenter, xeMax, yeMax)) {
        pos |= 8;
      }
      long pSize = level < maxG ? 8L : 9L;
      List<CodeRange> results = new ArrayList<CodeRange>(8);
      for (long i = 0; i <= pSize; i++) {
        if ((POSITION_INDEX[(int) i] & pos) != 0) {
          long v = i + 1L;
          long code = v + elementCode - 10L;
          results.add(new CodeRange(code, code, false));
        }
      }
      return results;
    }

    long isAt(int i) {
      return (39L * (long) Math.pow(4, maxG - i) - 9L) / 3L;
    }

    void split() {
      if (!children.isEmpty()) {
        return;
      }
      double xCenter = (xmax + xmin) / 2.0;
      double yCenter = (ymax + ymin) / 2.0;
      children.add(new ElementKnn(xmin, ymin, xCenter, yCenter, level + 1, maxG, elementCode + 9L));
      children.add(new ElementKnn(xCenter, ymin, xmax, yCenter, level + 1, maxG,
          elementCode + 9L + 1L * isAt(level + 1)));
      children.add(new ElementKnn(xmin, yCenter, xCenter, ymax, level + 1, maxG,
          elementCode + 9L + 2L * isAt(level + 1)));
      children.add(new ElementKnn(xCenter, yCenter, xmax, ymax, level + 1, maxG,
          elementCode + 9L + 3L * isAt(level + 1)));
    }
  }
}
