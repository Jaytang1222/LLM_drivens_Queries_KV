package kart.cost;

import kart.catalog.StatsSnapshot;
import kart.codec.TimeBucket;
import kart.codec.VehicleHash;
import kart.codec.ZOrder;
import kart.compile.LayoutContext;
import kart.compile.PhysicalPlan;
import kart.compile.ScanTask;
import kart.compile.ZOrderIndexAdapter;
import kart.geo.Rect;
import kart.ir.BoundIr;
import kart.plan.Op;
import kart.plan.PlanEnvelope;
import kart.plan.PlanNode;

import java.util.ArrayList;
import java.util.HashMap;
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
  private final RegionMapping regionMapping;
  private final long softMemoryBytes;

  public CostFeaturesExtractor(LayoutContext layout, StatsSnapshot stats) {
    this(layout, stats, null, 0L);
  }

  public CostFeaturesExtractor(LayoutContext layout, StatsSnapshot stats,
                               RegionMapping regionMapping) {
    this(layout, stats, regionMapping, 0L);
  }

  public CostFeaturesExtractor(LayoutContext layout, StatsSnapshot stats,
                               RegionMapping regionMapping, long softMemoryBytes) {
    this.layout = layout;
    this.stats = stats;
    this.regionMapping = regionMapping != null ? regionMapping : new RegionMapping.ShardFallback();
    this.softMemoryBytes = softMemoryBytes > 0 ? softMemoryBytes : (512L * 1024L * 1024L);
  }

  /** Mutable counters for one extractFinal call (region locate sub-interval). */
  public static final class ExtractTiming {
    public long regionLocateMs;
    public int locateCalls;
    public int locateCacheHits;
    public int nScanTasks;
  }

  /** Final: use exact scan ranges from compiled PhysicalPlan + live RS locate. */
  public CostFeatures extractFinal(PhysicalPlan phys, PlanEnvelope plan, BoundIr ir) {
    return extractFinal(phys, plan, ir, null);
  }

  /**
   * Final extract with optional timing. Region locate uses a per-call cache so
   * duplicate (table,startRow) pairs inside one extract are not re-located.
   */
  public CostFeatures extractFinal(PhysicalPlan phys, PlanEnvelope plan, BoundIr ir,
                                   ExtractTiming timing) {
    CostFeatures f = base(plan);
    Map<String, String> locateCache = new HashMap<String, String>();
    int calls = 0;
    int hits = 0;
    long locateNanos = 0L;
    if (phys != null && phys.scanTasks != null) {
      f.scanRanges = phys.scanTasks.size();
      if (timing != null) {
        timing.nScanTasks = phys.scanTasks.size();
      }
      for (ScanTask t : phys.scanTasks) {
        String rs = null;
        String cacheKey = locateCacheKey(t.table, t.startHex);
        if (cacheKey != null && locateCache.containsKey(cacheKey)) {
          hits++;
          calls++;
          rs = locateCache.get(cacheKey);
          if (rs != null && rs.isEmpty()) {
            rs = null;
          }
        } else {
          long t0 = System.nanoTime();
          try {
            rs = regionMapping.locate(t.table, t.startBytes());
          } catch (RuntimeException ignored) {
            rs = null;
          }
          locateNanos += Math.max(0L, System.nanoTime() - t0);
          calls++;
          if (cacheKey != null) {
            locateCache.put(cacheKey, rs == null ? "" : rs);
          }
        }
        if (rs == null || rs.isEmpty()) {
          rs = "shard:" + t.shard;
        }
        f.scanRsKeys.add(rs);
      }
      // Fetch affinity mirrors scan RS keys (deterministic round-robin later).
      f.getRsKeys.addAll(f.scanRsKeys);
    } else {
      f.scanRanges = estimateScanRanges(plan, ir);
      f.missingStats = true;
    }
    if (timing != null) {
      timing.regionLocateMs = Math.max(0L, locateNanos / 1_000_000L);
      timing.locateCalls = calls;
      timing.locateCacheHits = hits;
    }
    fillFromStatsAndPlan(f, plan, ir, phys);
    return f;
  }

  private static String locateCacheKey(String table, String startHex) {
    if (table == null) {
      return null;
    }
    return table + "|" + (startHex == null ? "" : startHex);
  }

  /**
   * Fast: approximate scan ranges from IR predicates × shards (design §13:
   * Fast may omit fine physical ranges). Does <b>not</b> compile PhysicalPlan.
   * When a live {@link RegionMapping} is present, locates coarse per-shard starts;
   * otherwise tags {@code region_mapping_coarse} (never silently invents RS ids as live).
   */
  public CostFeatures extractFast(PlanEnvelope plan, BoundIr ir) {
    CostFeatures f = base(plan);
    f.scanRanges = estimateScanRanges(plan, ir);
    fillCoarseRsKeys(f);
    fillFromStatsAndPlan(f, plan, ir, null);
    return f;
  }

  /** Coarse RS affinity: locate shard-prefix rows when mapping is available. */
  private void fillCoarseRsKeys(CostFeatures f) {
    int shards = layout != null ? Math.max(1, layout.shardCount) : Math.max(1, f.shardCountHint);
    boolean anyLive = false;
    boolean anyMiss = false;
    for (int s = 0; s < shards; s++) {
      byte[] start = new byte[] {(byte) (s & 0xFF)};
      String table = layout != null && layout.tableRaw != null ? layout.tableRaw : "traj_raw";
      String rs = null;
      try {
        rs = regionMapping.locate(table, start);
      } catch (RuntimeException ignored) {
        rs = null;
      }
      if (rs == null || rs.isEmpty() || regionMapping.missingRegionMap()) {
        anyMiss = true;
        rs = "shard:" + s;
      } else {
        anyLive = true;
      }
      f.scanRsKeys.add(rs);
    }
    f.getRsKeys.addAll(f.scanRsKeys);
    if (anyMiss || !anyLive) {
      f.extras.put("region_mapping_coarse", Boolean.TRUE);
      if (regionMapping.missingRegionMap()) {
        f.extras.put("missing_region_map", Boolean.TRUE);
      }
    }
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

  private void fillFromStatsAndPlan(CostFeatures f, PlanEnvelope plan, BoundIr ir,
                                    PhysicalPlan phys) {
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
      SumResult hr = sumHashPostings(ir);
      hashRows = hr.sum;
      if (hr.missing) {
        f.missingStats = true;
      }
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
    } else if (shape.intersect && shape.hasTime && shape.hasZ && shape.hasHash) {
      cand = estimateIntersectCandidates(ir, true, true, true);
    } else if (shape.intersect && shape.hasTime && shape.hasZ) {
      cand = estimateIntersectCandidates(ir, true, true, false);
    } else if (shape.intersect && shape.hasTime && shape.hasHash) {
      cand = estimateIntersectCandidates(ir, true, false, true);
    } else if (shape.intersect && shape.hasZ && shape.hasHash) {
      cand = estimateIntersectCandidates(ir, false, true, true);
    } else if (shape.hasTime && !shape.hasZ && !shape.hasHash) {
      cand = estimateSingleIndexCandidates(ir, true, false);
    } else if (shape.hasZ && !shape.hasTime && !shape.hasHash) {
      cand = estimateSingleIndexCandidates(ir, false, true);
    } else if (shape.hasHash && !shape.hasTime && !shape.hasZ) {
      cand = Math.max(1L, hashRows);
    } else {
      // Remaining shapes (e.g. FULL+index): still use sample-consistent intersect when possible.
      cand = estimateIntersectCandidates(ir, shape.hasTime, shape.hasZ, shape.hasHash);
      if (stats == null || stats.sample_chunks == null || stats.sample_chunks.isEmpty()) {
        f.missingStats = true;
      }
    }
    f.estCandidateChunks = Math.max(1L, cand);

    f.estRawBytes = (long) Math.ceil(f.estCandidateChunks * f.avgChunkBytes);
    resolveFilterPassRate(f, ir);
    double pass = f.missingStats ? 1.0 : f.filterPassRate; // conservative when missing
    f.estEligibleTrajs = Math.max(1L,
        (long) Math.ceil(f.estCandidateChunks * pass * f.chunksToTraj));

    boolean topK = ir != null && ir.result != null && "TOP_K".equals(ir.result.mode);
    f.needsReconstruct = topK;
    f.topK = (topK && ir.result.k != null) ? ir.result.k.intValue() : 0;
    if (layout != null) {
      f.shardCountHint = layout.shardCount;
    }
    if (topK) {
      if (ir.similarity == null || ir.similarity.metric == null || ir.similarity.metric.isEmpty()) {
        f.missingStats = true;
        f.simMetric = null;
        f.estDtwCells = 0L;
        f.extras.put("sim_metric_missing", Boolean.TRUE);
      } else {
        f.simMetric = ir.similarity.metric;
        double refLen = resolveRefLen(f, ir);
        f.estDtwCells = (long) Math.ceil(f.estEligibleTrajs * f.avgTrajLen * refLen);
        f.extras.put("ref_len", Double.valueOf(refLen));
      }
    } else {
      f.estDtwCells = 0L;
    }

    // Soft spill + geometry work units for §13.4 reserved terms.
    f.estSpillBytes = Math.max(0L, f.estRawBytes - softMemoryBytes);
    if (ir != null && ir.spatial != null) {
      f.estGeometryOps = Math.max(1L,
          (long) Math.ceil(f.estCandidateChunks * f.avgPointsPerChunk));
    } else {
      f.estGeometryOps = 0L;
    }
    f.mergeImpl = mergeImplFromPlan(plan);

    int caching = scanCachingRows(phys);
    double rowsPerRange = f.scanRanges > 0
        ? (double) f.estIndexRows / (double) f.scanRanges
        : (double) f.estIndexRows;
    f.rpcPerRange = Math.max(1.0, Math.ceil(rowsPerRange / (double) Math.max(1, caching)));
    f.rpcEst = f.scanRanges * f.rpcPerRange;
    f.estFetchGets = Math.max(1L, (f.estCandidateChunks + 499) / 500);
    // ExactST examines every point in candidate chunks (§13.4 P_hat_tested).
    f.estExactPoints = (long) Math.ceil(f.estCandidateChunks * f.avgPointsPerChunk);
    f.estSetBytes = f.estRawBytes;
    if (f.needsReconstruct) {
      f.estMetaGets = Math.max(1L, f.estEligibleTrajs);
      double chunksPerTraj = f.avgTrajLen / Math.max(1.0, f.avgPointsPerChunk);
      f.estReconChunks = Math.max(1L, (long) Math.ceil(f.estEligibleTrajs * chunksPerTraj));
      // FETCH already loads candidate chunks into the executor cache (§13.4 reuse).
      long cachedRecon = Math.min(f.estReconChunks, Math.max(0L, f.estCandidateChunks));
      long uncachedChunks = Math.max(0L, f.estReconChunks - cachedRecon);
      f.estUncachedReconBytes = (long) Math.ceil(uncachedChunks * f.avgChunkBytes);
      f.extras.put("cached_recon_chunks", Long.valueOf(cachedRecon));
      f.extras.put("uncached_recon_chunks", Long.valueOf(uncachedChunks));
      f.extras.put("scan_caching", Integer.valueOf(caching));
    }

    f.extras.put("time_index_rows", Long.valueOf(timeRows));
    f.extras.put("z_index_rows", Long.valueOf(zRows));
    f.extras.put("hash_index_rows", Long.valueOf(hashRows));
    f.extras.put("access", shape.branches.toString());
  }

  /** HBase Scan caching (rows per RPC page); matches ScanTask / Coordinator default. */
  private int scanCachingRows(PhysicalPlan phys) {
    if (phys != null && phys.scanTasks != null && !phys.scanTasks.isEmpty()) {
      int sum = 0;
      int n = 0;
      for (ScanTask t : phys.scanTasks) {
        if (t != null && t.caching > 0) {
          sum += t.caching;
          n++;
        }
      }
      if (n > 0) {
        return Math.max(1, sum / n);
      }
    }
    return 1000;
  }

  private static String mergeImplFromPlan(PlanEnvelope plan) {
    if (plan == null || plan.nodes == null) {
      return null;
    }
    for (PlanNode n : plan.nodes) {
      if (n != null && n.op == Op.INTERSECT && n.params != null
          && n.params.get("merge") instanceof String) {
        if (Boolean.TRUE.equals(n.params.get("provisional_merge"))) {
          return null; // incomplete search state — CostModel treats as HASH_SET provisionally via null
        }
        return (String) n.params.get("merge");
      }
    }
    return null;
  }

  /**
   * Prefer published ExactSTFilter stats ({@code filter_pass_*}). Never invent a rate
   * from index posting overlap — that is not point-level ExactSTFilter (§13.3).
   */
  private void resolveFilterPassRate(CostFeatures f, BoundIr ir) {
    if (stats != null && stats.filter_pass_rate >= 0.0 && stats.filter_pass_samples > 0) {
      f.filterPassRate = clamp01(stats.filter_pass_rate);
      f.extras.put("filter_pass_source", "stats");
      f.extras.put("filter_pass_samples", Long.valueOf(stats.filter_pass_samples));
      return;
    }
    // Conservative prior + HIGH uncertainty (no ExactST sample available yet).
    f.filterPassRate = 1.0;
    f.missingStats = true;
    f.extras.put("filter_pass_source", "conservative_prior");
    f.extras.put("filter_pass_uncertainty", Double.valueOf(1.0));
  }

  private static double clamp01(double v) {
    if (v < 0.0) {
      return 0.0;
    }
    if (v > 1.0) {
      return 1.0;
    }
    return v;
  }

  private long estimateIntersectCandidates(BoundIr ir) {
    return estimateIntersectCandidates(ir, true, true, false);
  }

  private long estimateIntersectCandidates(BoundIr ir, boolean time, boolean z, boolean hash) {
    if (stats == null || stats.sample_chunks == null || stats.sample_chunks.isEmpty()) {
      // No consistent sample: do NOT use independence / geometric mean (§13.3).
      // Conservative upper bound = min(branch postings); caller marks missingStats.
      long min = Long.MAX_VALUE;
      if (time && ir != null && ir.temporal != null) {
        min = Math.min(min, Math.max(1L,
            sumTimePostings(ir.temporal.start_ms, ir.temporal.end_ms).sum));
      }
      if (z && ir != null && ir.spatial != null) {
        min = Math.min(min, Math.max(1L, sumZPostings(ir.spatial).sum));
      }
      if (hash) {
        min = Math.min(min, Math.max(1L, sumHashPostings(ir).sum));
      }
      return min == Long.MAX_VALUE ? Math.max(1L, conservativeTotalPostings()) : min;
    }
    Set<Long> timeBuckets = time ? timeBucketSet(ir) : null;
    Set<Long> zCells = z ? zCellSet(ir) : null;
    String veh = hash ? vehicleEq(ir) : null;
    int hit = 0;
    for (StatsSnapshot.SampleChunk sc : stats.sample_chunks) {
      if (time && !hitsTime(sc, timeBuckets, ir)) {
        continue;
      }
      if (z && !hitsZ(sc, zCells, ir)) {
        continue;
      }
      if (hash && !hitsVehicle(sc, veh)) {
        continue;
      }
      hit++;
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
        ok = hitsTime(sc, timeBuckets, ir);
      }
      if (ok && z) {
        ok = hitsZ(sc, zCells, ir);
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
    if (sc.time_buckets != null) {
      for (Long b : sc.time_buckets) {
        if (buckets.contains(b)) {
          return true;
        }
      }
    }
    return false;
  }

  /**
   * Sample hit for temporal predicate: bucket membership, else envelope overlap with IR
   * when MBR/time metadata is present (§13.3).
   */
  private static boolean hitsTime(StatsSnapshot.SampleChunk sc, Set<Long> buckets, BoundIr ir) {
    if (hitsTime(sc, buckets)) {
      return true;
    }
    if (ir == null || ir.temporal == null) {
      return buckets == null || buckets.isEmpty();
    }
    // Envelope overlap with half-open query window.
    return sc.t_max_ms >= ir.temporal.start_ms && sc.t_min_ms < ir.temporal.end_ms;
  }

  private static boolean hitsZ(StatsSnapshot.SampleChunk sc, Set<Long> cells) {
    if (cells == null || cells.isEmpty()) {
      return true;
    }
    if (sc.z_cells != null) {
      for (Long z : sc.z_cells) {
        if (cells.contains(z)) {
          return true;
        }
      }
    }
    return false;
  }

  /** Sample hit for spatial: Z-cell membership, else MBR overlap. */
  private static boolean hitsZ(StatsSnapshot.SampleChunk sc, Set<Long> cells, BoundIr ir) {
    if (hitsZ(sc, cells)) {
      return true;
    }
    if (ir == null || ir.spatial == null) {
      return cells == null || cells.isEmpty();
    }
    return sc.max_x >= ir.spatial.min_x && sc.min_x <= ir.spatial.max_x
        && sc.max_y >= ir.spatial.min_y && sc.min_y <= ir.spatial.max_y;
  }

  private static boolean hitsVehicle(StatsSnapshot.SampleChunk sc, String vehicleId) {
    if (vehicleId == null || vehicleId.isEmpty()) {
      return true;
    }
    return vehicleId.equals(sc.vehicle_id);
  }

  private static String vehicleEq(BoundIr ir) {
    if (ir == null || ir.predicates == null) {
      return null;
    }
    for (BoundIr.Predicate p : ir.predicates) {
      if (p != null && "vehicle_id".equals(p.field) && "EQ".equals(p.op)) {
        return p.value;
      }
    }
    return null;
  }

  private SumResult sumHashPostings(BoundIr ir) {
    String veh = vehicleEq(ir);
    if (veh == null || veh.isEmpty()) {
      return SumResult.missing(conservativeTotalPostings());
    }
    if (stats == null || stats.vehicle_hash_posting_counts == null
        || stats.vehicle_hash_posting_counts.isEmpty()) {
      return SumResult.missing(conservativeTotalPostings());
    }
    String key = VehicleHash.hex128(veh);
    Long v = stats.vehicle_hash_posting_counts.get(key);
    if (v == null) {
      return SumResult.of(0L);
    }
    return SumResult.of(v.longValue());
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

  /**
   * Reference trajectory length for DTW cell estimate (design §13.2 / §10).
   * Prefer catalog meta {@code reference_chunk_count} × avg points/chunk;
   * else sample_chunks for that tid; else avg traj length with missingStats.
   */
  private double resolveRefLen(CostFeatures f, BoundIr ir) {
    if (ir != null && ir.similarity != null
        && ir.similarity.reference_chunk_count != null
        && ir.similarity.reference_chunk_count.intValue() > 0) {
      double pts = f.avgPointsPerChunk > 0 ? f.avgPointsPerChunk : 64.0;
      return Math.max(1.0, ir.similarity.reference_chunk_count.intValue() * pts);
    }
    if (ir != null && ir.similarity != null && stats != null && stats.sample_chunks != null) {
      long tid = ir.similarity.reference_tid;
      int chunks = 0;
      for (StatsSnapshot.SampleChunk sc : stats.sample_chunks) {
        if (sc != null && sc.tid == tid) {
          chunks++;
        }
      }
      if (chunks > 0) {
        double pts = f.avgPointsPerChunk > 0 ? f.avgPointsPerChunk : 64.0;
        return Math.max(1.0, chunks * pts);
      }
    }
    f.missingStats = true;
    return f.avgTrajLen > 0 ? f.avgTrajLen : 64.0;
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

    static SumResult of(long sum) {
      SumResult r = new SumResult();
      r.sum = sum;
      return r;
    }

    static SumResult missing(long conservativeSum) {
      SumResult r = new SumResult();
      r.sum = conservativeSum;
      r.missing = true;
      return r;
    }
  }
}
