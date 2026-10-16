package kart.cost;

import kart.codec.CodeRange;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

public final class CodePostingIndexTest {

  @Test
  void sortedSumMatchesBruteForceAndDedupsOverlaps() {
    Map<String, Long> counts = new HashMap<String, Long>();
    for (long c = 0; c < 10_000; c++) {
      counts.put(Long.toString(c), c % 7 + 1);
    }
    List<CodeRange> ranges = new ArrayList<CodeRange>();
    ranges.add(new CodeRange(100, 250, true));
    ranges.add(new CodeRange(200, 400, true)); // overlaps previous
    ranges.add(new CodeRange(5000, 5010, false));
    ranges.add(new CodeRange(9000, 8990, true)); // invalid lo>hi ignored by merge

    long brute = 0L;
    for (Map.Entry<String, Long> e : counts.entrySet()) {
      long code = Long.parseLong(e.getKey());
      if (inAny(code, ranges)) {
        brute += e.getValue().longValue();
      }
    }
    CostFeaturesExtractor.SortedCodeCounts sc =
        CostFeaturesExtractor.SortedCodeCounts.from(counts);
    long fast = CostFeaturesExtractor.sumSortedCodeRanges(sc, ranges);
    assertEquals(brute, fast);
  }

  @Test
  void prefixSumUsesInclusiveBoundsAndCollapsesEquivalentNumericKeys() {
    Map<String, Long> counts = new HashMap<String, Long>();
    counts.put("10", 2L);
    counts.put("010", 3L); // Same numeric code; the index collapses duplicate spellings.
    counts.put("11", 7L);
    counts.put("13", 13L);
    List<CodeRange> ranges = new ArrayList<CodeRange>();
    ranges.add(new CodeRange(10L, 10L, true));
    ranges.add(new CodeRange(11L, 12L, true));
    ranges.add(new CodeRange(13L, 13L, true));

    CostFeaturesExtractor.SortedCodeCounts sc =
        CostFeaturesExtractor.SortedCodeCounts.from(counts);
    assertEquals(25L, CostFeaturesExtractor.sumSortedCodeRanges(sc, ranges));
  }

  @Test
  void maxCodeEndpointDoesNotDoubleCountOverlappingRanges() {
    Map<String, Long> counts = new HashMap<String, Long>();
    counts.put(Long.toString(Long.MAX_VALUE - 1L), 3L);
    counts.put(Long.toString(Long.MAX_VALUE), 5L);
    List<CodeRange> ranges = new ArrayList<CodeRange>();
    ranges.add(new CodeRange(Long.MAX_VALUE - 1L, Long.MAX_VALUE, true));
    ranges.add(new CodeRange(Long.MAX_VALUE, Long.MAX_VALUE, true));

    CostFeaturesExtractor.SortedCodeCounts sc =
        CostFeaturesExtractor.SortedCodeCounts.from(counts);
    assertEquals(8L, CostFeaturesExtractor.sumSortedCodeRanges(sc, ranges));
  }

  @Test
  void mergeThenBinaryMembershipMatchesLinear() {
    List<CodeRange> ranges = new ArrayList<CodeRange>();
    ranges.add(new CodeRange(10, 20, true));
    ranges.add(new CodeRange(15, 30, true));
    ranges.add(new CodeRange(100, 110, false));
    List<CodeRange> merged = CostFeaturesExtractor.mergeCodeRanges(ranges);
    for (long c = 0; c < 120; c++) {
      boolean linear = false;
      for (CodeRange r : ranges) {
        if (r != null && r.lo <= r.hi && c >= r.lo && c <= r.hi) {
          linear = true;
          break;
        }
      }
      // Use package helper via sum path: membership on merged equals linear cover.
      boolean bin = false;
      for (CodeRange r : merged) {
        if (r != null && c >= r.lo && c <= r.hi) {
          bin = true;
          break;
        }
      }
      assertEquals(linear, bin, "code=" + c);
    }
  }

  @Test
  void fastCostMemoReturnsSameCard() {
    FastCost fc = new FastCost(null);
    kart.ir.BoundIr ir = new kart.ir.BoundIr();
    ir.query_id = "q1";
    kart.plan.PlanEnvelope plan = kart.plan.PlanBuilder.buildForAccess(ir, false, false, false);
    CostCard a = fc.estimate(plan, ir);
    CostCard b = fc.estimate(plan, ir);
    assertEquals(a.estimated_ms, b.estimated_ms, 1e-12);
  }

  private static boolean inAny(long code, List<CodeRange> ranges) {
    for (CodeRange r : ranges) {
      if (r != null && r.lo <= r.hi && code >= r.lo && code <= r.hi) {
        return true;
      }
    }
    return false;
  }
}
