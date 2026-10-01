package kart.validation;

import kart.codec.Bytes;
import kart.compile.ScanTask;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Per-table coverage index: decode each scan range once, then answer
 * {@code start <= probe < stop} probes in O(log N) via sorted starts + prefix max stop.
 *
 * <p>Correct for nested/overlapping intervals (unlike checking only the latest start).
 */
public final class RangeCoverageIndex {

  public static final class DecodedRange {
    public final ScanTask task;
    public final String table;
    public final int shard;
    public final byte[] start;
    public final byte[] stop;
    public final String error;

    DecodedRange(ScanTask task, String table, int shard, byte[] start, byte[] stop, String error) {
      this.task = task;
      this.table = table;
      this.shard = shard;
      this.start = start;
      this.stop = stop;
      this.error = error;
    }

    public boolean ok() {
      return error == null && start != null && stop != null;
    }
  }

  private final byte[][] starts;
  private final byte[][] prefixMaxStop;
  private final ValidatorTiming timing;

  private RangeCoverageIndex(byte[][] starts, byte[][] prefixMaxStop, ValidatorTiming timing) {
    this.starts = starts;
    this.prefixMaxStop = prefixMaxStop;
    this.timing = timing;
  }

  public static List<DecodedRange> decodeAll(List<ScanTask> tasks, ValidatorTiming timing) {
    List<DecodedRange> out = new ArrayList<DecodedRange>();
    if (tasks == null) {
      return out;
    }
    long t0 = System.nanoTime();
    for (ScanTask t : tasks) {
      if (t == null) {
        continue;
      }
      if (timing != null) {
        timing.range_decode_count++;
      }
      try {
        byte[] start = ScanTask.fromHex(t.startHex);
        byte[] stop = ScanTask.fromHex(t.stopHex);
        if (start == null || stop == null || start.length == 0 || stop.length == 0) {
          out.add(new DecodedRange(t, t.table, t.shard, start, stop, "empty_bounds"));
        } else if (Bytes.compareUnsigned(start, stop) >= 0) {
          out.add(new DecodedRange(t, t.table, t.shard, start, stop, "start_ge_stop"));
        } else {
          out.add(new DecodedRange(t, t.table, t.shard, start, stop, null));
        }
      } catch (RuntimeException e) {
        out.add(new DecodedRange(t, t.table, t.shard, null, null,
            e.getMessage() == null ? "decode_failed" : e.getMessage()));
      }
    }
    if (timing != null) {
      timing.t_range_decode_ms += Math.max(0L, (System.nanoTime() - t0) / 1_000_000L);
      timing.range_count += out.size();
    }
    return out;
  }

  public static RangeCoverageIndex buildForTable(List<DecodedRange> decoded, String table,
                                                 ValidatorTiming timing) {
    long t0 = System.nanoTime();
    List<DecodedRange> filtered = new ArrayList<DecodedRange>();
    if (decoded != null) {
      for (DecodedRange r : decoded) {
        if (r != null && r.ok() && table != null && table.equals(r.table)) {
          filtered.add(r);
        }
      }
    }
    Collections.sort(filtered, new Comparator<DecodedRange>() {
      @Override
      public int compare(DecodedRange a, DecodedRange b) {
        int c = Bytes.compareUnsigned(a.start, b.start);
        if (c != 0) {
          return c;
        }
        return Bytes.compareUnsigned(a.stop, b.stop);
      }
    });
    byte[][] starts = new byte[filtered.size()][];
    byte[][] prefixMax = new byte[filtered.size()][];
    byte[] maxStop = null;
    for (int i = 0; i < filtered.size(); i++) {
      DecodedRange r = filtered.get(i);
      starts[i] = r.start;
      if (maxStop == null || Bytes.compareUnsigned(r.stop, maxStop) > 0) {
        maxStop = r.stop;
      }
      prefixMax[i] = maxStop;
    }
    if (timing != null) {
      timing.t_coverage_index_ms += Math.max(0L, (System.nanoTime() - t0) / 1_000_000L);
    }
    return new RangeCoverageIndex(starts, prefixMax, timing);
  }

  public boolean covers(byte[] probe) {
    if (probe == null || starts.length == 0) {
      return false;
    }
    int lo = 0;
    int hi = starts.length - 1;
    int best = -1;
    while (lo <= hi) {
      int mid = (lo + hi) >>> 1;
      if (timing != null) {
        timing.range_compare_count++;
      }
      int c = Bytes.compareUnsigned(starts[mid], probe);
      if (c <= 0) {
        best = mid;
        lo = mid + 1;
      } else {
        hi = mid - 1;
      }
    }
    if (best < 0) {
      return false;
    }
    if (timing != null) {
      timing.range_compare_count++;
    }
    return Bytes.compareUnsigned(probe, prefixMaxStop[best]) < 0;
  }

  /** Legacy O(N) scan with per-call decode (for A/B scaling). */
  public static boolean coveredByLegacy(byte[] probe, List<ScanTask> tasks, String table,
                                        ValidatorTiming timing) {
    if (tasks == null) {
      return false;
    }
    for (ScanTask t : tasks) {
      if (t == null || table == null || !table.equals(t.table)) {
        continue;
      }
      if (timing != null) {
        timing.range_decode_count += 2;
        timing.range_compare_count += 2;
      }
      byte[] start = t.startBytes();
      byte[] stop = t.stopBytes();
      if (Bytes.compareUnsigned(start, probe) <= 0 && Bytes.compareUnsigned(probe, stop) < 0) {
        return true;
      }
    }
    return false;
  }
}
