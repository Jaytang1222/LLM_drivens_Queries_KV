package kart.compile;

import kart.codec.Bytes;
import kart.codec.CodeRange;
import kart.codec.PrefixSuccessor;
import kart.codec.RowKeyCodec;
import kart.codec.XzStarSfc;

import java.util.ArrayList;
import java.util.Collections;
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
    // Span-fill for overlap recall (same hole class as spatial XZ*).
    List<CodeRange> precise = XzStarSfc.fillInternalGaps(CodeRangeBudget.mergeOverlapping(
        layout.xztCodec().rangesCovering(startMs, endMs)));
    List<CodeRange> physical = CodeRangeBudget.mergeToBudgetGapAware(
        precise, maxRanges, layout.xztPostingGapCost());
    List<CodeRange> filter = Collections.unmodifiableList(precise);
    List<ScanTask> out = new ArrayList<ScanTask>();
    for (int shard = 0; shard < layout.shardCount; shard++) {
      for (CodeRange r : physical) {
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
        ScanTask t = new ScanTask(layout.tableXzt, start, stop, sourceNodeId, shard,
            "xzt:[" + r.lo + "," + r.hi + "]");
        t.preciseFilter = filter;
        out.add(t);
      }
    }
    return out;
  }
}
