package kart.snapshot;

import kart.codec.Bytes;
import kart.codec.RowKeyCodec;
import kart.codec.TShapeSfc;
import kart.codec.TimeBucket;
import kart.codec.VehicleHash;
import kart.codec.XzStarSfc;
import kart.codec.XztCodec;
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
 * Writes traj_raw / traj_meta and index posting tables (legacy + XZ-star / TShape / XZT).
 */
public final class IndexBuilders {

  public static final class LayoutParams {
    public int shardCount = 4;
    public long bucketMs = 600_000L;
    public long epochMs;
    public int zorderLevel = 8;
    public int xzLevel = 12;
    public int tshapeMaxDepth = 12;
    public int xztLevel = 12;
    /** End of XZT time axis (exclusive-ish clamp); default epoch+10y. */
    public long xztHorizonMs = -1L;
    public Rect domain;
    public String tableRaw = "traj_raw_v1";
    public String tableMeta = "traj_meta_v1";
    public String tableTime = "idx_time_v1";
    public String tableZorder = "idx_zorder_v1";
    public String tableHash = "idx_hash_v1";
    public String tableXz = "idx_xz_v1";
    public String tableTshape = "idx_tshape_v1";
    public String tableXzt = "idx_xzt_v1";
  }

  public static final class ExpectedPostings {
    public final Set<String> timeKeys = new HashSet<String>();
    public final Set<String> zorderKeys = new HashSet<String>();
    public final Set<String> hashKeys = new HashSet<String>();
    public final Set<String> xzKeys = new HashSet<String>();
    public final Set<String> tshapeKeys = new HashSet<String>();
    public final Set<String> xztKeys = new HashSet<String>();
  }

  private final LayoutParams layout;
  private final XzStarSfc xzSfc;
  private final TShapeSfc tshapeSfc;
  private final XztCodec xztCodec;

  public IndexBuilders(LayoutParams layout) {
    this.layout = layout;
    Rect d = layout.domain;
    short xzG = (short) Math.max(1, layout.xzLevel);
    short tsG = (short) Math.max(1, layout.tshapeMaxDepth);
    short xztG = (short) Math.max(1, layout.xztLevel);
    this.xzSfc = XzStarSfc.create(xzG, d.minX, d.maxX, d.minY, d.maxY);
    this.tshapeSfc = TShapeSfc.create(tsG, d.minX, d.maxX, d.minY, d.maxY);
    long horizon = layout.xztHorizonMs > layout.epochMs
        ? layout.xztHorizonMs
        : layout.epochMs + 30L * 24L * 3600L * 1000L;
    this.xztCodec = new XztCodec(xztG, layout.epochMs, horizon);
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
      writeXzPosting(kv, shard, t.tid, c, rawKey);
      writeTshapePosting(kv, shard, t.tid, c, rawKey);
      writeXztPosting(kv, shard, t.tid, c, rawKey);
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

  private void writeXzPosting(KvBackend kv, int shard, long tid, Chunk c, byte[] rawKey) throws IOException {
    long code = xzSfc.indexMbr(c.minX, c.minY, c.maxX, c.maxY, true);
    byte[] key = RowKeyCodec.encodeXz(shard, code, tid, c.chunkId);
    Map<String, byte[]> cols = new HashMap<String, byte[]>();
    cols.put("d:r", rawKey);
    kv.put(layout.tableXz, key, cols);
  }

  private void writeTshapePosting(KvBackend kv, int shard, long tid, Chunk c, byte[] rawKey) throws IOException {
    long code = tshapeSfc.indexMbr(c.minX, c.minY, c.maxX, c.maxY);
    byte[] key = RowKeyCodec.encodeTShape(shard, code, tid, c.chunkId);
    Map<String, byte[]> cols = new HashMap<String, byte[]>();
    cols.put("d:r", rawKey);
    kv.put(layout.tableTshape, key, cols);
  }

  private void writeXztPosting(KvBackend kv, int shard, long tid, Chunk c, byte[] rawKey) throws IOException {
    long code = xztCodec.indexInterval(c.tMinMs, c.tMaxMs);
    byte[] key = RowKeyCodec.encodeXzt(shard, code, tid, c.chunkId);
    Map<String, byte[]> cols = new HashMap<String, byte[]>();
    cols.put("d:r", rawKey);
    kv.put(layout.tableXzt, key, cols);
  }

  /** Rewrite XZT postings only (assumes raw keys already exist). */
  public void rewriteXztPostings(KvBackend kv, Trajectory t, List<Chunk> chunks) throws IOException {
    int shard = RowKeyCodec.shardOf(t.tid, layout.shardCount) & 0xFF;
    for (Chunk c : chunks) {
      byte[] rawKey = RowKeyCodec.encodeRaw(shard, t.tid, c.chunkId);
      writeXztPosting(kv, shard, t.tid, c, rawKey);
    }
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
    long xz = xzSfc.indexMbr(c.minX, c.minY, c.maxX, c.maxY, true);
    exp.xzKeys.add(hex(RowKeyCodec.encodeXz(shard, xz, t.tid, c.chunkId)));
    long ts = tshapeSfc.indexMbr(c.minX, c.minY, c.maxX, c.maxY);
    exp.tshapeKeys.add(hex(RowKeyCodec.encodeTShape(shard, ts, t.tid, c.chunkId)));
    long xzt = xztCodec.indexInterval(c.tMinMs, c.tMaxMs);
    exp.xztKeys.add(hex(RowKeyCodec.encodeXzt(shard, xzt, t.tid, c.chunkId)));
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
