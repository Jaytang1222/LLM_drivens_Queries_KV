package kart.compile;

import kart.catalog.Manifest;
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
  public final Rect domain;
  public final String tableRaw;
  public final String tableMeta;
  public final String tableTime;
  public final String tableZorder;
  public final String tableHash;
  public final int maxZorderRanges;

  public LayoutContext(int shardCount, long bucketMs, long epochMs, int zorderLevel,
                       Rect domain, String tableRaw, String tableMeta,
                       String tableTime, String tableZorder, String tableHash,
                       int maxZorderRanges) {
    this.shardCount = shardCount;
    this.bucketMs = bucketMs;
    this.epochMs = epochMs;
    this.zorderLevel = zorderLevel;
    this.domain = domain;
    this.tableRaw = tableRaw;
    this.tableMeta = tableMeta;
    this.tableTime = tableTime;
    this.tableZorder = tableZorder;
    this.tableHash = tableHash;
    this.maxZorderRanges = maxZorderRanges;
  }

  public static LayoutContext from(Manifest manifest) {
    Manifest.Layout l = manifest.layout;
    Rect domain = new Rect(l.domain.xmin, l.domain.ymin, l.domain.xmax, l.domain.ymax);
    return new LayoutContext(
        l.shard_count, l.bucket_ms, l.epoch_ms, l.zorder_level, domain,
        table(l, "raw", "traj_raw_v1"),
        table(l, "meta", "traj_meta_v1"),
        table(l, "time", "idx_time_v1"),
        table(l, "zorder", "idx_zorder_v1"),
        table(l, "hash", "idx_hash_v1"),
        64);
  }

  public static LayoutContext from(IndexBuilders.LayoutParams p) {
    return new LayoutContext(
        p.shardCount, p.bucketMs, p.epochMs, p.zorderLevel, p.domain,
        p.tableRaw, p.tableMeta, p.tableTime, p.tableZorder, p.tableHash, 64);
  }

  private static String table(Manifest.Layout l, String key, String fallback) {
    if (l.tables != null && l.tables.containsKey(key)) {
      return l.tables.get(key);
    }
    return fallback;
  }

  public boolean knownTable(String name) {
    return name != null && (name.equals(tableRaw) || name.equals(tableMeta)
        || name.equals(tableTime) || name.equals(tableZorder) || name.equals(tableHash));
  }

  /** Deterministic canonical string for layout hashing. */
  public String canonicalString() {
    return "shardCount=" + shardCount
        + "|bucketMs=" + bucketMs
        + "|epochMs=" + epochMs
        + "|zorderLevel=" + zorderLevel
        + "|domain=" + domain.minX + "," + domain.minY + "," + domain.maxX + "," + domain.maxY
        + "|tables=" + tableRaw + "," + tableMeta + "," + tableTime + "," + tableZorder + "," + tableHash
        + "|maxZorderRanges=" + maxZorderRanges;
  }
}
