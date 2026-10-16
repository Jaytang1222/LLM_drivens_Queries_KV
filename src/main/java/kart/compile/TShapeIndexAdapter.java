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

/** Emits idx_tshape scan tasks covering a query rectangle. */
public final class TShapeIndexAdapter {

  /**
   * Apply the optional scan-task cap for TShape. The configured value may only
   * lower the plan's requested limit; it can never widen a plan beyond its
   * declared or layout-level range budget.
   */
  public static int configuredMaxRanges(int requestedMaxRanges) {
    return boundedMaxRanges(requestedMaxRanges, System.getenv("KART_TSHAPE_MAX_RANGES"));
  }

  static int boundedMaxRanges(int requestedMaxRanges, String configured) {
    int requested = Math.max(1, requestedMaxRanges);
    if (configured == null || configured.trim().isEmpty()) {
      return requested;
    }
    try {
      int cap = Integer.parseInt(configured.trim());
      return cap > 0 ? Math.min(requested, cap) : requested;
    } catch (NumberFormatException ignored) {
      return requested;
    }
  }

  private final LayoutContext layout;

  public TShapeIndexAdapter(LayoutContext layout) {
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
      raw.addAll(layout.tshapeSfc().ranges(query.minX, query.minY, query.maxX, query.maxY));
    }
    List<CodeRange> precise = XzStarSfc.fillInternalGaps(
        CodeRangeBudget.mergeOverlapping(raw));
    List<CodeRange> physical = CodeRangeBudget.mergeToBudgetGapAware(
        precise, maxRanges, layout.tshapePostingGapCost());
    List<CodeRange> filter = Collections.unmodifiableList(precise);
    List<ScanTask> out = new ArrayList<ScanTask>();
    for (int shard = 0; shard < layout.shardCount; shard++) {
      for (CodeRange r : physical) {
        byte[] start = RowKeyCodec.encodeTShape(shard, r.lo, 0, 0);
        byte[] stop;
        if (r.hi + 1 != 0) {
          stop = RowKeyCodec.encodeTShape(shard, r.hi + 1, 0, 0);
        } else {
          Optional<byte[]> succ = PrefixSuccessor.of(Bytes.concat(Bytes.u8(shard), Bytes.u64(r.hi)));
          if (!succ.isPresent()) {
            throw new IllegalStateException("no prefix successor for tshape shard=" + shard);
          }
          stop = succ.get();
        }
        ScanTask t = new ScanTask(layout.tableTshape, start, stop, sourceNodeId, shard,
            "ts:[" + r.lo + "," + r.hi + "]");
        t.preciseFilter = filter;
        out.add(t);
      }
    }
    return out;
  }
}
