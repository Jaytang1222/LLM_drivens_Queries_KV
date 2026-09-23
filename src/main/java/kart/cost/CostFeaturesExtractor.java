package kart.cost;

import kart.catalog.StatsSnapshot;
import kart.codec.TimeBucket;
import kart.codec.ZOrder;
import kart.compile.LayoutContext;
import kart.compile.PhysicalPlan;
import kart.compile.QueryCompiler;
import kart.compile.ScanTask;
import kart.compile.ZOrderIndexAdapter;
import kart.geo.Rect;
import kart.ir.BoundIr;
import kart.plan.Op;
import kart.plan.PlanEnvelope;
import kart.plan.PlanNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Extract {@link CostFeatures} from PhysicalPlan + stats (T5.1).
 * Missing stats → conservative upper bounds + {@code missingStats=true}.
 */
public final class CostFeaturesExtractor {

  private final LayoutContext layout;
  private final StatsSnapshot stats;

  public CostFeaturesExtractor(LayoutContext layout, StatsSnapshot stats) {
    this.layout = layout;
    this.stats = stats;
  }

  /** Final: use exact scan ranges from compiled PhysicalPlan. */
  public CostFeatures extractFinal(PhysicalPlan phys, PlanEnvelope plan, BoundIr ir) {
    CostFeatures f = base(plan);
    if (phys != null && phys.scanTasks != null) {
      f.scanRanges = phys.scanTasks.size();
    } else {
      f.scanRanges = estimateScanRanges(plan, ir);
      f.missingStats = true;
    }
    fillFromStatsAndPlan(f, plan, ir);
    return f;
  }

  /**
   * Fast: compile when layout is available for exact ranges; otherwise approximate
   * from IR predicates × shard_count.
   */
  public CostFeatures extractFast(PlanEnvelope plan, BoundIr ir) {
    CostFeatures f = base(plan);
    PhysicalPlan phys = null;
    if (layout != null && plan != null && ir != null) {
      try {
        phys = new QueryCompiler(layout).compile(plan, ir);
      } catch (RuntimeException ignored) {
        phys = null;
      }
    }
    if (phys != null) {
      f.scanRanges = phys.scanTasks.size();
    } else {
      f.scanRanges = estimateScanRanges(plan, ir);
    }
    fillFromStatsAndPlan(f, plan, ir);
    return f;
  }

  private CostFeatures base(PlanEnvelope plan) {
    CostFeatures f = new CostFeatures();
    if (plan != null) {
      f.planId = plan.plan_id;
    }
    fillAverages(f);
    if (stats != null && stats.sample_chunks != null) {
      f.sampleSize = stats.sample_chunks.size();
      f.sampleRate = stats.sample_rate > 0 ? stats.sample_rate : 0.01;
    } else {
      f.sampleSize = 0;
      f.missingStats = true;
    }
    return f;
  }

  private void fillFromStatsAndPlan(CostFeatures f, PlanEnvelope plan, BoundIr ir) {
    AccessShape shape = accessShape(plan);
    f.accessBranches = new ArrayList<String>(shape.branches);

    long timeRows = 0L;
    long zRows = 0L;
    long hashRows = 0L;
    long fullRows = 0L;

    if (shape.hasTime && ir != null && ir.temporal != null) {
      SumResult sr = sumTimePostings(ir.temporal.start_ms, ir.temporal.end_ms);
      timeRows = sr.sum;
      if (sr.missing) {
        f.missingStats = true;
      }
    }
    if (shape.hasZ && ir != null && ir.spatial != null) {
      SumResult sr = sumZPostings(ir.spatial);
      zRows = sr.sum;
      if (sr.missing) {
        f.missingStats = true;
      }
    }
    if (shape.hasHash) {
      // No per-hash histogram in MVP stats → conservative: all chunks upper bound.
      hashRows = conservativeTotalPostings();
      f.missingStats = true;
    }
    if (shape.hasFull) {
      fullRows = conservativeTotalPostings();
      if (stats == null) {
        f.missingStats = true;
      }
    }

    f.estIndexRows = timeRows + zRows + hashRows + fullRows;

    long cand;
    if (shape.hasFull && !shape.hasTime && !shape.hasZ && !shape.hasHash) {
      cand = Math.max(1L, fullRows);
    } else if (shape.intersect && shape.hasTime && shape.hasZ) {
      cand = estimateIntersectCandidates(ir);
    } else if (shape.hasTime && !shape.hasZ && !shape.hasHash) {
      cand = estimateSingleIndexCandidates(ir, true, false);
    } else if (shape.hasZ && !shape.hasTime && !shape.hasHash) {
      cand = estimateSingleIndexCandidates(ir, false, true);
    } else if (shape.hasHash && !shape.hasTime && !shape.hasZ) {
      cand = Math.max(1L, (long) Math.ceil(hashRows * 0.5));
      f.missingStats = true;
    } else {
      // mixed without full intersect modeling: take min branch as optimistic, mark missing
      long minBranch = Long.MAX_VALUE;
      if (shape.hasTime) {
        minBranch = Math.min(minBranch, Math.max(1L, timeRows));
      }
      if (shape.hasZ) {
        minBranch = Math.min(minBranch, Math.max(1L, zRows));
      }
      if (shape.hasHash) {
        minBranch = Math.min(minBranch, Math.max(1L, hashRows));
      }
      cand = minBranch == Long.MAX_VALUE ? Math.max(1L, f.estIndexRows) : minBranch;
    }
    f.estCandidateChunks = Math.max(1L, cand);

    f.estRawBytes = (long) Math.ceil(f.estCandidateChunks * f.avgChunkBytes);
    double pass = f.missingStats ? 1.0 : f.filterPassRate; // conservative when missing
    f.estEligibleTrajs = Math.max(1L,
        (long) Math.ceil(f.estCandidateChunks * pass * f.chunksToTraj));

    boolean topK = ir != null && ir.result != null && "TOP_K".equals(ir.result.mode);
    if (topK) {
      double refLen = f.avgTrajLen;
      f.estDtwCells = (long) Math.ceil(f.estEligibleTrajs * f.avgTrajLen * refLen);
    } else {
      f.estDtwCells = 0L;
    }

    f.extras.put("time_index_rows", Long.valueOf(timeRows));
    f.extras.put("z_index_rows", Long.valueOf(zRows));
    f.extras.put("access", shape.branches.toString());
  }

  private long estimateIntersectCandidates(BoundIr ir) {
    if (stats == null || stats.sample_chunks == null || stats.sample_chunks.isEmpty()) {
      // no sample: geometric mean of branch postings (conservative-ish)
      SumResult t = ir != null && ir.temporal != null
          ? sumTimePostings(ir.temporal.start_ms, ir.temporal.end_ms)
          : SumResult.zero();
      SumResult z = ir != null && ir.spatial != null
          ? sumZPostings(ir.spatial)
          : SumResult.zero();
      long a = Math.max(1L, t.sum);
      long b = Math.max(1L, z.sum);
      return Math.max(1L, (long) Math.ceil(Math.sqrt((double) a * (double) b)));
    }
    Set<Long> timeBuckets = timeBucketSet(ir);
    Set<Long> zCells = zCellSet(ir);
    int hit = 0;
    for (StatsSnapshot.SampleChunk sc : stats.sample_chunks) {
      if (hitsTime(sc, timeBuckets) && hitsZ(sc, zCells)) {
        hit++;
      }
    }
    double rate = stats.sample_rate > 0 ? stats.sample_rate : 0.01;
    if (hit == 0) {
      return Math.max(1L, (long) Math.ceil(0.5 / rate));
    }
    return Math.max(1L, (long) Math.ceil(hit / rate));
  }

  private long estimateSingleIndexCandidates(BoundIr ir, boolean time, boolean z) {
    if (stats == null || stats.sample_chunks == null || stats.sample_chunks.isEmpty()) {
      if (time && ir != null && ir.temporal != null) {
        return Math.max(1L, sumTimePostings(ir.temporal.start_ms, ir.temporal.end_ms).sum);
      }
      if (z && ir != null && ir.spatial != null) {
        return Math.max(1L, sumZPostings(ir.spatial).sum);
      }
      return conservativeTotalPostings();
    }
    Set<Long> timeBuckets = time ? timeBucketSet(ir) : null;
    Set<Long> zCells = z ? zCellSet(ir) : null;
    int hit = 0;
    for (StatsSnapshot.SampleChunk sc : stats.sample_chunks) {
      boolean ok = true;
      if (time) {
        ok = hitsTime(sc, timeBuckets);
      }
      if (ok && z) {
        ok = hitsZ(sc, zCells);
      }
      if (ok) {
        hit++;
      }
    }
    double rate = stats.sample_rate > 0 ? stats.sample_rate : 0.01;
    if (hit == 0) {
      return Math.max(1L, (long) Math.ceil(0.5 / rate));
    }
    return Math.max(1L, (long) Math.ceil(hit / rate));
  }

  private static boolean hitsTime(StatsSnapshot.SampleChunk sc, Set<Long> buckets) {
    if (buckets == null || buckets.isEmpty()) {
      return true;
    }
    if (sc.time_buckets == null) {
      return false;
    }
    for (Long b : sc.time_buckets) {
      if (buckets.contains(b)) {
        return true;
      }
    }
    return false;
  }

  private static boolean hitsZ(StatsSnapshot.SampleChunk sc, Set<Long> cells) {
    if (cells == null || cells.isEmpty()) {
      return true;
    }
    if (sc.z_cells == null) {
      return false;
    }
    for (Long z : sc.z_cells) {
      if (cells.contains(z)) {
        return true;
      }
    }
    return false;
  }

  private Set<Long> timeBucketSet(BoundIr ir) {
    Set<Long> set = new HashSet<Long>();
    if (ir == null || ir.temporal == null || layout == null) {
      return set;
    }
    long[] buckets = TimeBucket.bucketsCovering(
        ir.temporal.start_ms, ir.temporal.end_ms, layout.epochMs, layout.bucketMs);
    for (long b : buckets) {
      set.add(b);
    }
    return set;
  }

  private Set<Long> zCellSet(BoundIr ir) {
    Set<Long> set = new HashSet<Long>();
    if (ir == null || ir.spatial == null || layout == null) {
      return set;
    }
    Rect d = layout.domain;
    ZOrder.CellRect cells = ZOrder.metersToCells(
        ir.spatial.min_x, ir.spatial.min_y, ir.spatial.max_x, ir.spatial.max_y,
        d.minX, d.minY, d.maxX, d.maxY, layout.zorderLevel);
    for (int cx = cells.cxMin; cx <= cells.cxMax; cx++) {
      for (int cy = cells.cyMin; cy <= cells.cyMax; cy++) {
        set.add(ZOrder.interleave(cx, cy, layout.zorderLevel));
      }
    }
    return set;
  }

  private SumResult sumTimePostings(long startMs, long endMs) {
    SumResult r = new SumResult();
    if (layout == null) {
      r.missing = true;
      r.sum = conservativeTotalPostings();
      return r;
    }
    long[] buckets = TimeBucket.bucketsCovering(startMs, endMs, layout.epochMs, layout.bucketMs);
    if (stats == null || stats.time_bucket_posting_counts == null
        || stats.time_bucket_posting_counts.isEmpty()) {
      r.missing = true;
      // conservative: assume each bucket×shard has max observed or 10k
      long per = maxMapValue(null);
      r.sum = Math.max(1L, (long) buckets.length * Math.max(per, 10_000L));
      return r;
    }
    long maxKnown = maxMapValue(stats.time_bucket_posting_counts);
    for (long b : buckets) {
      Long c = stats.time_bucket_posting_counts.get(Long.toString(b));
      if (c == null) {
        r.missing = true;
        r.sum += Math.max(maxKnown, 1L);
      } else {
        r.sum += c.longValue();
      }
    }
    return r;
  }

  private SumResult sumZPostings(BoundIr.Spatial spatial) {
    SumResult r = new SumResult();
    if (layout == null || spatial == null) {
      r.missing = true;
      r.sum = conservativeTotalPostings();
      return r;
    }
    Rect d = layout.domain;
    ZOrder.CellRect cells = ZOrder.metersToCells(
        spatial.min_x, spatial.min_y, spatial.max_x, spatial.max_y,
        d.minX, d.minY, d.maxX, d.maxY, layout.zorderLevel);
    if (stats == null || stats.zorder_cell_posting_counts == null
        || stats.zorder_cell_posting_counts.isEmpty()) {
      r.missing = true;
      long cellCount = (long) (cells.cxMax - cells.cxMin + 1) * (cells.cyMax - cells.cyMin + 1);
      r.sum = Math.max(1L, cellCount * 10_000L);
      return r;
    }
    long maxKnown = maxMapValue(stats.zorder_cell_posting_counts);
    for (int cx = cells.cxMin; cx <= cells.cxMax; cx++) {
      for (int cy = cells.cyMin; cy <= cells.cyMax; cy++) {
        long z = ZOrder.interleave(cx, cy, layout.zorderLevel);
        Long c = stats.zorder_cell_posting_counts.get(Long.toString(z));
        if (c == null) {
          r.missing = true;
          r.sum += Math.max(maxKnown, 1L);
        } else {
          r.sum += c.longValue();
        }
      }
    }
    return r;
  }

  private long conservativeTotalPostings() {
    if (stats == null) {
      return 1_000_000L;
    }
    long t = sumMap(stats.time_bucket_posting_counts);
    long z = sumMap(stats.zorder_cell_posting_counts);
    long v = Math.max(t, z);
    return v > 0 ? v : 1_000_000L;
  }

  private long estimateScanRanges(PlanEnvelope plan, BoundIr ir) {
    if (plan == null) {
      return 1L;
    }
    AccessShape shape = accessShape(plan);
    long ranges = 0L;
    int shards = layout != null ? Math.max(1, layout.shardCount) : 4;
    if (shape.hasTime && ir != null && ir.temporal != null && layout != null) {
      long[] buckets = TimeBucket.bucketsCovering(
          ir.temporal.start_ms, ir.temporal.end_ms, layout.epochMs, layout.bucketMs);
      ranges += (long) buckets.length * shards;
    }
    if (shape.hasZ && ir != null && ir.spatial != null && layout != null) {
      List<ZOrder.MortonRange> mr = new ZOrderIndexAdapter(layout).mortonRanges(
          new Rect(ir.spatial.min_x, ir.spatial.min_y, ir.spatial.max_x, ir.spatial.max_y));
      ranges += (long) mr.size() * shards;
    }
    if (shape.hasHash) {
      ranges += shards;
    }
    if (shape.hasFull) {
      ranges += shards;
    }
    return Math.max(1L, ranges);
  }

  private void fillAverages(CostFeatures f) {
    if (stats == null) {
      return;
    }
    double[] chunk = histAverage(stats.chunk_size_hist, new double[]{1, 8, 40, 160, 300});
    if (chunk[0] > 0) {
      f.avgPointsPerChunk = chunk[0];
      f.avgChunkBytes = Math.max(64.0, chunk[0] * 24.0);
    }
    double[] traj = histAverage(stats.traj_length_hist, new double[]{1, 8, 40, 160, 400});
    if (traj[0] > 0) {
      f.avgTrajLen = traj[0];
    }
    if (f.avgPointsPerChunk > 0 && f.avgTrajLen > 0) {
      f.chunksToTraj = Math.min(1.0, f.avgPointsPerChunk / f.avgTrajLen);
    }
  }

  /** Returns [weightedAvg, totalCount]. Midpoints aligned to StatsBuilder bucket keys. */
  private static double[] histAverage(Map<String, Long> hist, double[] mids) {
    if (hist == null || hist.isEmpty()) {
      return new double[]{0, 0};
    }
    String[] keys = new String[]{"1", "2-16", "17-64", "65-256", "257+"};
    double sum = 0;
    long n = 0;
    for (int i = 0; i < keys.length; i++) {
      Long c = hist.get(keys[i]);
      if (c != null && c.longValue() > 0) {
        sum += mids[Math.min(i, mids.length - 1)] * c.longValue();
        n += c.longValue();
      }
    }
    if (n == 0) {
      return new double[]{0, 0};
    }
    return new double[]{sum / n, n};
  }

  private static long sumMap(Map<String, Long> m) {
    if (m == null) {
      return 0L;
    }
    long s = 0;
    for (Long v : m.values()) {
      if (v != null) {
        s += v.longValue();
      }
    }
    return s;
  }

  private static long maxMapValue(Map<String, Long> m) {
    if (m == null || m.isEmpty()) {
      return 0L;
    }
    long max = 0L;
    for (Long v : m.values()) {
      if (v != null && v.longValue() > max) {
        max = v.longValue();
      }
    }
    return max;
  }

  static AccessShape accessShape(PlanEnvelope plan) {
    AccessShape s = new AccessShape();
    if (plan == null || plan.nodes == null) {
      return s;
    }
    for (PlanNode n : plan.nodes) {
      if (n == null || n.op == null) {
        continue;
      }
      if (n.op == Op.TIME_RANGE_SCAN) {
        s.hasTime = true;
        s.branches.add("TIME");
      } else if (n.op == Op.ZORDER_RANGE_SCAN) {
        s.hasZ = true;
        s.branches.add("ZORDER");
      } else if (n.op == Op.EQUALITY_LOOKUP) {
        s.hasHash = true;
        s.branches.add("HASH");
      } else if (n.op == Op.FULL_SCAN_CHUNKS) {
        s.hasFull = true;
        s.branches.add("FULL");
      } else if (n.op == Op.INTERSECT) {
        s.intersect = true;
      }
    }
    return s;
  }

  /** Count exact ranges from a physical plan (for tests / selector ties). */
  public static int rangeCount(PhysicalPlan phys) {
    return phys == null || phys.scanTasks == null ? 0 : phys.scanTasks.size();
  }

  public static List<String> coverageSummary(PhysicalPlan phys) {
    List<String> out = new ArrayList<String>();
    if (phys == null) {
      return out;
    }
    for (ScanTask t : phys.scanTasks) {
      out.add(t.table + " shard=" + t.shard + " " + t.coverageRef
          + " [" + t.startHex + "," + t.stopHex + ")");
    }
    return out;
  }

  static final class AccessShape {
    boolean hasTime;
    boolean hasZ;
    boolean hasHash;
    boolean hasFull;
    boolean intersect;
    final List<String> branches = new ArrayList<String>();
  }

  private static final class SumResult {
    long sum;
    boolean missing;

    static SumResult zero() {
      return new SumResult();
    }
  }
}
