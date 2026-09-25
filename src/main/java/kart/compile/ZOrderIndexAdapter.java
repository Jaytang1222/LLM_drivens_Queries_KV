package kart.compile;

import kart.codec.Bytes;
import kart.codec.PrefixSuccessor;
import kart.codec.RowKeyCodec;
import kart.codec.ZOrder;
import kart.geo.Rect;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Emits idx_zorder scan tasks covering a query rectangle.
 */
public final class ZOrderIndexAdapter {

  private final LayoutContext layout;

  public ZOrderIndexAdapter(LayoutContext layout) {
    this.layout = layout;
  }

  public List<ZOrder.MortonRange> mortonRanges(Rect query) {
    Rect d = layout.domain;
    ZOrder.CellRect cells = ZOrder.metersToCells(
        query.minX, query.minY, query.maxX, query.maxY,
        d.minX, d.minY, d.maxX, d.maxY, layout.zorderLevel);
    return ZOrder.decompose(cells, layout.zorderLevel, layout.maxZorderRanges);
  }

  public List<ScanTask> scanTasks(Rect query, String sourceNodeId) {
    List<ScanTask> out = new ArrayList<ScanTask>();
    List<ZOrder.MortonRange> ranges = mortonRanges(query);
    for (int shard = 0; shard < layout.shardCount; shard++) {
      for (ZOrder.MortonRange r : ranges) {
        byte[] start = RowKeyCodec.encodeZorder(shard, r.lo, 0, 0);
        byte[] stop;
        if (r.hi + 1 != 0) { // unsigned overflow guard
          stop = RowKeyCodec.encodeZorder(shard, r.hi + 1, 0, 0);
        } else {
          Optional<byte[]> succ = PrefixSuccessor.of(Bytes.concat(Bytes.u8(shard), Bytes.u64(r.hi)));
          if (!succ.isPresent()) {
            throw new IllegalStateException("no prefix successor for shard=" + shard + " z=" + r.hi);
          }
          stop = succ.get();
        }
        out.add(new ScanTask(layout.tableZorder, start, stop, sourceNodeId, shard,
            "z:[" + r.lo + "," + r.hi + "]"));
      }
    }
    return out;
  }
}
