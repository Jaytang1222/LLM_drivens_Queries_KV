package kart.compile;

import kart.codec.Bytes;
import kart.codec.CodeRange;
import kart.codec.PrefixSuccessor;
import kart.codec.RowKeyCodec;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Emits idx_xzt scan tasks covering a half-open time window. */
public final class XztIndexAdapter {

  private final LayoutContext layout;

  public XztIndexAdapter(LayoutContext layout) {
    this.layout = layout;
  }

  public List<ScanTask> scanTasks(long startMs, long endMs, String sourceNodeId) {
    return scanTasks(startMs, endMs, sourceNodeId, layout.maxXzRanges);
  }

  public List<ScanTask> scanTasks(long startMs, long endMs, String sourceNodeId, int maxRanges) {
    List<CodeRange> ranges = layout.xztCodec().rangesCovering(startMs, endMs);
    if (ranges.size() > maxRanges) {
      ranges = coarsen(ranges, maxRanges);
    }
    List<ScanTask> out = new ArrayList<ScanTask>();
    for (int shard = 0; shard < layout.shardCount; shard++) {
      for (CodeRange r : ranges) {
        byte[] start = RowKeyCodec.encodeXzt(shard, r.lo, 0, 0);
        byte[] stop;
        if (r.hi + 1 != 0) {
          stop = RowKeyCodec.encodeXzt(shard, r.hi + 1, 0, 0);
        } else {
          Optional<byte[]> succ = PrefixSuccessor.of(Bytes.concat(Bytes.u8(shard), Bytes.u64(r.hi)));
          if (!succ.isPresent()) {
            throw new IllegalStateException("no prefix successor for xzt shard=" + shard);
          }
          stop = succ.get();
        }
        out.add(new ScanTask(layout.tableXzt, start, stop, sourceNodeId, shard,
            "xzt:[" + r.lo + "," + r.hi + "]"));
      }
    }
    return out;
  }

  private static List<CodeRange> coarsen(List<CodeRange> ranges, int maxRanges) {
    List<CodeRange> cur = new ArrayList<CodeRange>(ranges);
    java.util.Collections.sort(cur, new java.util.Comparator<CodeRange>() {
      @Override
      public int compare(CodeRange a, CodeRange b) {
        return Long.compare(a.lo, b.lo);
      }
    });
    while (cur.size() > maxRanges) {
      List<CodeRange> next = new ArrayList<CodeRange>();
      for (int i = 0; i < cur.size(); i += 2) {
        if (i + 1 >= cur.size()) {
          next.add(cur.get(i));
        } else {
          CodeRange a = cur.get(i);
          CodeRange b = cur.get(i + 1);
          next.add(new CodeRange(Math.min(a.lo, b.lo), Math.max(a.hi, b.hi), false));
        }
      }
      cur = next;
    }
    return cur;
  }
}
