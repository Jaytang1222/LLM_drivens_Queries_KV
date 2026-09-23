package kart.snapshot;

import kart.catalog.StatsSnapshot;
import kart.codec.TimeBucket;
import kart.codec.ZOrder;
import kart.data.Chunk;
import kart.data.Chunker;
import kart.data.Trajectory;
import org.apache.commons.codec.digest.MurmurHash3;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Build StatsSnapshot: posting counts, histograms, 1% consistent sample.
 */
public final class StatsBuilder {

  private final IndexBuilders.LayoutParams layout;
  private final Chunker chunker;

  public StatsBuilder(IndexBuilders.LayoutParams layout) {
    this.layout = layout;
    this.chunker = new Chunker(256);
  }

  public StatsSnapshot build(String manifestId, List<Trajectory> trajectories) {
    StatsSnapshot s = new StatsSnapshot();
    s.manifest_id = manifestId;
    s.stats_version = "stats_v1";
    s.sample_rate = 0.01;

    int n = trajectories.size();
    long t0 = System.currentTimeMillis();
    System.out.println("STATS_PHASE=build trajectories=" + n);
    System.out.flush();
    int i = 0;
    for (Trajectory t : trajectories) {
      i++;
      String lenKey = bucketKey(t.size());
      Long prev = s.traj_length_hist.get(lenKey);
      s.traj_length_hist.put(lenKey, prev == null ? 1L : prev + 1);
      List<Chunk> chunks = chunker.chunk(t);
      for (Chunk c : chunks) {
        String sz = bucketKey(c.pointCount);
        Long p = s.chunk_size_hist.get(sz);
        s.chunk_size_hist.put(sz, p == null ? 1L : p + 1);

        long b0 = TimeBucket.bucketOf(c.tMinMs, layout.epochMs, layout.bucketMs);
        long b1 = TimeBucket.bucketOf(c.tMaxMs, layout.epochMs, layout.bucketMs);
        List<Long> timeBuckets = new ArrayList<Long>();
        for (long b = b0; b <= b1; b++) {
          timeBuckets.add(b);
          String bk = Long.toString(b);
          Long cnt = s.time_bucket_posting_counts.get(bk);
          s.time_bucket_posting_counts.put(bk, cnt == null ? 1L : cnt + 1);
        }

        ZOrder.CellRect cells = ZOrder.metersToCells(
            c.minX, c.minY, c.maxX, c.maxY,
            layout.domain.minX, layout.domain.minY, layout.domain.maxX, layout.domain.maxY,
            layout.zorderLevel);
        List<Long> zCells = new ArrayList<Long>();
        for (int cx = cells.cxMin; cx <= cells.cxMax; cx++) {
          for (int cy = cells.cyMin; cy <= cells.cyMax; cy++) {
            long z = ZOrder.interleave(cx, cy, layout.zorderLevel);
            zCells.add(z);
            String zk = Long.toString(z);
            Long cnt = s.zorder_cell_posting_counts.get(zk);
            s.zorder_cell_posting_counts.put(zk, cnt == null ? 1L : cnt + 1);
          }
        }

        if (sample(t.tid, c.chunkId)) {
          StatsSnapshot.SampleChunk sc = new StatsSnapshot.SampleChunk();
          sc.tid = t.tid;
          sc.chunk_id = c.chunkId;
          sc.time_buckets = timeBuckets;
          sc.z_cells = zCells;
          s.sample_chunks.add(sc);
        }
      }
      if (i == 1 || i % 500 == 0 || i == n) {
        long elapsed = System.currentTimeMillis() - t0;
        double rate = i / Math.max(0.001, elapsed / 1000.0);
        long etaSec = (long) ((n - i) / Math.max(0.001, rate));
        System.out.println("STATS_PROGRESS phase=build done=" + i + "/" + n
            + " samples=" + s.sample_chunks.size()
            + " time_buckets=" + s.time_bucket_posting_counts.size()
            + " z_cells=" + s.zorder_cell_posting_counts.size()
            + " elapsed_s=" + (elapsed / 1000)
            + " rate_traj_s=" + String.format("%.2f", rate)
            + " eta_s=" + etaSec);
        System.out.flush();
      }
    }
    System.out.println("STATS_PHASE=done samples=" + s.sample_chunks.size()
        + " elapsed_s=" + ((System.currentTimeMillis() - t0) / 1000));
    System.out.flush();
    return s;
  }

  /** murmur(tid,chunk) mod 1000 < 10 → ~1%. */
  static boolean sample(long tid, int chunkId) {
    byte[] data = ByteBuffer.allocate(12).putLong(tid).putInt(chunkId).array();
    long[] h = MurmurHash3.hash128x64(data);
    long mod = Long.remainderUnsigned(h[0], 1000L);
    return mod < 10;
  }

  private static String bucketKey(int n) {
    if (n <= 1) {
      return "1";
    }
    if (n <= 16) {
      return "2-16";
    }
    if (n <= 64) {
      return "17-64";
    }
    if (n <= 256) {
      return "65-256";
    }
    return "257+";
  }
}
