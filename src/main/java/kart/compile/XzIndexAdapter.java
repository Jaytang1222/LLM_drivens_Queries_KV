package kart.compile;

import kart.codec.Bytes;
import kart.codec.CodeRange;
import kart.codec.PrefixSuccessor;
import kart.codec.RowKeyCodec;
import kart.geo.Rect;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Emits idx_xz scan tasks covering a query rectangle (XZ*). */
public final class XzIndexAdapter {

  private final LayoutContext layout;

  public XzIndexAdapter(LayoutContext layout) {
    this.layout = layout;
  }

  public List<ScanTask> scanTasks(Rect query, String sourceNodeId) {
    return scanTasks(query, sourceNodeId, layout.maxXzRanges);
  }

  public List<ScanTask> scanTasks(Rect query, String sourceNodeId, int maxRanges) {
    List<CodeRange> ranges = layout.xzSfc().ranges(query.minX, query.minY, query.maxX, query.maxY);
    if (ranges.size() > maxRanges) {
      ranges = mergeToBudget(ranges, maxRanges);
    }
    List<ScanTask> out = new ArrayList<ScanTask>();
    for (int shard = 0; shard < layout.shardCount; shard++) {
      for (CodeRange r : ranges) {
        byte[] start = RowKeyCodec.encodeXz(shard, r.lo, 0, 0);
        byte[] stop;
        if (r.hi + 1 != 0) {
          stop = RowKeyCodec.encodeXz(shard, r.hi + 1, 0, 0);
        } else {
          Optional<byte[]> succ = PrefixSuccessor.of(Bytes.concat(Bytes.u8(shard), Bytes.u64(r.hi)));
          if (!succ.isPresent()) {
            throw new IllegalStateException("no prefix successor for xz shard=" + shard);
          }
          stop = succ.get();
        }
        out.add(new ScanTask(layout.tableXz, start, stop, sourceNodeId, shard,
            "xz:[" + r.lo + "," + r.hi + "]"));
      }
    }
    return out;
  }

  private static List<CodeRange> mergeToBudget(List<CodeRange> ranges, int maxRanges) {
    List<CodeRange> cur = new ArrayList<CodeRange>(ranges);
    Collections.sort(cur, new Comparator<CodeRange>() {
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
          next.add(new CodeRange(Math.min(a.lo, b.lo), Math.max(a.hi, b.hi),
              a.contained && b.contained));
        }
      }
      cur = next;
    }
    return cur;
  }
}
