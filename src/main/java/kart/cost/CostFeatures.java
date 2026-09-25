package kart.cost;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cost features extracted from a PhysicalPlan (or Fast approximation) and stats
 * (design.md §10 / IMPLEMENTATION_PLAN §13.4).
 */
public final class CostFeatures {

  public String planId;
  /** Exact scan range count (Final) or estimated (Fast). */
  public long scanRanges;
  public long estIndexRows;
  public long estCandidateChunks;
  public long estRawBytes;
  public long estEligibleTrajs;
  /** O(mn) similarity cell count (metric-agnostic name kept for schema compat). */
  public long estDtwCells;
  /** Spill bytes above soft memory budget (0 if under budget). */
  public long estSpillBytes;
  /** Geometry/MBR predicate work units (spatial ExactFilter). */
  public long estGeometryOps;
  /** True when any required posting/hist sample was missing and upper bounds were used. */
  public boolean missingStats;
  public int sampleSize;
  public double sampleRate = 0.01;
  public double avgChunkBytes = 4096.0;
  public double avgPointsPerChunk = 128.0;
  public double avgTrajLen = 256.0;
  public double filterPassRate = 0.5;
  public double chunksToTraj = 0.5;
  public List<String> accessBranches = new ArrayList<String>();
  public Map<String, Object> extras = new LinkedHashMap<String, Object>();
  /** Per-scan RegionServer keys (Final path); empty → shard fallback in CostModel. */
  public List<String> scanRsKeys = new ArrayList<String>();
  /** Per-get-batch RS keys (mirrors scan affinity when unknown). */
  public List<String> getRsKeys = new ArrayList<String>();
  public int shardCountHint = 4;
  public boolean needsReconstruct;
  public int topK;
  /** HASH_SET or SORT_MERGE (null → HASH_SET). */
  public String mergeImpl;
  /** DTW / FRECHET / HAUSDORFF for L_sim coefficient selection. */
  public String simMetric;

  /** Estimated RPCs per scan range (R̂_ar); default 1. */
  public double rpcPerRange = 1.0;
  /** Total estimated index RPCs = scanRanges * rpcPerRange. */
  public double rpcEst;
  /** Meta Get count for reconstruct (§13.4). */
  public long estMetaGets;
  /** Uncached full-trajectory bytes for reconstruct. */
  public long estUncachedReconBytes;
  /** Uncached reconstruct chunk Gets. */
  public long estReconChunks;
  /** Estimated fetch Get RPCs (batched). */
  public long estFetchGets;
  /** Estimated exact-filter point examinations. */
  public long estExactPoints;
  /** Estimated set-op working bytes. */
  public long estSetBytes;

  public Map<String, Object> toFeatureMap() {
    Map<String, Object> m = new LinkedHashMap<String, Object>();
    m.put("scan_ranges", Long.valueOf(scanRanges));
    m.put("estimated_index_rows", Long.valueOf(estIndexRows));
    m.put("estimated_candidate_chunks", Long.valueOf(estCandidateChunks));
    m.put("estimated_raw_bytes", Long.valueOf(estRawBytes));
    m.put("estimated_eligible_trajectories", Long.valueOf(estEligibleTrajs));
    m.put("estimated_dtw_cells", Long.valueOf(estDtwCells));
    m.put("estimated_spill_bytes", Long.valueOf(estSpillBytes));
    m.put("estimated_geometry_ops", Long.valueOf(estGeometryOps));
    m.put("filter_pass_rate", Double.valueOf(filterPassRate));
    m.put("missing_stats", Boolean.valueOf(missingStats));
    m.put("needs_reconstruct", Boolean.valueOf(needsReconstruct));
    m.put("top_k", Integer.valueOf(topK));
    // §13.4 structural drivers for FeedbackCalibrator / fit_cost_coeffs.py
    m.put("rpc_est", Double.valueOf(rpcEst > 0 ? rpcEst : scanRanges * Math.max(1.0, rpcPerRange)));
    m.put("seek_ranges", Long.valueOf(scanRanges));
    m.put("index_bytes", Double.valueOf(
        estIndexRows * avgChunkBytes / Math.max(1.0, avgPointsPerChunk)));
    m.put("decode_rows", Long.valueOf(estIndexRows));
    m.put("set_bytes", Long.valueOf(estSetBytes > 0 ? estSetBytes : estRawBytes));
    m.put("fetch_gets", Long.valueOf(
        estFetchGets > 0 ? estFetchGets : Math.max(1L, (estCandidateChunks + 499) / 500)));
    m.put("exact_points", Long.valueOf(
        estExactPoints > 0
            ? estExactPoints
            : (long) Math.ceil(estCandidateChunks * avgPointsPerChunk)));
    m.put("recon_chunks", Long.valueOf(estReconChunks));
    m.put("meta_gets", Long.valueOf(estMetaGets));
    m.put("uncached_recon_bytes", Long.valueOf(estUncachedReconBytes));
    m.put("dtw_cells", Long.valueOf(estDtwCells));
    if (mergeImpl != null) {
      m.put("merge_impl", mergeImpl);
    }
    if (simMetric != null) {
      m.put("sim_metric", simMetric);
    }
    return m;
  }
}
