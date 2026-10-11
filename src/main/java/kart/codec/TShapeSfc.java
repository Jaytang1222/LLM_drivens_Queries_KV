package kart.codec;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Java port of TMan-spatial TShape (LocS/CellIndex path): {@code XZSFC} + {@code CellIndex.ranges}
 * and storage key {@code signature | (location << alpha*beta)} from TrajPutUtil.
 * Default alpha=beta=2 matches LocSIndex / IndexMethod.TSHAPE.
 */
public final class TShapeSfc {

  private final short maxR;
  private final int alpha;
  private final int beta;
  private final double xLo;
  private final double xHi;
  private final double yLo;
  private final double yHi;
  private final double xSize;
  private final double ySize;
  private final TShapeEE root;

  public TShapeSfc(short maxR, double xLo, double xHi, double yLo, double yHi, int alpha, int beta) {
    if (maxR < 1) {
      throw new IllegalArgumentException("maxR must be >= 1");
    }
    this.maxR = maxR;
    this.alpha = alpha;
    this.beta = beta;
    this.xLo = xLo;
    this.xHi = xHi;
    this.yLo = yLo;
    this.yHi = yHi;
    this.xSize = xHi - xLo;
    this.ySize = yHi - yLo;
    this.root = new TShapeEE(xLo, yLo, xHi, yHi, 0, 0L);
  }

  public static TShapeSfc create(short maxR, double xLo, double xHi, double yLo, double yHi) {
    return new TShapeSfc(maxR, xLo, xHi, yLo, yHi, 2, 2);
  }

  public int shapeBits() {
    return alpha * beta;
  }

  /** Single posting key: {@code signature | (location << shapeBits)}. */
  public long indexMbr(double xmin, double ymin, double xmax, double ymax) {
    int l = resolutionMbr(xmin, ymin, xmax, ymax);
    SeqCell sc = sequenceCode(xmin, ymin, l);
    double w = xSize * Math.pow(0.5, l);
    double h = ySize * Math.pow(0.5, l);
    long sig = signature(sc.xmin, sc.ymin, w, h, xmin, ymin, xmax, ymax);
    return sig | (sc.code << shapeBits());
  }

  int resolutionMbr(double xmin, double ymin, double xmax, double ymax) {
    double maxDim = Math.max(((xmax - xmin) / xSize) / alpha, ((ymax - ymin) / ySize) / beta);
    int l = (int) Math.floor(Math.log(maxDim) / Math.log(0.5));
    if (l > maxR) {
      l = maxR;
    } else {
      double w = xSize * Math.pow(0.5, l);
      double h = ySize * Math.pow(0.5, l);
      if (!predicateX(xmin, xmax, w) || !predicateY(ymin, ymax, h)) {
        l = l - 1;
      }
    }
    if (l < 0) {
      l = 0;
    }
    return l;
  }

  private boolean predicateX(double min, double max, double w) {
    return max <= xLo + (Math.floor((min - xLo) / w) * w) + (alpha * w);
  }

  private boolean predicateY(double min, double max, double h) {
    return max <= yLo + (Math.floor((min - yLo) / h) * h) + (beta * h);
  }

  private static final class SeqCell {
    final long code;
    final double xmin;
    final double ymin;

    SeqCell(long code, double xmin, double ymin) {
      this.code = code;
      this.xmin = xmin;
      this.ymin = ymin;
    }
  }

  SeqCell sequenceCode(double x, double y, int l) {
    double xmin = xLo;
    double ymin = yLo;
    double xmax = xHi;
    double ymax = yHi;
    long cs = 0L;
    for (int i = 1; i <= l; i++) {
      double xCenter = (xmin + xmax) / 2.0;
      double yCenter = (ymin + ymax) / 2.0;
      if (x < xCenter && y < yCenter) {
        cs += 1L;
        xmax = xCenter;
        ymax = yCenter;
      } else if (x >= xCenter && y < yCenter) {
        cs += 1L + 1L * is(i);
        xmin = xCenter;
        ymax = yCenter;
      } else if (x < xCenter && y >= yCenter) {
        cs += 1L + 2L * is(i);
        xmax = xCenter;
        ymin = yCenter;
      } else {
        cs += 1L + 3L * is(i);
        xmin = xCenter;
        ymin = yCenter;
      }
    }
    return new SeqCell(cs, xmin, ymin);
  }

  private long is(int i) {
    return ((long) Math.pow(4, maxR - i + 1) - 1L) / 3L;
  }

  long signature(double x, double y, double w, double h,
                 double gx1, double gy1, double gx2, double gy2) {
    long signature = 0L;
    for (int i = 0; i < alpha; i++) {
      for (int j = 0; j < beta; j++) {
        double minX = x + w * i;
        double minY = y + h * j;
        if (rectsOverlap(minX, minY, minX + w, minY + h, gx1, gy1, gx2, gy2)) {
          signature |= (1L << (i * beta + j));
        }
      }
    }
    return signature;
  }

  private static boolean rectsOverlap(double ax1, double ay1, double ax2, double ay2,
                                     double bx1, double by1, double bx2, double by2) {
    return ax2 >= bx1 && ay2 >= by1 && ax1 <= bx2 && ay1 <= by2;
  }

  public List<CodeRange> ranges(double lng1, double lat1, double lng2, double lat2) {
    QueryWindow queryWindow = new QueryWindow(
        Math.min(lng1, lng2), Math.min(lat1, lat2),
        Math.max(lng1, lng2), Math.max(lat1, lat2));
    List<CodeRange> ranges = new ArrayList<CodeRange>(100);
    ArrayDeque<TShapeEE> remaining = new ArrayDeque<TShapeEE>(200);
    TShapeEE levelStop = new TShapeEE(-1, -1, -1, -1, -1, -1);
    root.split();
    remaining.addAll(root.children);
    remaining.add(levelStop);
    short level = 1;
    while (!remaining.isEmpty()) {
      TShapeEE next = remaining.poll();
      if (next.level < 0) {
        if (!remaining.isEmpty() && level < maxR) {
          level = (short) (level + 1);
          remaining.add(levelStop);
        }
      } else if (next.isContained(queryWindow)) {
        // LocSIndex: location << (alpha*beta), not CellIndex's hardcoded << 8.
        long shift = shapeBits();
        long min = next.elementCode;
        long max = next.elementCode + is(level);
        ranges.add(new CodeRange(min << shift, (max << shift) - 1L, true));
      } else if (next.insertion(queryWindow)) {
        // LocSIndex non-Redis path: every shape mask i that shares bits with the
        // query signature yields score i | (elementCode << (alpha*beta)).
        long signature = next.insertSignature(queryWindow);
        long key = next.elementCode;
        long shift = shapeBits();
        int maxShape = 1 << shapeBits();
        for (int i = 1; i < maxShape; i++) {
          if ((signature & i) != 0) {
            long index = ((long) i) | (key << shift);
            ranges.add(new CodeRange(index, index, true));
          }
        }
        if (level < maxR) {
          next.split();
          remaining.addAll(next.children);
        }
      }
    }
    return ranges;
  }

  private final class QueryWindow {
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

    boolean insertion(double x1, double y1, double x2, double y2) {
      return xmax >= x1 && ymax >= y1 && xmin <= x2 && ymin <= y2;
    }
  }

  private final class TShapeEE {
    final double xmin;
    final double ymin;
    final double xmax;
    final double ymax;
    final int level;
    final long elementCode;
    final double xLength;
    final double yLength;
    final double exMax;
    final double eyMax;
    final List<TShapeEE> children = new ArrayList<TShapeEE>(4);

    TShapeEE(double xmin, double ymin, double xmax, double ymax, int level, long elementCode) {
      this.xmin = xmin;
      this.ymin = ymin;
      this.xmax = xmax;
      this.ymax = ymax;
      this.level = level;
      this.elementCode = elementCode;
      this.xLength = xmax - xmin;
      this.yLength = ymax - ymin;
      this.exMax = xmin + xLength * alpha;
      this.eyMax = ymin + yLength * beta;
    }

    boolean insertion(QueryWindow window) {
      return window.xmax >= xmin && window.ymax >= ymin
          && window.xmin <= exMax && window.ymin <= eyMax;
    }

    boolean isContained(QueryWindow window) {
      return window.xmin <= xmin && window.ymin <= ymin
          && window.xmax >= exMax && window.ymax >= eyMax;
    }

    long insertSignature(QueryWindow window) {
      long signature = 0L;
      int xs = Math.max(0, (int) Math.floor((window.xmin - xmin) / xLength));
      int xe = Math.min((int) Math.floor((window.xmax - xmin) / xLength) + 1, alpha);
      int ys = Math.max(0, (int) Math.floor((window.ymin - ymin) / yLength));
      int ye = Math.min((int) Math.floor((window.ymax - ymin) / yLength) + 1, beta);
      for (int i = xs; i < xe; i++) {
        for (int j = ys; j < ye; j++) {
          double minX = xmin + xLength * i;
          double minY = ymin + yLength * j;
          if (window.insertion(minX, minY, minX + xLength, minY + yLength)) {
            signature |= (1L << (i * beta + j));
          }
        }
      }
      return signature;
    }

    void split() {
      if (!children.isEmpty()) {
        return;
      }
      double xCenter = (xmax + xmin) / 2.0;
      double yCenter = (ymax + ymin) / 2.0;
      long intervalSize = ((long) Math.pow(4, maxR - level) - 1L) / 3L;
      children.add(new TShapeEE(xmin, ymin, xCenter, yCenter, level + 1, elementCode + 1L));
      children.add(new TShapeEE(xCenter, ymin, xmax, yCenter, level + 1,
          elementCode + 1L + 1L * intervalSize));
      children.add(new TShapeEE(xmin, yCenter, xCenter, ymax, level + 1,
          elementCode + 1L + 2L * intervalSize));
      children.add(new TShapeEE(xCenter, yCenter, xmax, ymax, level + 1,
          elementCode + 1L + 3L * intervalSize));
    }
  }
}
