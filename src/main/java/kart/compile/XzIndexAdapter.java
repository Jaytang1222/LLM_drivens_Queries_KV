package kart.compile;

import kart.codec.Bytes;
import kart.codec.CodeRange;
import kart.codec.PrefixSuccessor;
import kart.codec.RowKeyCodec;
import kart.codec.XzStarSfc;
import kart.geo.Rect;

import java.util.ArrayList;
import java.util.Collections;
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
    return scanTasks(Collections.singletonList(query), sourceNodeId, maxRanges);
  }

  public List<ScanTask> scanTasks(List<Rect> queries, String sourceNodeId, int maxRanges) {
    if (queries == null || queries.isEmpty()) {
      return Collections.emptyList();
    }
    List<CodeRange> raw = new ArrayList<CodeRange>();
    for (Rect query : queries) {
      if (query == null) {
        continue;
      }
      raw.addAll(layout.xzSfc().ranges(query.minX, query.minY, query.maxX, query.maxY));
    }
    // Span-fill holes: XZ* range enumeration can omit SFC codes of geometrically
    // overlapping MBRs; without fill, e2e oracle misses (P_XZ / P_XZTXZ).
    List<CodeRange> precise = XzStarSfc.fillInternalGaps(
        CodeRangeBudget.mergeOverlapping(raw));
    List<CodeRange> physical = CodeRangeBudget.mergeToBudgetGapAware(
        precise, maxRanges, layout.xzPostingGapCost());
    List<CodeRange> filter = Collections.unmodifiableList(precise);
    List<ScanTask> out = new ArrayList<ScanTask>();
    for (int shard = 0; shard < layout.shardCount; shard++) {
      for (CodeRange r : physical) {
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
        ScanTask t = new ScanTask(layout.tableXz, start, stop, sourceNodeId, shard,
            "xz:[" + r.lo + "," + r.hi + "]");
        t.preciseFilter = filter;
        out.add(t);
      }
    }
    return out;
  }
}
