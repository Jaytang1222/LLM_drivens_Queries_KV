package kart.compile;

import kart.catalog.Manifest;
import kart.codec.TShapeSfc;
import kart.codec.XzStarSfc;
import kart.codec.XztCodec;
import kart.geo.Rect;
import kart.snapshot.IndexBuilders;

/**
 * Physical layout parameters needed by index adapters and the compiler.
 */
public final class LayoutContext {

  public final int shardCount;
  public final long bucketMs;
  public final long epochMs;
  public final int zorderLevel;
  public final int xzLevel;
  public final int tshapeMaxDepth;
  public final int xztLevel;
  public final long xztHorizonMs;
  public final Rect domain;
  public final String tableRaw;
  public final String tableMeta;
  public final String tableTime;
  public final String tableZorder;
  public final String tableHash;
  public final String tableXz;
  public final String tableTshape;
  public final String tableXzt;
  public final int maxZorderRanges;
  public final int maxXzRanges;

  private XzStarSfc xzSfc;
  private TShapeSfc tshapeSfc;
  private XztCodec xztCodec;

  public LayoutContext(int shardCount, long bucketMs, long epochMs, int zorderLevel,
                       int xzLevel, int tshapeMaxDepth, int xztLevel, long xztHorizonMs,
                       Rect domain, String tableRaw, String tableMeta,
                       String tableTime, String tableZorder, String tableHash,
                       String tableXz, String tableTshape, String tableXzt,
                       int maxZorderRanges) {
    this.shardCount = shardCount;
    this.bucketMs = bucketMs;
    this.epochMs = epochMs;
    this.zorderLevel = zorderLevel;
    this.xzLevel = xzLevel;
    this.tshapeMaxDepth = tshapeMaxDepth;
    this.xztLevel = xztLevel;
    this.xztHorizonMs = xztHorizonMs;
    this.domain = domain;
    this.tableRaw = tableRaw;
    this.tableMeta = tableMeta;
    this.tableTime = tableTime;
    this.tableZorder = tableZorder;
    this.tableHash = tableHash;
    this.tableXz = tableXz;
    this.tableTshape = tableTshape;
    this.tableXzt = tableXzt;
    this.maxZorderRanges = maxZorderRanges;
    this.maxXzRanges = maxZorderRanges;
  }

  public static LayoutContext from(Manifest manifest) {
    Manifest.Layout l = manifest.layout;
    Rect domain = new Rect(l.domain.xmin, l.domain.ymin, l.domain.xmax, l.domain.ymax);
    long horizon = l.xzt_horizon_ms > l.epoch_ms
        ? l.xzt_horizon_ms
        : l.epoch_ms + 30L * 24L * 3600L * 1000L;
    int xz = l.xz_level > 0 ? l.xz_level : 12;
    int ts = l.tshape_max_depth > 0 ? l.tshape_max_depth : 12;
    int xzt = l.xzt_level > 0 ? l.xzt_level : 12;
    return new LayoutContext(
        l.shard_count, l.bucket_ms, l.epoch_ms, l.zorder_level,
        xz, ts, xzt, horizon, domain,
        table(l, "raw", "traj_raw_v1"),
        table(l, "meta", "traj_meta_v1"),
        table(l, "time", "idx_time_v1"),
        table(l, "zorder", "idx_zorder_v1"),
        table(l, "hash", "idx_hash_v1"),
        table(l, "xz", "idx_xz_v1"),
        table(l, "tshape", "idx_tshape_v1"),
        table(l, "xzt", "idx_xzt_v1"),
        64);
  }

  public static LayoutContext from(IndexBuilders.LayoutParams p) {
    long horizon = p.xztHorizonMs > p.epochMs
        ? p.xztHorizonMs
        : p.epochMs + 30L * 24L * 3600L * 1000L;
    return new LayoutContext(
        p.shardCount, p.bucketMs, p.epochMs, p.zorderLevel,
        p.xzLevel, p.tshapeMaxDepth, p.xztLevel, horizon, p.domain,
        p.tableRaw, p.tableMeta, p.tableTime, p.tableZorder, p.tableHash,
        p.tableXz, p.tableTshape, p.tableXzt, 64);
  }

  private static String table(Manifest.Layout l, String key, String fallback) {
    if (l.tables != null && l.tables.containsKey(key)) {
      return l.tables.get(key);
    }
    return fallback;
  }

  public XzStarSfc xzSfc() {
    if (xzSfc == null) {
      xzSfc = XzStarSfc.create((short) Math.max(1, xzLevel),
          domain.minX, domain.maxX, domain.minY, domain.maxY);
    }
    return xzSfc;
  }

  public TShapeSfc tshapeSfc() {
    if (tshapeSfc == null) {
      tshapeSfc = TShapeSfc.create((short) Math.max(1, tshapeMaxDepth),
          domain.minX, domain.maxX, domain.minY, domain.maxY);
    }
    return tshapeSfc;
  }

  public XztCodec xztCodec() {
    if (xztCodec == null) {
      xztCodec = new XztCodec((short) Math.max(1, xztLevel), epochMs, xztHorizonMs);
    }
    return xztCodec;
  }

  public boolean knownTable(String name) {
    return name != null && (name.equals(tableRaw) || name.equals(tableMeta)
        || name.equals(tableTime) || name.equals(tableZorder) || name.equals(tableHash)
        || name.equals(tableXz) || name.equals(tableTshape) || name.equals(tableXzt));
  }

  /** Deterministic canonical string for layout hashing. */
  public String canonicalString() {
    return "shardCount=" + shardCount
        + "|bucketMs=" + bucketMs
        + "|epochMs=" + epochMs
        + "|zorderLevel=" + zorderLevel
        + "|xzLevel=" + xzLevel
        + "|tshapeMaxDepth=" + tshapeMaxDepth
        + "|xztLevel=" + xztLevel
        + "|xztHorizonMs=" + xztHorizonMs
        + "|domain=" + domain.minX + "," + domain.minY + "," + domain.maxX + "," + domain.maxY
        + "|tables=" + tableRaw + "," + tableMeta + "," + tableTime + "," + tableZorder + ","
        + tableHash + "," + tableXz + "," + tableTshape + "," + tableXzt
        + "|maxZorderRanges=" + maxZorderRanges;
  }
}
