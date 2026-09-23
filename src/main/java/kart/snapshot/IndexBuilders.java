package kart.snapshot;

import kart.codec.Bytes;
import kart.codec.RowKeyCodec;
import kart.codec.TimeBucket;
import kart.codec.VehicleHash;
import kart.codec.ZOrder;
import kart.data.Chunk;
import kart.data.Trajectory;
import kart.exec.HBaseBackend;
import kart.exec.KvBackend;
import kart.geo.Rect;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Writes traj_raw / traj_meta and three index posting tables.
 */
public final class IndexBuilders {

  public static final class LayoutParams {
    public int shardCount = 4;
    public long bucketMs = 600_000L;
    public long epochMs;
    public int zorderLevel = 8;
    public Rect domain;
    public String tableRaw = "traj_raw_v1";
    public String tableMeta = "traj_meta_v1";
    public String tableTime = "idx_time_v1";
    public String tableZorder = "idx_zorder_v1";
    public String tableHash = "idx_hash_v1";
  }

  public static final class ExpectedPostings {
    public final Set<String> timeKeys = new HashSet<String>();
    public final Set<String> zorderKeys = new HashSet<String>();
    public final Set<String> hashKeys = new HashSet<String>();
  }

  private final LayoutParams layout;

  public IndexBuilders(LayoutParams layout) {
    this.layout = layout;
  }

  public void writeTrajectory(KvBackend kv, Trajectory t, List<Chunk> chunks) throws IOException {
    int shard = RowKeyCodec.shardOf(t.tid, layout.shardCount) & 0xFF;
    Map<String, byte[]> meta = new HashMap<String, byte[]>();
    meta.put("d:ext", t.trajectoryId.getBytes(StandardCharsets.UTF_8));
    meta.put("d:veh", t.vehicleId.getBytes(StandardCharsets.UTF_8));
    meta.put("d:cc", intBytes(chunks.size()));
    meta.put("d:pc", intBytes(t.size()));
    long t0 = Long.MAX_VALUE;
    long t1 = Long.MIN_VALUE;
    for (Chunk c : chunks) {
      t0 = Math.min(t0, c.tMinMs);
      t1 = Math.max(t1, c.tMaxMs);
    }
    meta.put("d:t0", longBytes(t0));
    meta.put("d:t1", longBytes(t1));
    kv.put(layout.tableMeta, RowKeyCodec.encodeMeta(shard, t.tid), meta);

    byte[] hash = VehicleHash.hash128(t.vehicleId);
    for (Chunk c : chunks) {
      Map<String, byte[]> cols = new HashMap<String, byte[]>();
      cols.put("d:p", c.encodePayload());
      cols.put("d:m", c.encodeMeta());
      byte[] rawKey = RowKeyCodec.encodeRaw(shard, t.tid, c.chunkId);
      kv.put(layout.tableRaw, rawKey, cols);
      writeTimePostings(kv, shard, t.tid, c, rawKey);
      writeZorderPostings(kv, shard, t.tid, c, rawKey);
      writeHashPosting(kv, shard, t.tid, c, rawKey, hash, t.vehicleId);
    }
  }

  private void writeTimePostings(KvBackend kv, int shard, long tid, Chunk c, byte[] rawKey) throws IOException {
    long b0 = TimeBucket.bucketOf(c.tMinMs, layout.epochMs, layout.bucketMs);
    long b1 = TimeBucket.bucketOf(c.tMaxMs, layout.epochMs, layout.bucketMs);
    for (long b = b0; b <= b1; b++) {
      byte[] key = RowKeyCodec.encodeTime(shard, b, tid, c.chunkId);
      Map<String, byte[]> cols = new HashMap<String, byte[]>();
      cols.put("d:r", rawKey);
      kv.put(layout.tableTime, key, cols);
    }
  }

  private void writeZorderPostings(KvBackend kv, int shard, long tid, Chunk c, byte[] rawKey) throws IOException {
    ZOrder.CellRect cells = ZOrder.metersToCells(
        c.minX, c.minY, c.maxX, c.maxY,
        layout.domain.minX, layout.domain.minY, layout.domain.maxX, layout.domain.maxY,
        layout.zorderLevel);
    for (int cx = cells.cxMin; cx <= cells.cxMax; cx++) {
      for (int cy = cells.cyMin; cy <= cells.cyMax; cy++) {
        long z = ZOrder.interleave(cx, cy, layout.zorderLevel);
        byte[] key = RowKeyCodec.encodeZorder(shard, z, tid, c.chunkId);
        Map<String, byte[]> cols = new HashMap<String, byte[]>();
        cols.put("d:r", rawKey);
        kv.put(layout.tableZorder, key, cols);
      }
    }
  }

  private void writeHashPosting(KvBackend kv, int shard, long tid, Chunk c, byte[] rawKey,
                                byte[] hash, String vehicleId) throws IOException {
    byte[] key = RowKeyCodec.encodeHash(shard, RowKeyCodec.HASH_FIELD_VEHICLE, hash, tid, c.chunkId);
    Map<String, byte[]> cols = new HashMap<String, byte[]>();
    cols.put("d:r", rawKey);
    cols.put("d:v", vehicleId.getBytes(StandardCharsets.UTF_8));
    kv.put(layout.tableHash, key, cols);
  }

  /** Compute expected posting key hex sets for one chunk (for verifier / tests). */
  public ExpectedPostings expectedForChunk(Trajectory t, Chunk c) {
    ExpectedPostings exp = new ExpectedPostings();
    int shard = RowKeyCodec.shardOf(t.tid, layout.shardCount) & 0xFF;
    long b0 = TimeBucket.bucketOf(c.tMinMs, layout.epochMs, layout.bucketMs);
    long b1 = TimeBucket.bucketOf(c.tMaxMs, layout.epochMs, layout.bucketMs);
    for (long b = b0; b <= b1; b++) {
      exp.timeKeys.add(hex(RowKeyCodec.encodeTime(shard, b, t.tid, c.chunkId)));
    }
    ZOrder.CellRect cells = ZOrder.metersToCells(
        c.minX, c.minY, c.maxX, c.maxY,
        layout.domain.minX, layout.domain.minY, layout.domain.maxX, layout.domain.maxY,
        layout.zorderLevel);
    for (int cx = cells.cxMin; cx <= cells.cxMax; cx++) {
      for (int cy = cells.cyMin; cy <= cells.cyMax; cy++) {
        long z = ZOrder.interleave(cx, cy, layout.zorderLevel);
        exp.zorderKeys.add(hex(RowKeyCodec.encodeZorder(shard, z, t.tid, c.chunkId)));
      }
    }
    byte[] hash = VehicleHash.hash128(t.vehicleId);
    exp.hashKeys.add(hex(RowKeyCodec.encodeHash(shard, RowKeyCodec.HASH_FIELD_VEHICLE, hash, t.tid, c.chunkId)));
    return exp;
  }

  public static String hex(byte[] b) {
    StringBuilder sb = new StringBuilder(b.length * 2);
    for (byte x : b) {
      sb.append(String.format("%02x", x & 0xFF));
    }
    return sb.toString();
  }

  private static byte[] intBytes(int v) {
    return ByteBuffer.allocate(4).putInt(v).array();
  }

  private static byte[] longBytes(long v) {
    return ByteBuffer.allocate(8).putLong(v).array();
  }
}
