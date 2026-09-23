package kart.cost;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cost features extracted from a PhysicalPlan (or Fast approximation) and stats
 * (design.md §10).
 */
public final class CostFeatures {

  public String planId;
  /** Exact scan range count (Final) or estimated (Fast). */
  public long scanRanges;
  public long estIndexRows;
  public long estCandidateChunks;
  public long estRawBytes;
  public long estEligibleTrajs;
  public long estDtwCells;
  /** True when any required posting/hist sample was missing and upper bounds were used. */
  public boolean missingStats;
  public int sampleSize;
  public double sampleRate = 0.01;
  public double avgChunkBytes = 4096.0;
  public double avgPointsPerChunk = 128.0;
  public double avgTrajLen = 256.0;
  public double filterPassRate = 0.25;
  public double chunksToTraj = 0.5;
  public List<String> accessBranches = new ArrayList<String>();
  public Map<String, Object> extras = new LinkedHashMap<String, Object>();

  public Map<String, Object> toFeatureMap() {
    Map<String, Object> m = new LinkedHashMap<String, Object>();
    m.put("scan_ranges", Long.valueOf(scanRanges));
    m.put("estimated_index_rows", Long.valueOf(estIndexRows));
    m.put("estimated_candidate_chunks", Long.valueOf(estCandidateChunks));
    m.put("estimated_raw_bytes", Long.valueOf(estRawBytes));
    m.put("estimated_eligible_trajectories", Long.valueOf(estEligibleTrajs));
    m.put("estimated_dtw_cells", Long.valueOf(estDtwCells));
    m.put("missing_stats", Boolean.valueOf(missingStats));
    return m;
  }
}
