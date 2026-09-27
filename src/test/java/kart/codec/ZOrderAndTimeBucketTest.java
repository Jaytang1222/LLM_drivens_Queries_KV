package kart.codec;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.DoubleRange;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ZOrderAndTimeBucketTest {

  @Test
  void interleaveDeinterleaveKnown() {
    long z = ZOrder.interleave(1, 2, 8);
    int[] xy = ZOrder.deinterleave(z, 8);
    assertEquals(1, xy[0]);
    assertEquals(2, xy[1]);
  }

  @Property(tries = 200)
  void interleaveRoundTrip(
      @ForAll @IntRange(min = 0, max = 255) int cx,
      @ForAll @IntRange(min = 0, max = 255) int cy) {
    long z = ZOrder.interleave(cx, cy, 8);
    int[] xy = ZOrder.deinterleave(z, 8);
    assertEquals(cx, xy[0]);
    assertEquals(cy, xy[1]);
  }

  @Property(tries = 100)
  void rangesCoverAllCellsAndRespectMax(
      @ForAll @IntRange(min = 0, max = 200) int cx0,
      @ForAll @IntRange(min = 0, max = 200) int cy0,
      @ForAll @IntRange(min = 0, max = 20) int w,
      @ForAll @IntRange(min = 0, max = 20) int h,
      @ForAll @IntRange(min = 1, max = 64) int maxRanges) {
    int cx1 = Math.min(255, cx0 + w);
    int cy1 = Math.min(255, cy0 + h);
    ZOrder.CellRect rect = new ZOrder.CellRect(cx0, cy0, cx1, cy1);
    List<ZOrder.MortonRange> ranges = ZOrder.decompose(rect, 8, maxRanges);
    assertTrue(ranges.size() <= maxRanges);
    assertTrue(ZOrder.coversAllCells(rect, ranges, 8));
    // adjacent merged: no overlapping/touching consecutive without merge already done
    for (int i = 1; i < ranges.size(); i++) {
      assertTrue(ranges.get(i).lo > ranges.get(i - 1).hi + 1
          || ranges.size() == maxRanges);
    }
  }

  @Property(tries = 50)
  void randomMeterRectCovered(
      @ForAll @DoubleRange(min = 0, max = 90) double minX,
      @ForAll @DoubleRange(min = 0, max = 90) double minY,
      @ForAll @DoubleRange(min = 1, max = 10) double dx,
      @ForAll @DoubleRange(min = 1, max = 10) double dy) {
    double maxX = minX + dx;
    double maxY = minY + dy;
    ZOrder.CellRect cells = ZOrder.metersToCells(minX, minY, maxX, maxY, 0, 0, 100, 100, 8);
    List<ZOrder.MortonRange> ranges = ZOrder.decompose(cells, 8, 64);
    assertTrue(ZOrder.coversAllCells(cells, ranges, 8));
  }

  @Test
  void timeBucketsHalfOpen() {
    long epoch = 0;
    long bucket = 600_000L;
    long[] bs = TimeBucket.bucketsCovering(0, 600_000L, epoch, bucket);
    assertEquals(1, bs.length);
    assertEquals(0, bs[0]);
    long[] bs2 = TimeBucket.bucketsCovering(0, 600_001L, epoch, bucket);
    assertEquals(2, bs2.length);
  }

  @Test
  void timeBucketsClampPreEpochToEmptyOrPartial() {
    long epoch = 1_201_930_200_000L;
    long bucket = 600_000L;
    // entirely before epoch (empty_far_3 / t_large_3 style)
    assertEquals(0, TimeBucket.bucketsCovering(
        epoch - 86_400_000L, epoch, epoch, bucket).length);
    assertEquals(0, TimeBucket.bucketsCovering(
        946_684_800_000L, 946_688_400_000L, epoch, bucket).length);
    // end exactly at epoch → empty
    assertEquals(0, TimeBucket.bucketsCovering(
        epoch - bucket, epoch, epoch, bucket).length);
    // straddles epoch → clamp access start to epoch (bucket 0..)
    long[] span = TimeBucket.bucketsCovering(epoch - bucket, epoch + bucket, epoch, bucket);
    assertEquals(1, span.length);
    assertEquals(0, span[0]);
    // after epoch unchanged
    long[] after = TimeBucket.bucketsCovering(epoch, epoch + bucket, epoch, bucket);
    assertEquals(1, after.length);
    assertEquals(0, after[0]);
  }

  @Test
  void fullFfPrefixEmpty() {
    assertTrue(!PrefixSuccessor.of(new byte[]{(byte) 0xFF}).isPresent());
  }
}
