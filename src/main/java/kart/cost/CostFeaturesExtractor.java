package kart.cost;

import kart.catalog.StatsSnapshot;
import kart.codec.CodeRange;
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
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
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
  /** Sorted code→count views for ADV posting maps (identity-keyed; stats maps are stable). */
  private final Map<Map<String, Long>, SortedCodeCounts> sortedCodeCache =
      new IdentityHashMap<Map<String, Long>, SortedCodeCounts>();
  /** Budgeted range lists are reused across candidate plans for the same query. */
  private final Map<BudgetedRangesKey, List<CodeRange>> budgetedCodeRangeCache =
      new HashMap<BudgetedRangesKey, List<CodeRange>>();
  /** Memo for sample/posting candidate estimates within one extractor (one query FastCost). */
  private final Map<String, Long> candidateEstCache = new HashMap<String, Long>();
  /** Exact posting sums are query/range-specific and reused across candidate plan scoring. */
  private final Map<List<CodeRange>, Map<SampleCodeFamily, SumResult>> codePostingEstimateCache =
      new IdentityHashMap<List<CodeRange>, Map<SampleCodeFamily, SumResult>>();
  /**
   * Process-wide SFC/XZT range memo (generation dominates ADV FastCost). Bounded;
   * shared across FastCost instances in a warm suite JVM.
   */
  private static final Map<String, List<CodeRange>> GLOBAL_CODE_RANGE_CACHE =
      Collections.synchronizedMap(new LinkedHashMap<String, List<CodeRange>>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, List<CodeRange>> eldest) {
          return size() > 256;
        }
      });

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
    long xztRows = 0L;
    long xzRows = 0L;
    long tsRows = 0L;
    long hashRows = 0L;
    long fullRows = 0L;

    if (shape.usedXzt && ir != null && BoundIr.hasTemporal(ir)) {
      SumResult sr = sumXztPostingsIr(ir);
      xztRows = sr.sum;
      timeRows = xztRows;
      if (sr.missing) {
        f.missingStats = true;
      }
    } else if (shape.hasTime && ir != null && BoundIr.hasTemporal(ir)) {
      SumResult sr = sumTimePostingsIr(ir);
      timeRows = sr.sum;
      if (sr.missing) {
        f.missingStats = true;
      }
    }
    if (shape.usedXz && ir != null && BoundIr.hasSpatial(ir)) {
      SumResult sr = sumXzPostingsIr(ir);
      xzRows = sr.sum;
      zRows = xzRows;
      if (sr.missing) {
        f.missingStats = true;
      }
    } else if (shape.usedTshape && ir != null && BoundIr.hasSpatial(ir)) {
      SumResult sr = sumTshapePostingsIr(ir);
      tsRows = sr.sum;
      zRows = tsRows;
      if (sr.missing) {
        f.missingStats = true;
      }
    } else if (shape.hasZ && ir != null && BoundIr.hasSpatial(ir)) {
      SumResult sr = sumZPostingsIr(ir);
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
    long preciseIndexRows = f.estIndexRows;
    f.estIndexRowsAfterCodeFilter = preciseIndexRows;
    f.estIndexRows = putAdvancedRangeBudgetExtras(
        f, shape, ir, preciseIndexRows, xzRows, tsRows, xztRows);

    long cand;
    if (shape.hasFull && !shape.hasTime && !shape.hasZ && !shape.hasHash) {
      cand = Math.max(1L, fullRows);
    } else if (shape.intersect && shape.hasTime && shape.hasZ && shape.hasHash) {
      cand = estimateIntersectCandidates(ir, shape, true, true, true);
    } else if (shape.intersect && shape.hasTime && shape.hasZ) {
      cand = estimateIntersectCandidates(ir, shape, true, true, false);
    } else if (shape.intersect && shape.hasTime && shape.hasHash) {
      cand = estimateIntersectCandidates(ir, shape, true, false, true);
    } else if (shape.intersect && shape.hasZ && shape.hasHash) {
      cand = estimateIntersectCandidates(ir, shape, false, true, true);
    } else if (shape.hasTime && !shape.hasZ && !shape.hasHash) {
      cand = estimateSingleIndexCandidates(ir, shape, true, false);
    } else if (shape.hasZ && !shape.hasTime && !shape.hasHash) {
      cand = estimateSingleIndexCandidates(ir, shape, false, true);
    } else if (shape.hasHash && !shape.hasTime && !shape.hasZ) {
      cand = Math.max(1L, hashRows);
    } else {
      // Remaining shapes (e.g. FULL+index): still use sample-consistent intersect when possible.
      cand = estimateIntersectCandidates(ir, shape, shape.hasTime, shape.hasZ, shape.hasHash);
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
    if (ir != null && BoundIr.hasSpatial(ir)) {
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

    f.extras.put("time_index_rows", Long.valueOf(shape.usedXzt ? 0L : timeRows));
    f.extras.put("z_index_rows", Long.valueOf(
        (shape.usedXz || shape.usedTshape) ? 0L : zRows));
    f.extras.put("xzt_index_rows", Long.valueOf(xztRows));
    f.extras.put("xz_index_rows", Long.valueOf(xzRows));
    f.extras.put("tshape_index_rows", Long.valueOf(tsRows));
    f.extras.put("hash_index_rows", Long.valueOf(hashRows));
    f.extras.put("access", shape.branches.toString());
  }

  /**
   * Separate physical scan postings (budget-merged ranges) from rows retained
   * by the precise-code filter. Both estimates follow the adapters' range budget.
   */
  private long putAdvancedRangeBudgetExtras(CostFeatures f, AccessShape shape, BoundIr ir,
                                            long preciseIndexRows, long xzPrecise,
                                            long tsPrecise, long xztPrecise) {
    if (!usesAdvancedShape(shape) || layout == null) {
      return preciseIndexRows;
    }
    int budget = Math.max(1, layout.maxXzRanges);
    long advancedPreciseRows = 0L;
    long advancedScannedRows = 0L;
    long preciseRangeCount = 0L;
    long scanRangeCount = 0L;
    if (shape.usedXz && ir != null && BoundIr.hasSpatial(ir)) {
      List<CodeRange> ranges = cachedXzRangesIr(ir);
      SumResult scan = recordAdvancedBranchEstimate(f, "xz", ranges,
          stats != null ? stats.xz_code_posting_counts : null, SampleCodeFamily.XZ,
          xzPrecise, budget);
      advancedPreciseRows += xzPrecise;
      advancedScannedRows += Math.max(xzPrecise, scan.sum);
      preciseRangeCount += ranges.size();
      scanRangeCount += scan.physicalRangeCount;
    }
    if (shape.usedTshape && ir != null && BoundIr.hasSpatial(ir)) {
      List<CodeRange> ranges = cachedTshapeRangesIr(ir);
      SumResult scan = recordAdvancedBranchEstimate(f, "tshape", ranges,
          stats != null ? stats.tshape_code_posting_counts : null, SampleCodeFamily.TSHAPE,
          tsPrecise, budget);
      advancedPreciseRows += tsPrecise;
      advancedScannedRows += Math.max(tsPrecise, scan.sum);
      preciseRangeCount += ranges.size();
      scanRangeCount += scan.physicalRangeCount;
    }
    if (shape.usedXzt && ir != null && BoundIr.hasTemporal(ir)) {
      List<CodeRange> ranges = cachedXztRangesIr(ir);
      SumResult scan = recordAdvancedBranchEstimate(f, "xzt", ranges,
          stats != null ? stats.xzt_code_posting_counts : null, SampleCodeFamily.XZT,
          xztPrecise, budget);
      advancedPreciseRows += xztPrecise;
      advancedScannedRows += Math.max(xztPrecise, scan.sum);
      preciseRangeCount += ranges.size();
      scanRangeCount += scan.physicalRangeCount;
    }
    if (advancedPreciseRows == 0L && advancedScannedRows == 0L) {
      return preciseIndexRows;
    }
    f.extras.put("precise_code_ranges", Long.valueOf(preciseRangeCount));
    f.extras.put("merged_scan_ranges", Long.valueOf(scanRangeCount));
    f.extras.put("est_postings_precise", Long.valueOf(advancedPreciseRows));
    f.extras.put("est_postings_merged", Long.valueOf(advancedScannedRows));
    long nonAdvancedRows = Math.max(0L, preciseIndexRows - advancedPreciseRows);
    return nonAdvancedRows + advancedScannedRows;
  }

  private SumResult recordAdvancedBranchEstimate(CostFeatures f, String name,
                                                  List<CodeRange> preciseRanges,
                                                  Map<String, Long> counts,
                                                  SampleCodeFamily family,
                                                  long preciseRows, int budget) {
    List<CodeRange> physicalRanges = cachedBudgetedRanges(preciseRanges, budget, family);
    SumResult scanned = sumCodeRangePostings(counts, physicalRanges, family);
    f.extras.put(name + "_precise_range_count", Integer.valueOf(preciseRanges.size()));
    f.extras.put(name + "_scan_range_count", Integer.valueOf(physicalRanges.size()));
    f.extras.put(name + "_estimated_postings_after_code_filter", Long.valueOf(preciseRows));
    f.extras.put(name + "_estimated_postings_scanned", Long.valueOf(Math.max(preciseRows, scanned.sum)));
    f.extras.put(name + "_posting_count_source",
        scanned.sampleBased ? "SAMPLE_EXTRAPOLATION"
            : (counts != null && !counts.isEmpty() ? "POSTING_HISTOGRAM" : "RANGE_UPPER_BOUND"));
    f.extras.put(name + "_gap_merge_source", gapCost(family) != null
        ? "POSTING_HISTOGRAM" : "CODE_GAP_LENGTH_FALLBACK");
    scanned.physicalRangeCount = physicalRanges.size();
    if (scanned.sampleBased) {
      f.missingStats = true;
      f.extras.put(name + "_sample_matches_scanned", Integer.valueOf(scanned.sampleMatches));
      f.extras.put(name + "_sample_size", Integer.valueOf(scanned.sampleSize));
      f.extras.put(name + "_sample_rate", Double.valueOf(scanned.sampleRate));
    }
    return scanned;
  }

  private static int physicalRangeCount(List<CodeRange> preciseRanges, int budget) {
    return Math.min(Math.max(0, budget), preciseRanges == null ? 0 : preciseRanges.size());
  }

  private List<CodeRange> cachedBudgetedRanges(List<CodeRange> preciseRanges, int budget,
                                               SampleCodeFamily family) {
    BudgetedRangesKey key = new BudgetedRangesKey(preciseRanges, budget, family);
    List<CodeRange> cached = budgetedCodeRangeCache.get(key);
    if (cached != null) {
      return cached;
    }
    List<CodeRange> merged = kart.compile.CodeRangeBudget
        .mergeToBudgetGapAware(preciseRanges, budget, gapCost(family));
    budgetedCodeRangeCache.put(key, merged);
    return merged;
  }

  private kart.compile.CodeRangeBudget.GapCost gapCost(SampleCodeFamily family) {
    if (layout == null || family == null) {
      return null;
    }
    switch (family) {
      case XZ:
        return layout.xzPostingGapCost();
      case TSHAPE:
        return layout.tshapePostingGapCost();
      case XZT:
        return layout.xztPostingGapCost();
      default:
        return null;
    }
  }

  private static final class BudgetedRangesKey {
    final List<CodeRange> ranges;
    final int budget;
    final SampleCodeFamily family;

    BudgetedRangesKey(List<CodeRange> ranges, int budget, SampleCodeFamily family) {
      this.ranges = ranges;
      this.budget = budget;
      this.family = family;
    }

    @Override
    public boolean equals(Object other) {
      if (!(other instanceof BudgetedRangesKey)) {
        return false;
      }
      BudgetedRangesKey that = (BudgetedRangesKey) other;
      return ranges == that.ranges && budget == that.budget && family == that.family;
    }

    @Override
    public int hashCode() {
      int h = System.identityHashCode(ranges);
      h = 31 * h + budget;
      return 31 * h + (family == null ? 0 : family.hashCode());
    }
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

  private long estimateIntersectCandidates(BoundIr ir, AccessShape shape,
                                           boolean time, boolean z, boolean hash) {
    String ck = candidateCacheKey("I", ir, shape, time, z, hash);
    Long hitCached = candidateEstCache.get(ck);
    if (hitCached != null) {
      return hitCached.longValue();
    }
    long value = estimateIntersectCandidatesUncached(ir, shape, time, z, hash);
    candidateEstCache.put(ck, Long.valueOf(value));
    return value;
  }

  private long estimateIntersectCandidatesUncached(BoundIr ir, AccessShape shape,
                                                   boolean time, boolean z, boolean hash) {
    // ADV shapes: sample×SFC-range scans dominate FastCost; use posting mins instead.
    if (stats == null || stats.sample_chunks == null || stats.sample_chunks.isEmpty()
        || (advancedFastPostingOnly() && usesAdvancedShape(shape))) {
      // No consistent sample: do NOT use independence / geometric mean (§13.3).
      // Conservative upper bound = min(branch postings); caller marks missingStats.
      long min = Long.MAX_VALUE;
      if (time && ir != null && BoundIr.hasTemporal(ir)) {
        long rows = shape != null && shape.usedXzt
            ? sumXztPostingsIr(ir).sum
            : sumTimePostingsIr(ir).sum;
        min = Math.min(min, Math.max(1L, rows));
      }
      if (z && ir != null && BoundIr.hasSpatial(ir)) {
        long rows;
        if (shape != null && shape.usedXz) {
          rows = sumXzPostingsIr(ir).sum;
        } else if (shape != null && shape.usedTshape) {
          rows = sumTshapePostingsIr(ir).sum;
        } else {
          rows = sumZPostingsIr(ir).sum;
        }
        min = Math.min(min, Math.max(1L, rows));
      }
      if (hash) {
        min = Math.min(min, Math.max(1L, sumHashPostings(ir).sum));
      }
      return min == Long.MAX_VALUE ? Math.max(1L, conservativeTotalPostings()) : min;
    }
    Set<Long> timeBuckets = time && (shape == null || !shape.usedXzt) ? timeBucketSet(ir) : null;
    Set<Long> zCells = z && (shape == null || (!shape.usedXz && !shape.usedTshape))
        ? zCellSet(ir) : null;
    List<CodeRange> xztRanges = time && shape != null && shape.usedXzt && ir != null
        && ir.temporal != null && layout != null
        ? cachedXztRangesIr(ir) : null;
    List<CodeRange> xzRanges = z && shape != null && shape.usedXz && ir != null
        && BoundIr.hasSpatial(ir) && layout != null
        ? cachedXzRangesIr(ir) : null;
    List<CodeRange> tsRanges = z && shape != null && shape.usedTshape && ir != null
        && BoundIr.hasSpatial(ir) && layout != null
        ? cachedTshapeRangesIr(ir) : null;
    String veh = hash ? vehicleEq(ir) : null;
    int hit = 0;
    for (StatsSnapshot.SampleChunk sc : stats.sample_chunks) {
      if (time && !hitsTemporalBranch(sc, timeBuckets, xztRanges, ir, shape)) {
        continue;
      }
      if (z && !hitsSpatialBranch(sc, zCells, xzRanges, tsRanges, ir, shape)) {
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

  private long estimateSingleIndexCandidates(BoundIr ir, AccessShape shape,
                                             boolean time, boolean z) {
    String ck = candidateCacheKey("S", ir, shape, time, z, false);
    Long hitCached = candidateEstCache.get(ck);
    if (hitCached != null) {
      return hitCached.longValue();
    }
    long value = estimateSingleIndexCandidatesUncached(ir, shape, time, z);
    candidateEstCache.put(ck, Long.valueOf(value));
    return value;
  }

  private static String candidateCacheKey(String kind, BoundIr ir, AccessShape shape,
                                          boolean time, boolean z, boolean hash) {
    StringBuilder sb = new StringBuilder(64);
    sb.append(kind).append('|');
    sb.append(time ? 'T' : '-').append(z ? 'Z' : '-').append(hash ? 'H' : '-').append('|');
    if (shape != null) {
      sb.append(shape.usedXzt ? '1' : '0');
      sb.append(shape.usedXz ? '1' : '0');
      sb.append(shape.usedTshape ? '1' : '0');
      sb.append(shape.intersect ? 'I' : 'S');
    }
    sb.append('|');
    if (ir != null && BoundIr.hasTemporal(ir)) {
      java.util.List<BoundIr.Temporal> _tp = BoundIr.temporalsOf(ir);
      if (!_tp.isEmpty()) {
        sb.append(_tp.get(0).start_ms).append(':').append(_tp.get(_tp.size()-1).end_ms);
      }
    }
    sb.append('|');
    if (ir != null && BoundIr.hasSpatial(ir)) {
      BoundIr.Spatial _su = BoundIr.spatialUnion(ir);
      sb.append(_su.min_x).append(':').append(_su.min_y).append(':')
          .append(_su.max_x).append(':').append(_su.max_y);
    }
    return sb.toString();
  }

  private long estimateSingleIndexCandidatesUncached(BoundIr ir, AccessShape shape,
                                                     boolean time, boolean z) {
    if (stats == null || stats.sample_chunks == null || stats.sample_chunks.isEmpty()
        || (advancedFastPostingOnly() && usesAdvancedShape(shape))) {
      if (time && ir != null && BoundIr.hasTemporal(ir)) {
        if (shape != null && shape.usedXzt) {
          return Math.max(1L, sumXztPostingsIr(ir).sum);
        }
        return Math.max(1L, sumTimePostingsIr(ir).sum);
      }
      if (z && ir != null && BoundIr.hasSpatial(ir)) {
        if (shape != null && shape.usedXz) {
          return Math.max(1L, sumXzPostingsIr(ir).sum);
        }
        if (shape != null && shape.usedTshape) {
          return Math.max(1L, sumTshapePostingsIr(ir).sum);
        }
        return Math.max(1L, sumZPostingsIr(ir).sum);
      }
      return conservativeTotalPostings();
    }
    Set<Long> timeBuckets = time && (shape == null || !shape.usedXzt) ? timeBucketSet(ir) : null;
    Set<Long> zCells = z && (shape == null || (!shape.usedXz && !shape.usedTshape))
        ? zCellSet(ir) : null;
    List<CodeRange> xztRanges = time && shape != null && shape.usedXzt && ir != null
        && ir.temporal != null && layout != null
        ? cachedXztRangesIr(ir) : null;
    List<CodeRange> xzRanges = z && shape != null && shape.usedXz && ir != null
        && BoundIr.hasSpatial(ir) && layout != null
        ? cachedXzRangesIr(ir) : null;
    List<CodeRange> tsRanges = z && shape != null && shape.usedTshape && ir != null
        && BoundIr.hasSpatial(ir) && layout != null
        ? cachedTshapeRangesIr(ir) : null;
    int hit = 0;
    for (StatsSnapshot.SampleChunk sc : stats.sample_chunks) {
      boolean ok = true;
      if (time) {
        ok = hitsTemporalBranch(sc, timeBuckets, xztRanges, ir, shape);
      }
      if (ok && z) {
        ok = hitsSpatialBranch(sc, zCells, xzRanges, tsRanges, ir, shape);
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
    for (BoundIr.Temporal w : BoundIr.temporalsOf(ir)) {
      if (w != null && sc.t_max_ms >= w.start_ms && sc.t_min_ms < w.end_ms) {
        return true;
      }
    }
    return false;
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
    for (BoundIr.Spatial s : BoundIr.spatialsOf(ir)) {
      if (s != null && sc.max_x >= s.min_x && sc.min_x <= s.max_x
          && sc.max_y >= s.min_y && sc.min_y <= s.max_y) {
        return true;
      }
    }
    return false;
  }

  private static boolean hitsTemporalBranch(StatsSnapshot.SampleChunk sc, Set<Long> buckets,
                                            List<CodeRange> xztRanges, BoundIr ir,
                                            AccessShape shape) {
    if (shape != null && shape.usedXzt) {
      if (xztRanges != null && sc.xzt_code != null) {
        return codeInRanges(sc.xzt_code.longValue(), xztRanges);
      }
      return hitsTime(sc, buckets, ir);
    }
    return hitsTime(sc, buckets, ir);
  }

  private static boolean hitsSpatialBranch(StatsSnapshot.SampleChunk sc, Set<Long> cells,
                                           List<CodeRange> xzRanges, List<CodeRange> tsRanges,
                                           BoundIr ir, AccessShape shape) {
    if (shape != null && shape.usedXz) {
      if (xzRanges != null && sc.xz_code != null) {
        return codeInRanges(sc.xz_code.longValue(), xzRanges);
      }
      return hitsZ(sc, cells, ir);
    }
    if (shape != null && shape.usedTshape) {
      if (tsRanges != null && sc.tshape_code != null) {
        return codeInRanges(sc.tshape_code.longValue(), tsRanges);
      }
      return hitsZ(sc, cells, ir);
    }
    return hitsZ(sc, cells, ir);
  }

  /**
   * Membership test. When {@code ranges} is merge-sorted non-overlapping (see
   * {@link #mergeCodeRanges}), uses binary search; otherwise falls back to linear scan.
   */
  private static boolean codeInRanges(long code, List<CodeRange> ranges) {
    if (ranges == null || ranges.isEmpty()) {
      return false;
    }
    if (ranges.size() >= 4) {
      int lo = 0;
      int hi = ranges.size() - 1;
      while (lo <= hi) {
        int mid = (lo + hi) >>> 1;
        CodeRange r = ranges.get(mid);
        if (r == null) {
          return false;
        }
        if (code < r.lo) {
          hi = mid - 1;
        } else if (code > r.hi) {
          lo = mid + 1;
        } else {
          return true;
        }
      }
      return false;
    }
    for (int i = 0; i < ranges.size(); i++) {
      CodeRange r = ranges.get(i);
      if (r != null && code >= r.lo && code <= r.hi) {
        return true;
      }
    }
    return false;
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
        BoundIr.temporalsOf(ir).get(0).start_ms, BoundIr.temporalsOf(ir).get(BoundIr.temporalsOf(ir).size()-1).end_ms, layout.epochMs, layout.bucketMs);
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
        BoundIr.spatialUnion(ir).min_x, BoundIr.spatialUnion(ir).min_y, BoundIr.spatialUnion(ir).max_x, BoundIr.spatialUnion(ir).max_y,
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

  private SumResult sumXztPostings(long startMs, long endMs) {
    if (layout == null || layout.xztCodec() == null) {
      return SumResult.missing(conservativeTotalPostings());
    }
    return sumCodeRangePostings(stats != null ? stats.xzt_code_posting_counts : null,
        cachedXztRanges(startMs, endMs), SampleCodeFamily.XZT);
  }



  private SumResult sumXztPostingsIr(BoundIr ir) {
    java.util.List<BoundIr.Temporal> parts = BoundIr.temporalsOf(ir);
    if (parts.isEmpty()) {
      return SumResult.missing(conservativeTotalPostings());
    }
    if (parts.size() == 1) {
      return sumXztPostings(parts.get(0).start_ms, parts.get(0).end_ms);
    }
    SumResult total = new SumResult();
    for (BoundIr.Temporal w : parts) {
      SumResult r = sumXztPostings(w.start_ms, w.end_ms);
      total.sum += r.sum;
      total.missing |= r.missing;
    }
    return total;
  }

  private List<CodeRange> cachedXztRangesIr(BoundIr ir) {
    java.util.List<BoundIr.Temporal> parts = BoundIr.temporalsOf(ir);
    if (parts.isEmpty()) {
      return java.util.Collections.emptyList();
    }
    if (parts.size() == 1) {
      return cachedXztRanges(parts.get(0).start_ms, parts.get(0).end_ms);
    }
    java.util.List<CodeRange> all = new java.util.ArrayList<CodeRange>();
    for (BoundIr.Temporal w : parts) {
      all.addAll(cachedXztRanges(w.start_ms, w.end_ms));
    }
    return mergeCodeRanges(all);
  }

  private SumResult sumXzPostingsIr(BoundIr ir) {
    java.util.List<BoundIr.Spatial> parts = BoundIr.spatialsOf(ir);
    if (parts.isEmpty()) {
      return SumResult.missing(conservativeTotalPostings());
    }
    if (parts.size() == 1) {
      return sumXzPostings(parts.get(0));
    }
    SumResult total = new SumResult();
    for (BoundIr.Spatial s : parts) {
      SumResult r = sumXzPostings(s);
      total.sum += r.sum;
      total.missing |= r.missing;
    }
    return total;
  }

  private SumResult sumTshapePostingsIr(BoundIr ir) {
    java.util.List<BoundIr.Spatial> parts = BoundIr.spatialsOf(ir);
    if (parts.isEmpty()) {
      return SumResult.missing(conservativeTotalPostings());
    }
    if (parts.size() == 1) {
      return sumTshapePostings(parts.get(0));
    }
    SumResult total = new SumResult();
    for (BoundIr.Spatial s : parts) {
      SumResult r = sumTshapePostings(s);
      total.sum += r.sum;
      total.missing |= r.missing;
    }
    return total;
  }

  private SumResult sumZPostingsIr(BoundIr ir) {
    java.util.List<BoundIr.Spatial> parts = BoundIr.spatialsOf(ir);
    if (parts.isEmpty()) {
      SumResult r = new SumResult();
      r.missing = true;
      r.sum = conservativeTotalPostings();
      return r;
    }
    if (parts.size() == 1) {
      return sumZPostings(parts.get(0));
    }
    SumResult total = new SumResult();
    for (BoundIr.Spatial s : parts) {
      SumResult r = sumZPostings(s);
      total.sum += r.sum;
      total.missing |= r.missing;
    }
    return total;
  }

  private SumResult sumTimePostingsIr(BoundIr ir) {
    java.util.List<BoundIr.Temporal> parts = BoundIr.temporalsOf(ir);
    if (parts.isEmpty()) {
      return SumResult.missing(conservativeTotalPostings());
    }
    if (parts.size() == 1) {
      return sumTimePostings(parts.get(0).start_ms, parts.get(0).end_ms);
    }
    SumResult total = new SumResult();
    for (BoundIr.Temporal w : parts) {
      SumResult r = sumTimePostings(w.start_ms, w.end_ms);
      total.sum += r.sum;
      total.missing |= r.missing;
    }
    return total;
  }

  private List<CodeRange> cachedXzRangesIr(BoundIr ir) {
    java.util.List<BoundIr.Spatial> parts = BoundIr.spatialsOf(ir);
    if (parts.isEmpty()) {
      return java.util.Collections.emptyList();
    }
    if (parts.size() == 1) {
      return cachedXzRanges(parts.get(0));
    }
    java.util.List<CodeRange> all = new java.util.ArrayList<CodeRange>();
    for (BoundIr.Spatial s : parts) {
      all.addAll(cachedXzRanges(s));
    }
    return mergeCodeRanges(all);
  }

  private List<CodeRange> cachedTshapeRangesIr(BoundIr ir) {
    java.util.List<BoundIr.Spatial> parts = BoundIr.spatialsOf(ir);
    if (parts.isEmpty()) {
      return java.util.Collections.emptyList();
    }
    if (parts.size() == 1) {
      return cachedTshapeRanges(parts.get(0));
    }
    java.util.List<CodeRange> all = new java.util.ArrayList<CodeRange>();
    for (BoundIr.Spatial s : parts) {
      all.addAll(cachedTshapeRanges(s));
    }
    return mergeCodeRanges(all);
  }

  private SumResult sumXzPostings(BoundIr.Spatial spatial) {
    if (layout == null || layout.xzSfc() == null || spatial == null) {
      return SumResult.missing(conservativeTotalPostings());
    }
    return sumCodeRangePostings(stats != null ? stats.xz_code_posting_counts : null,
        cachedXzRanges(spatial), SampleCodeFamily.XZ);
  }

  private SumResult sumTshapePostings(BoundIr.Spatial spatial) {
    if (layout == null || layout.tshapeSfc() == null || spatial == null) {
      return SumResult.missing(conservativeTotalPostings());
    }
    return sumCodeRangePostings(stats != null ? stats.tshape_code_posting_counts : null,
        cachedTshapeRanges(spatial), SampleCodeFamily.TSHAPE);
  }

  private List<CodeRange> cachedXztRanges(long startMs, long endMs) {
    String key = rangeLayoutKey() + "|xzt|" + startMs + "|" + endMs;
    List<CodeRange> hit = GLOBAL_CODE_RANGE_CACHE.get(key);
    if (hit != null) {
      return hit;
    }
    List<CodeRange> ranges = mergeCodeRanges(layout.xztCodec().rangesCovering(startMs, endMs));
    GLOBAL_CODE_RANGE_CACHE.put(key, ranges);
    return ranges;
  }

  private List<CodeRange> cachedXzRanges(BoundIr.Spatial spatial) {
    String key = rangeLayoutKey() + "|xz|" + spatial.min_x + "|" + spatial.min_y + "|"
        + spatial.max_x + "|" + spatial.max_y;
    List<CodeRange> hit = GLOBAL_CODE_RANGE_CACHE.get(key);
    if (hit != null) {
      return hit;
    }
    List<CodeRange> ranges = mergeCodeRanges(layout.xzSfc().ranges(
        spatial.min_x, spatial.min_y, spatial.max_x, spatial.max_y));
    GLOBAL_CODE_RANGE_CACHE.put(key, ranges);
    return ranges;
  }

  private List<CodeRange> cachedTshapeRanges(BoundIr.Spatial spatial) {
    String key = rangeLayoutKey() + "|ts|" + spatial.min_x + "|" + spatial.min_y + "|"
        + spatial.max_x + "|" + spatial.max_y;
    List<CodeRange> hit = GLOBAL_CODE_RANGE_CACHE.get(key);
    if (hit != null) {
      return hit;
    }
    List<CodeRange> ranges = mergeCodeRanges(layout.tshapeSfc().ranges(
        spatial.min_x, spatial.min_y, spatial.max_x, spatial.max_y));
    GLOBAL_CODE_RANGE_CACHE.put(key, ranges);
    return ranges;
  }

  /** Static range memo must distinguish datasets/index layouts sharing query coordinates. */
  private String rangeLayoutKey() {
    if (layout == null || layout.domain == null) {
      return "layout:none";
    }
    return "layout:" + layout.xzLevel + ':' + layout.tshapeMaxDepth + ':' + layout.xztLevel
        + ':' + layout.epochMs + ':' + layout.bucketMs + ':' + layout.xztHorizonMs
        + ':' + Double.doubleToLongBits(layout.domain.minX)
        + ':' + Double.doubleToLongBits(layout.domain.minY)
        + ':' + Double.doubleToLongBits(layout.domain.maxX)
        + ':' + Double.doubleToLongBits(layout.domain.maxY);
  }

  private SumResult sumCodeRangePostings(Map<String, Long> counts, List<CodeRange> ranges,
                                         SampleCodeFamily family) {
    if (ranges == null || ranges.isEmpty()) {
      SumResult r = new SumResult();
      r.missing = true;
      r.sum = Math.max(1L, conservativeTotalPostings());
      return r;
    }
    Map<SampleCodeFamily, SumResult> byFamily = codePostingEstimateCache.get(ranges);
    if (byFamily != null) {
      SumResult cached = byFamily.get(family);
      if (cached != null) {
        return cached;
      }
    }
    SumResult r;
    if (counts == null || counts.isEmpty()) {
      r = estimateSampleCodePostings(ranges, family);
    } else {
      SortedCodeCounts sc = sortedCodeCounts(counts);
      // All callers pass normalized ranges from cachedX*Ranges or cachedBudgetedRanges.
      r = new SumResult();
      r.sum = sumSortedDisjointCodeRanges(sc, ranges);
    }
    if (byFamily == null) {
      byFamily = new HashMap<SampleCodeFamily, SumResult>();
      codePostingEstimateCache.put(ranges, byFamily);
    }
    byFamily.put(family, r);
    return r;
  }

  /**
   * Estimates code-range postings from the uniform-hash chunk sample when the
   * exact posting histogram is absent. Zero hits use a half-sample pseudocount,
   * and the caller marks the resulting estimate as uncertain.
   */
  private SumResult estimateSampleCodePostings(List<CodeRange> ranges, SampleCodeFamily family) {
    SumResult r = new SumResult();
    r.missing = true;
    r.sampleBased = true;
    r.sampleSize = 0;
    r.sampleRate = stats != null && stats.sample_rate > 0.0 ? stats.sample_rate : 0.01;
    if (stats == null || stats.sample_chunks == null || stats.sample_chunks.isEmpty()) {
      r.sum = Math.max(1L, (long) ranges.size() * 10_000L);
      r.sampleBased = false;
      return r;
    }
    int hits = 0;
    for (StatsSnapshot.SampleChunk sample : stats.sample_chunks) {
      Long code = sampleCode(sample, family);
      if (code != null) {
        r.sampleSize++;
        if (codeInRanges(code.longValue(), ranges)) {
          hits++;
        }
      }
    }
    if (r.sampleSize != stats.sample_chunks.size()) {
      // A legacy/partial snapshot cannot support a valid extrapolation.
      r.sampleBased = false;
      r.sum = Math.max(1L, (long) ranges.size() * 10_000L);
      r.sampleMatches = hits;
      return r;
    }
    r.sampleMatches = hits;
    double estimated = hits > 0 ? hits / r.sampleRate : 0.5 / r.sampleRate;
    r.sum = Math.max(1L, (long) Math.ceil(estimated));
    return r;
  }

  private static Long sampleCode(StatsSnapshot.SampleChunk sample, SampleCodeFamily family) {
    if (sample == null || family == null) {
      return null;
    }
    switch (family) {
      case XZ:
        return sample.xz_code;
      case TSHAPE:
        return sample.tshape_code;
      case XZT:
        return sample.xzt_code;
      default:
        return null;
    }
  }

  private static boolean usesAdvancedShape(AccessShape shape) {
    return shape != null && (shape.usedXzt || shape.usedXz || shape.usedTshape);
  }

  /**
   * When advanced indexes are enabled, FastCost skips sample_chunks for ADV shapes
   * (SFC range × samples) and uses posting-count mins. Override with
   * {@code KART_FAST_COST_POSTING_ONLY=0/1}.
   */
  static boolean advancedFastPostingOnly() {
    String o = System.getenv("KART_FAST_COST_POSTING_ONLY");
    if (o != null && !o.trim().isEmpty()) {
      o = o.trim().toLowerCase();
      if (o.equals("0") || o.equals("false") || o.equals("off") || o.equals("no")) {
        return false;
      }
      if (o.equals("1") || o.equals("true") || o.equals("on") || o.equals("yes")) {
        return true;
      }
    }
    String v = System.getenv("KART_ADVANCED_INDEXES");
    if (v == null || v.isEmpty()) {
      return false;
    }
    v = v.trim().toLowerCase();
    return v.equals("1") || v.equals("true") || v.equals("on") || v.equals("yes");
  }

  private SortedCodeCounts sortedCodeCounts(Map<String, Long> counts) {
    SortedCodeCounts cached = sortedCodeCache.get(counts);
    if (cached != null) {
      return cached;
    }
    SortedCodeCounts built = SortedCodeCounts.from(counts);
    sortedCodeCache.put(counts, built);
    return built;
  }

  /**
   * Sum posting counts for codes covered by {@code ranges} using sorted keys + binary search.
   * Overlapping ranges are merged so each code is counted at most once (same as full-map scan).
   */
  static long sumSortedCodeRanges(SortedCodeCounts sc, List<CodeRange> ranges) {
    if (sc == null || sc.codes.length == 0 || ranges == null || ranges.isEmpty()) {
      return 0L;
    }
    return sumSortedDisjointCodeRanges(sc, mergeCodeRanges(ranges));
  }

  /** Sum a sorted, disjoint range list using prefix counts; inclusive endpoints. */
  private static long sumSortedDisjointCodeRanges(SortedCodeCounts sc,
                                                  List<CodeRange> sortedDisjoint) {
    if (sc == null || sc.codes.length == 0 || sortedDisjoint == null || sortedDisjoint.isEmpty()) {
      return 0L;
    }
    long sum = 0L;
    for (int ri = 0; ri < sortedDisjoint.size(); ri++) {
      CodeRange cr = sortedDisjoint.get(ri);
      int from = lowerBound(sc.codes, cr.lo);
      int to = upperBound(sc.codes, cr.hi);
      if (to > from) {
        sum += sc.prefixCounts[to] - sc.prefixCounts[from];
      }
    }
    return sum;
  }

  static List<CodeRange> mergeCodeRanges(List<CodeRange> ranges) {
    List<CodeRange> sorted = new ArrayList<CodeRange>(ranges.size());
    for (int i = 0; i < ranges.size(); i++) {
      CodeRange r = ranges.get(i);
      if (r != null && r.lo <= r.hi) {
        sorted.add(r);
      }
    }
    if (sorted.isEmpty()) {
      return sorted;
    }
    Collections.sort(sorted, new Comparator<CodeRange>() {
      @Override
      public int compare(CodeRange a, CodeRange b) {
        if (a.lo < b.lo) {
          return -1;
        }
        if (a.lo > b.lo) {
          return 1;
        }
        return a.hi < b.hi ? -1 : (a.hi > b.hi ? 1 : 0);
      }
    });
    List<CodeRange> out = new ArrayList<CodeRange>(sorted.size());
    CodeRange cur = sorted.get(0);
    for (int i = 1; i < sorted.size(); i++) {
      CodeRange n = sorted.get(i);
      if (n.lo <= cur.hi || (cur.hi != Long.MAX_VALUE && n.lo == cur.hi + 1L)) {
        if (n.hi > cur.hi) {
          cur = new CodeRange(cur.lo, n.hi, cur.contained && n.contained);
        }
      } else {
        out.add(cur);
        cur = n;
      }
    }
    out.add(cur);
    return out;
  }

  static int lowerBound(long[] codes, long key) {
    int lo = 0;
    int hi = codes.length;
    while (lo < hi) {
      int mid = (lo + hi) >>> 1;
      if (codes[mid] < key) {
        lo = mid + 1;
      } else {
        hi = mid;
      }
    }
    return lo;
  }

  static int upperBound(long[] codes, long key) {
    int lo = 0;
    int hi = codes.length;
    while (lo < hi) {
      int mid = (lo + hi) >>> 1;
      if (codes[mid] <= key) {
        lo = mid + 1;
      } else {
        hi = mid;
      }
    }
    return lo;
  }

  /** Immutable sorted parallel arrays for string-keyed posting maps. */
  static final class SortedCodeCounts {
    final long[] codes;
    final long[] counts;
    final long[] prefixCounts;
    final long maxValue;

    SortedCodeCounts(long[] codes, long[] counts, long[] prefixCounts, long maxValue) {
      this.codes = codes;
      this.counts = counts;
      this.prefixCounts = prefixCounts;
      this.maxValue = maxValue;
    }

    static SortedCodeCounts from(Map<String, Long> counts) {
      int n = counts.size();
      long[][] pairs = new long[n][2];
      int w = 0;
      long max = 0L;
      for (Map.Entry<String, Long> e : counts.entrySet()) {
        if (e.getKey() == null || e.getValue() == null) {
          continue;
        }
        long code;
        try {
          code = Long.parseLong(e.getKey());
        } catch (NumberFormatException ex) {
          continue;
        }
        long v = e.getValue().longValue();
        pairs[w][0] = code;
        pairs[w][1] = v;
        if (v > max) {
          max = v;
        }
        w++;
      }
      Arrays.sort(pairs, 0, w, new Comparator<long[]>() {
        @Override
        public int compare(long[] a, long[] b) {
          return a[0] < b[0] ? -1 : (a[0] > b[0] ? 1 : 0);
        }
      });
      // Collapse duplicate codes (sum counts) after sort.
      long[] codesOut = new long[w];
      long[] countsOut = new long[w];
      int m = 0;
      for (int i = 0; i < w; i++) {
        long c = pairs[i][0];
        long v = pairs[i][1];
        if (m > 0 && codesOut[m - 1] == c) {
          countsOut[m - 1] += v;
        } else {
          codesOut[m] = c;
          countsOut[m] = v;
          m++;
        }
      }
      if (m != w) {
        codesOut = Arrays.copyOf(codesOut, m);
        countsOut = Arrays.copyOf(countsOut, m);
      }
      long[] prefix = new long[m + 1];
      for (int i = 0; i < m; i++) {
        prefix[i + 1] = prefix[i] + countsOut[i];
      }
      return new SortedCodeCounts(codesOut, countsOut, prefix, max);
    }
  }

  private long conservativeTotalPostings() {
    if (stats == null) {
      return 1_000_000L;
    }
    long t = sumMap(stats.time_bucket_posting_counts);
    long z = sumMap(stats.zorder_cell_posting_counts);
    long xz = sumMap(stats.xz_code_posting_counts);
    long ts = sumMap(stats.tshape_code_posting_counts);
    long xzt = sumMap(stats.xzt_code_posting_counts);
    long v = Math.max(t, Math.max(z, Math.max(xz, Math.max(ts, xzt))));
    return v > 0 ? v : 1_000_000L;
  }

  private long estimateScanRanges(PlanEnvelope plan, BoundIr ir) {
    if (plan == null) {
      return 1L;
    }
    AccessShape shape = accessShape(plan);
    long ranges = 0L;
    int shards = layout != null ? Math.max(1, layout.shardCount) : 4;
    boolean usedXzt = shape.branches.contains("XZT");
    boolean usedXz = shape.branches.contains("XZ");
    boolean usedTs = shape.branches.contains("TSHAPE");
    if (shape.hasTime && ir != null && BoundIr.hasTemporal(ir) && layout != null) {
      if (usedXzt) {
        ranges += (long) physicalRangeCount(
            cachedXztRangesIr(ir),
            Math.max(1, layout.maxXzRanges)) * shards;
      } else {
        long[] buckets = TimeBucket.bucketsCovering(
            BoundIr.temporalsOf(ir).get(0).start_ms, BoundIr.temporalsOf(ir).get(BoundIr.temporalsOf(ir).size()-1).end_ms, layout.epochMs, layout.bucketMs);
        ranges += (long) buckets.length * shards;
      }
    }
    if (shape.hasZ && ir != null && BoundIr.hasSpatial(ir) && layout != null) {
      if (usedXz) {
        ranges += (long) physicalRangeCount(cachedXzRangesIr(ir),
            Math.max(1, layout.maxXzRanges)) * shards;
      } else if (usedTs) {
        ranges += (long) physicalRangeCount(cachedTshapeRangesIr(ir),
            Math.max(1, layout.maxXzRanges)) * shards;
      } else {
        List<ZOrder.MortonRange> mr = new ZOrderIndexAdapter(layout).mortonRanges(
            new Rect(BoundIr.spatialUnion(ir).min_x, BoundIr.spatialUnion(ir).min_y, BoundIr.spatialUnion(ir).max_x, BoundIr.spatialUnion(ir).max_y));
        ranges += (long) mr.size() * shards;
      }
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
      } else if (n.op == Op.XZT_RANGE_SCAN) {
        s.hasTime = true;
        s.usedXzt = true;
        s.branches.add("XZT");
      } else if (n.op == Op.ZORDER_RANGE_SCAN) {
        s.hasZ = true;
        s.branches.add("ZORDER");
      } else if (n.op == Op.XZ_RANGE_SCAN) {
        s.hasZ = true;
        s.usedXz = true;
        s.branches.add("XZ");
      } else if (n.op == Op.TSHAPE_RANGE_SCAN) {
        s.hasZ = true;
        s.usedTshape = true;
        s.branches.add("TSHAPE");
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
    boolean usedXzt;
    boolean usedXz;
    boolean usedTshape;
    final List<String> branches = new ArrayList<String>();
  }

  private enum SampleCodeFamily {
    XZ,
    TSHAPE,
    XZT
  }

  private static final class SumResult {
    long sum;
    boolean missing;
    boolean sampleBased;
    int physicalRangeCount;
    int sampleMatches;
    int sampleSize;
    double sampleRate;

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
