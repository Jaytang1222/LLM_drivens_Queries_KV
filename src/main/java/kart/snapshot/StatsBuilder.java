package kart.snapshot;

import kart.catalog.StatsSnapshot;
import kart.codec.TimeBucket;
import kart.codec.VehicleHash;
import kart.codec.ZOrder;
import kart.data.Chunk;
import kart.data.Chunker;
import kart.data.Trajectory;
import kart.exec.ExactSTFilter;
import kart.ir.BoundIr;
import org.apache.commons.codec.digest.MurmurHash3;

import java.nio.ByteBuffer;
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

        // Hash-index posting count per vehicle (one posting per chunk).
        if (t.vehicleId != null && !t.vehicleId.isEmpty()) {
          String hk = VehicleHash.hex128(t.vehicleId);
          Long hc = s.vehicle_hash_posting_counts.get(hk);
          s.vehicle_hash_posting_counts.put(hk, hc == null ? 1L : hc + 1);
        }

        if (sample(t.tid, c.chunkId)) {
          StatsSnapshot.SampleChunk sc = new StatsSnapshot.SampleChunk();
          sc.tid = t.tid;
          sc.chunk_id = c.chunkId;
          sc.vehicle_id = t.vehicleId;
          sc.time_buckets = timeBuckets;
          sc.z_cells = zCells;
          sc.t_min_ms = c.tMinMs;
          sc.t_max_ms = c.tMaxMs;
          sc.min_x = c.minX;
          sc.min_y = c.minY;
          sc.max_x = c.maxX;
          sc.max_y = c.maxY;
          s.sample_chunks.add(sc);
          // Point-level ExactST selectivity probe: nested half-window of chunk envelope.
          sampleExactFilterProbe(c, s);
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
    if (s.filter_pass_samples > 0) {
      s.filter_pass_rate = (s.filter_pass_hits + 1.0) / (s.filter_pass_samples + 2.0);
    }
    return s;
  }

  /**
   * ExactSTFilter selectivity probe: central half of chunk envelope as a synthetic BoundIr,
   * evaluated with the same {@link ExactSTFilter} used at execution (§13.3).
   */
  private static void sampleExactFilterProbe(Chunk c, StatsSnapshot s) {
    if (c == null || c.points == null || c.points.isEmpty()) {
      return;
    }
    long span = Math.max(1L, c.tMaxMs - c.tMinMs);
    long qStart = c.tMinMs + span / 4;
    long qEnd = c.tMaxMs - span / 4;
    if (qEnd <= qStart) {
      qEnd = c.tMaxMs + 1;
      qStart = c.tMinMs;
    }
    double midX = (c.minX + c.maxX) / 2.0;
    double midY = (c.minY + c.maxY) / 2.0;
    double halfW = Math.max(1e-6, (c.maxX - c.minX) / 4.0);
    double halfH = Math.max(1e-6, (c.maxY - c.minY) / 4.0);

    BoundIr ir = new BoundIr();
    ir.temporal = new BoundIr.Temporal();
    ir.temporal.start_ms = qStart;
    ir.temporal.end_ms = qEnd;
    ir.spatial = new BoundIr.Spatial();
    ir.spatial.min_x = midX - halfW;
    ir.spatial.max_x = midX + halfW;
    ir.spatial.min_y = midY - halfH;
    ir.spatial.max_y = midY + halfH;

    List<Chunk.DecodedPoint> pts = new ArrayList<Chunk.DecodedPoint>(c.points.size());
    for (kart.data.CanonicalPoint p : c.points) {
      pts.add(new Chunk.DecodedPoint(p.timestampMs, p.xM, p.yM));
    }
    boolean pass = ExactSTFilter.matches(ir, pts, null);
    s.filter_pass_samples++;
    if (pass) {
      s.filter_pass_hits++;
    }
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
