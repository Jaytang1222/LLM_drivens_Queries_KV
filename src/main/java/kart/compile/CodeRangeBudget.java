package kart.compile;

import kart.codec.CodeRange;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.TreeMap;

/**
 * Shared precise-range merge and membership for ADV index adapters.
 * Physical scans may use coarsened ranges; postings are filtered by precise ranges.
 */
public final class CodeRangeBudget {

  private CodeRangeBudget() {}

  /** Sort by lo and merge overlapping / abutting inclusive ranges. */
  public static List<CodeRange> mergeOverlapping(List<CodeRange> ranges) {
    if (ranges == null || ranges.isEmpty()) {
      return Collections.emptyList();
    }
    List<CodeRange> sorted = new ArrayList<CodeRange>(ranges);
    Collections.sort(sorted, BY_LO);
    List<CodeRange> out = new ArrayList<CodeRange>(sorted.size());
    CodeRange cur = sorted.get(0);
    for (int i = 1; i < sorted.size(); i++) {
      CodeRange n = sorted.get(i);
      if (overlapsOrAbuts(cur.hi, n.lo)) {
        cur = new CodeRange(cur.lo, Math.max(cur.hi, n.hi), cur.contained && n.contained);
      } else {
        out.add(cur);
        cur = n;
      }
    }
    out.add(cur);
    return out;
  }

  /**
   * Coarsen to at most {@code maxRanges} by repeatedly merging the adjacent pair
   * with the smallest gap cost (extra codes in {@code (a.hi, b.lo)}).
   */
  public static List<CodeRange> mergeToBudgetGapAware(List<CodeRange> ranges, int maxRanges) {
    return mergeToBudgetGapAware(ranges, maxRanges, null);
  }

  /**
   * @param gapPostings optional; when non-null, {@code gapPostings.cost(gapLo, gapHi)}
   *                    estimates postings in the inclusive gap; otherwise gap length is used.
   */
  public static List<CodeRange> mergeToBudgetGapAware(List<CodeRange> ranges, int maxRanges,
                                                     GapCost gapPostings) {
    if (maxRanges <= 0) {
      throw new IllegalArgumentException("maxRanges must be > 0");
    }
    List<CodeRange> merged = mergeOverlapping(ranges);
    if (merged.size() <= maxRanges) {
      return merged;
    }
    List<Node> nodes = new ArrayList<Node>(merged.size());
    for (int i = 0; i < merged.size(); i++) {
      Node n = new Node(merged.get(i));
      if (!nodes.isEmpty()) {
        Node prev = nodes.get(nodes.size() - 1);
        prev.next = n;
        n.prev = prev;
      }
      nodes.add(n);
    }
    PriorityQueue<Gap> gaps = new PriorityQueue<Gap>(Math.max(1, nodes.size() - 1), GAP_ORDER);
    for (int i = 0; i + 1 < nodes.size(); i++) {
      gaps.add(new Gap(nodes.get(i), nodes.get(i + 1), gapPostings));
    }
    int size = nodes.size();
    while (size > maxRanges) {
      Gap best = gaps.poll();
      while (best != null && !best.isCurrent()) {
        best = gaps.poll();
      }
      if (best == null) {
        throw new IllegalStateException("no adjacent code range available to merge");
      }
      Node left = best.left;
      Node right = best.right;
      Node after = right.next;
      left.range = new CodeRange(left.range.lo, right.range.hi,
          left.range.contained && right.range.contained);
      left.version++;
      left.next = after;
      if (after != null) {
        after.prev = left;
        gaps.add(new Gap(left, after, gapPostings));
      }
      right.active = false;
      right.version++;
      right.prev = null;
      right.next = null;
      size--;
    }
    List<CodeRange> out = new ArrayList<CodeRange>(size);
    for (Node n = nodes.get(0); n != null; n = n.next) {
      if (n.active) {
        out.add(n.range);
      }
    }
    return out;
  }

  private static final class Node {
    CodeRange range;
    Node prev;
    Node next;
    long version;
    boolean active = true;

    Node(CodeRange range) {
      this.range = range;
    }
  }

  private static final class Gap {
    final Node left;
    final Node right;
    final long leftVersion;
    final long cost;

    Gap(Node left, Node right, GapCost gapPostings) {
      this.left = left;
      this.right = right;
      this.leftVersion = left.version;
      this.cost = gapCost(left.range, right.range, gapPostings);
    }

    boolean isCurrent() {
      return left.active && right.active && left.next == right
          && left.version == leftVersion;
    }
  }

  private static final Comparator<Gap> GAP_ORDER = new Comparator<Gap>() {
    @Override
    public int compare(Gap a, Gap b) {
      int c = Long.compare(a.cost, b.cost);
      if (c != 0) {
        return c;
      }
      c = Long.compare(a.left.range.lo, b.left.range.lo);
      if (c != 0) {
        return c;
      }
      return Long.compare(a.right.range.lo, b.right.range.lo);
    }
  };

  /** Binary search: whether {@code code} lies in any inclusive range of a sorted disjoint list. */
  public static boolean containsCode(List<CodeRange> sortedDisjoint, long code) {
    if (sortedDisjoint == null || sortedDisjoint.isEmpty()) {
      return true;
    }
    int lo = 0;
    int hi = sortedDisjoint.size() - 1;
    while (lo <= hi) {
      int mid = (lo + hi) >>> 1;
      CodeRange r = sortedDisjoint.get(mid);
      if (code < r.lo) {
        hi = mid - 1;
      } else if (code > r.hi) {
        lo = mid + 1;
      } else {
        return true;
      }
    }
    return false;
  }

  public interface GapCost {
    /** Estimated extra postings for codes in the inclusive interval {@code [gapLo, gapHi]}. */
    long cost(long gapLo, long gapHi);
  }

  /**
   * Build an O(log n) posting-count lookup for choosing which SFC gaps to coarsen.
   * The input map is the snapshot's code → exact posting-count histogram. Returns
   * {@code null} for absent/invalid histograms so callers can retain code-length fallback.
   */
  public static GapCost postingGapCost(Map<String, Long> codePostings) {
    if (codePostings == null || codePostings.isEmpty()) {
      return null;
    }
    TreeMap<Long, Long> sorted = new TreeMap<Long, Long>();
    for (Map.Entry<String, Long> e : codePostings.entrySet()) {
      if (e.getKey() == null || e.getValue() == null || e.getValue().longValue() <= 0L) {
        continue;
      }
      try {
        long code = Long.parseLong(e.getKey());
        Long prior = sorted.get(Long.valueOf(code));
        long count = e.getValue().longValue();
        sorted.put(Long.valueOf(code), Long.valueOf(prior == null
            ? count : saturatingAdd(prior.longValue(), count)));
      } catch (NumberFormatException ignored) {
        // Ignore malformed histogram keys; the remaining entries stay useful.
      }
    }
    if (sorted.isEmpty()) {
      return null;
    }
    final long[] codes = new long[sorted.size()];
    final long[] prefix = new long[sorted.size() + 1];
    int i = 0;
    for (Map.Entry<Long, Long> e : sorted.entrySet()) {
      codes[i] = e.getKey().longValue();
      prefix[i + 1] = saturatingAdd(prefix[i], e.getValue().longValue());
      i++;
    }
    return new GapCost() {
      @Override
      public long cost(long gapLo, long gapHi) {
        if (gapHi < gapLo) {
          return 0L;
        }
        int from = lowerBound(codes, gapLo);
        int to = upperBound(codes, gapHi);
        if (from == to) {
          return 0L;
        }
        return prefix[to] - prefix[from];
      }
    };
  }

  private static int lowerBound(long[] values, long target) {
    int lo = 0;
    int hi = values.length;
    while (lo < hi) {
      int mid = (lo + hi) >>> 1;
      if (values[mid] < target) {
        lo = mid + 1;
      } else {
        hi = mid;
      }
    }
    return lo;
  }

  private static int upperBound(long[] values, long target) {
    int lo = 0;
    int hi = values.length;
    while (lo < hi) {
      int mid = (lo + hi) >>> 1;
      if (values[mid] <= target) {
        lo = mid + 1;
      } else {
        hi = mid;
      }
    }
    return lo;
  }

  private static long saturatingAdd(long a, long b) {
    return Long.MAX_VALUE - a < b ? Long.MAX_VALUE : a + b;
  }

  private static long gapCost(CodeRange a, CodeRange b, GapCost gapPostings) {
    if (overlapsOrAbuts(a.hi, b.lo)) {
      return 0L;
    }
    long gapLo = a.hi + 1L;
    long gapHi = b.lo - 1L;
    if (gapPostings != null) {
      long c = gapPostings.cost(gapLo, gapHi);
      return c >= 0L ? c : gapLength(gapLo, gapHi);
    }
    return gapLength(gapLo, gapHi);
  }

  private static boolean overlapsOrAbuts(long leftHi, long rightLo) {
    return rightLo <= leftHi
        || (leftHi != Long.MAX_VALUE && rightLo == leftHi + 1L);
  }

  private static long gapLength(long gapLo, long gapHi) {
    if (gapHi < gapLo) {
      return 0L;
    }
    long len = gapHi - gapLo + 1L;
    return len < 0L ? Long.MAX_VALUE : len;
  }

  private static final Comparator<CodeRange> BY_LO = new Comparator<CodeRange>() {
    @Override
    public int compare(CodeRange a, CodeRange b) {
      int c = Long.compare(a.lo, b.lo);
      return c != 0 ? c : Long.compare(a.hi, b.hi);
    }
  };
}
