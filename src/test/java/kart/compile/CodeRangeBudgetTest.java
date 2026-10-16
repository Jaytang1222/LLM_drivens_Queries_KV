package kart.compile;

import kart.codec.CodeRange;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public final class CodeRangeBudgetTest {

  @Test
  void mergeOverlappingCollapsesAbutting() {
    List<CodeRange> in = Arrays.asList(
        new CodeRange(10, 20, true),
        new CodeRange(21, 30, true),
        new CodeRange(100, 110, false));
    List<CodeRange> out = CodeRangeBudget.mergeOverlapping(in);
    assertEquals(2, out.size());
    assertEquals(10L, out.get(0).lo);
    assertEquals(30L, out.get(0).hi);
    assertEquals(100L, out.get(1).lo);
  }

  @Test
  void mergeOverlappingAtMaxLongDoesNotOverflowAdjacencyCheck() {
    List<CodeRange> in = Arrays.asList(
        new CodeRange(Long.MAX_VALUE - 1L, Long.MAX_VALUE, true),
        new CodeRange(Long.MAX_VALUE, Long.MAX_VALUE, true));
    List<CodeRange> out = CodeRangeBudget.mergeOverlapping(in);
    assertEquals(1, out.size());
    assertEquals(Long.MAX_VALUE - 1L, out.get(0).lo);
    assertEquals(Long.MAX_VALUE, out.get(0).hi);
  }

  @Test
  void gapAwarePrefersSmallGap() {
    // [0,10] gap 5codes [16,20]  vs large gap to [1000,1010]
    List<CodeRange> in = new ArrayList<CodeRange>();
    in.add(new CodeRange(0, 10, true));
    in.add(new CodeRange(16, 20, true));
    in.add(new CodeRange(1000, 1010, true));
    List<CodeRange> out = CodeRangeBudget.mergeToBudgetGapAware(in, 2);
    assertEquals(2, out.size());
    assertEquals(0L, out.get(0).lo);
    assertEquals(20L, out.get(0).hi);
    assertEquals(1000L, out.get(1).lo);
  }

  @Test
  void gapAwareTieMergesLeftMostPairFirst() {
    List<CodeRange> in = Arrays.asList(
        new CodeRange(0, 10, true),
        new CodeRange(20, 30, true),
        new CodeRange(40, 50, true));
    List<CodeRange> out = CodeRangeBudget.mergeToBudgetGapAware(in, 2);
    assertEquals(2, out.size());
    assertEquals(0L, out.get(0).lo);
    assertEquals(30L, out.get(0).hi);
    assertEquals(40L, out.get(1).lo);
    assertEquals(50L, out.get(1).hi);
  }

  @Test
  void priorityQueueMergeMatchesReferenceForPostingWeightedGaps() {
    List<CodeRange> in = new ArrayList<CodeRange>();
    for (int i = 0; i < 120; i++) {
      long lo = i * 11L;
      in.add(new CodeRange(lo, lo + (i % 3), true));
    }
    CodeRangeBudget.GapCost weights = new CodeRangeBudget.GapCost() {
      @Override
      public long cost(long loExclusive, long hiExclusive) {
        return (loExclusive * 31L + hiExclusive * 17L) % 97L;
      }
    };
    for (int budget : new int[]{80, 32, 1}) {
      List<CodeRange> expected = referenceMerge(in, budget, weights);
      List<CodeRange> actual = CodeRangeBudget.mergeToBudgetGapAware(in, budget, weights);
      assertRangesEqual(expected, actual);
    }
  }

  @Test
  void postingHistogramPrefersSparseGapOverShortButDenseGap() {
    List<CodeRange> in = Arrays.asList(
        new CodeRange(0, 0, true),
        new CodeRange(10, 10, true),
        new CodeRange(20, 20, true));
    Map<String, Long> counts = new HashMap<String, Long>();
    for (int code = 1; code < 10; code++) {
      counts.put(Integer.toString(code), Long.valueOf(100L));
    }

    CodeRangeBudget.GapCost gapCost = CodeRangeBudget.postingGapCost(counts);
    assertEquals(900L, gapCost.cost(1L, 9L));
    assertEquals(0L, gapCost.cost(11L, 19L));

    List<CodeRange> out = CodeRangeBudget.mergeToBudgetGapAware(in, 2, gapCost);
    assertEquals(2, out.size());
    assertEquals(0L, out.get(0).lo);
    assertEquals(0L, out.get(0).hi);
    assertEquals(10L, out.get(1).lo);
    assertEquals(20L, out.get(1).hi);
  }

  private static List<CodeRange> referenceMerge(List<CodeRange> ranges, int budget,
                                                CodeRangeBudget.GapCost gapCost) {
    List<CodeRange> cur = CodeRangeBudget.mergeOverlapping(ranges);
    while (cur.size() > budget) {
      int best = 0;
      long bestCost = Long.MAX_VALUE;
      for (int i = 0; i < cur.size() - 1; i++) {
        CodeRange a = cur.get(i);
        CodeRange b = cur.get(i + 1);
        long cost = gapCost.cost(a.hi + 1L, b.lo - 1L);
        if (cost < bestCost || (cost == bestCost && a.lo < cur.get(best).lo)) {
          bestCost = cost;
          best = i;
        }
      }
      CodeRange a = cur.get(best);
      CodeRange b = cur.get(best + 1);
      List<CodeRange> next = new ArrayList<CodeRange>(cur.size() - 1);
      for (int i = 0; i < cur.size(); i++) {
        if (i == best) {
          next.add(new CodeRange(a.lo, b.hi, a.contained && b.contained));
          i++;
        } else {
          next.add(cur.get(i));
        }
      }
      cur = next;
    }
    return cur;
  }

  private static void assertRangesEqual(List<CodeRange> expected, List<CodeRange> actual) {
    assertEquals(expected.size(), actual.size());
    for (int i = 0; i < expected.size(); i++) {
      assertEquals(expected.get(i).lo, actual.get(i).lo, "range lo at " + i);
      assertEquals(expected.get(i).hi, actual.get(i).hi, "range hi at " + i);
      assertEquals(expected.get(i).contained, actual.get(i).contained,
          "range containment at " + i);
    }
  }

  @Test
  void containsCodeBinarySearch() {
    List<CodeRange> r = CodeRangeBudget.mergeOverlapping(Arrays.asList(
        new CodeRange(100, 110, true),
        new CodeRange(900, 910, true)));
    assertTrue(CodeRangeBudget.containsCode(r, 105L));
    assertTrue(CodeRangeBudget.containsCode(r, 900L));
    assertFalse(CodeRangeBudget.containsCode(r, 500L));
    assertFalse(CodeRangeBudget.containsCode(r, 911L));
  }
}
