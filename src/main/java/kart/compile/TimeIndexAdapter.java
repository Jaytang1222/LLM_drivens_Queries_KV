package kart.compile;

import kart.codec.Bytes;
import kart.codec.PrefixSuccessor;
import kart.codec.RowKeyCodec;
import kart.codec.TimeBucket;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Emits idx_time scan tasks covering a half-open time interval [startMs, endMs).
 */
public final class TimeIndexAdapter {

  private final LayoutContext layout;

  public TimeIndexAdapter(LayoutContext layout) {
    this.layout = layout;
  }

  public List<ScanTask> scanTasks(long startMs, long endMs, String sourceNodeId) {
    List<ScanTask> out = new ArrayList<ScanTask>();
    long[] buckets = TimeBucket.bucketsCovering(startMs, endMs, layout.epochMs, layout.bucketMs);
    for (int shard = 0; shard < layout.shardCount; shard++) {
      for (long b : buckets) {
        byte[] start = RowKeyCodec.encodeTime(shard, b, 0, 0);
        byte[] stop;
        if (b + 1 != 0) { // unsigned overflow guard
          stop = RowKeyCodec.encodeTime(shard, b + 1, 0, 0);
        } else {
          Optional<byte[]> succ = PrefixSuccessor.of(Bytes.concat(Bytes.u8(shard), Bytes.u64(b)));
          if (!succ.isPresent()) {
            throw new IllegalStateException("no prefix successor for shard=" + shard + " bucket=" + b);
          }
          stop = succ.get();
        }
        ScanTask t = new ScanTask(layout.tableTime, start, stop, sourceNodeId, shard, "bucket:" + b);
        out.add(t);
      }
    }
    return out;
  }
}
