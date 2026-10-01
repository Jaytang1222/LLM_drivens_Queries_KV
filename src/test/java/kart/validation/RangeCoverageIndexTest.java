package kart.validation;

import kart.compile.ScanTask;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RangeCoverageIndexTest {

  @Test
  void nestedIntervalCoveredByEarlierLongRange() {
    // start=0x10.. short later range; earlier long range covers probe inside short gap.
    List<ScanTask> tasks = new ArrayList<ScanTask>();
    tasks.add(task("t", hex(new byte[]{0x10}), hex(new byte[]{0x50}))); // long
    tasks.add(task("t", hex(new byte[]{0x20}), hex(new byte[]{0x30}))); // nested short
    ValidatorTiming timing = new ValidatorTiming();
    List<RangeCoverageIndex.DecodedRange> decoded = RangeCoverageIndex.decodeAll(tasks, timing);
    RangeCoverageIndex idx = RangeCoverageIndex.buildForTable(decoded, "t", timing);
    byte[] probe = new byte[]{0x40}; // outside short, inside long
    assertTrue(idx.covers(probe));
    assertTrue(RangeCoverageIndex.coveredByLegacy(probe, tasks, "t", null));
    assertEquals(timing.range_decode_count, 2L); // one decode pass
  }

  @Test
  void stopExclusiveBoundary() {
    List<ScanTask> tasks = new ArrayList<ScanTask>();
    tasks.add(task("t", hex(new byte[]{0x10}), hex(new byte[]{0x20})));
    RangeCoverageIndex idx = RangeCoverageIndex.buildForTable(
        RangeCoverageIndex.decodeAll(tasks, null), "t", null);
    assertTrue(idx.covers(new byte[]{0x10}));
    assertTrue(idx.covers(new byte[]{0x1F}));
    assertFalse(idx.covers(new byte[]{0x20}));
    assertFalse(idx.covers(new byte[]{0x0F}));
  }

  @Test
  void randomProbesMatchLegacy() {
    List<ScanTask> tasks = new ArrayList<ScanTask>();
    tasks.add(task("t", hex(new byte[]{0x01, 0x00}), hex(new byte[]{0x01, (byte) 0x80})));
    tasks.add(task("t", hex(new byte[]{0x01, 0x40}), hex(new byte[]{0x02, 0x00})));
    tasks.add(task("t", hex(new byte[]{0x03, 0x00}), hex(new byte[]{0x03, 0x10})));
    RangeCoverageIndex idx = RangeCoverageIndex.buildForTable(
        RangeCoverageIndex.decodeAll(tasks, null), "t", null);
    for (int a = 0; a < 4; a++) {
      for (int b = 0; b < 256; b += 17) {
        byte[] probe = new byte[]{(byte) a, (byte) b};
        assertEquals(
            RangeCoverageIndex.coveredByLegacy(probe, tasks, "t", null),
            idx.covers(probe),
            "probe=" + a + "," + b);
      }
    }
  }

  private static ScanTask task(String table, String startHex, String stopHex) {
    ScanTask t = new ScanTask();
    t.table = table;
    t.startHex = startHex;
    t.stopHex = stopHex;
    t.shard = Integer.parseInt(startHex.substring(0, 2), 16);
    return t;
  }

  private static String hex(byte[] b) {
    return ScanTask.toHex(b);
  }
}
