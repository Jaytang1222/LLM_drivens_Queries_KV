package kart.cost;

import kart.config.AppConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * §13.4 cost model: L_hat_index + set + fetch + exact + reconstruct + sim + topk
 * with ScheduleEstimate (RS affinity). Fast/Final share formula and coefficients.
 */
public final class CostModel {

  public static final String MODEL_VERSION = "cost_v2_rs_sched";

  private final AppConfig.CostCoeffs coeffs;
  private final RegionMapping regionMapping;

  public CostModel(AppConfig.CostCoeffs coeffs) {
    this(coeffs, new RegionMapping.ShardFallback());
  }

  public CostModel(AppConfig.CostCoeffs coeffs, RegionMapping regionMapping) {
    this.coeffs = coeffs != null ? coeffs : new AppConfig.CostCoeffs();
    applyLegacyMapping(this.coeffs);
    this.regionMapping = regionMapping != null ? regionMapping : new RegionMapping.ShardFallback();
  }

  /**
   * Map legacy c_* into §13.4 coeffs when the latter look unset.
   * Skipped entirely when {@code calibrated=true} so published zeros stay zeros.
   */
  static void applyLegacyMapping(AppConfig.CostCoeffs c) {
    if (c == null || c.calibrated) {
      return;
    }
    if (c.alpha_seek == 0.0 && c.c_scan > 0) {
      c.alpha_seek = c.c_scan;
    }
    if (c.alpha_decode == 0.0 && c.c_row > 0) {
      c.alpha_decode = c.c_row;
    }
    if (c.gamma_rpc == 0.0 && c.c_get > 0) {
      c.gamma_rpc = c.c_get;
    }
    if (c.alpha_byte == 0.0 && c.c_byte > 0) {
      c.alpha_byte = c.c_byte;
      c.gamma_byte = c.c_byte;
    }
    if (c.delta_point == 0.0 && c.c_point > 0) {
      c.delta_point = c.c_point;
    }
    if (c.eta_cell == 0.0 && c.c_dtw > 0) {
      c.eta_cell = c.c_dtw;
    }
  }

  public AppConfig.CostCoeffs coeffs() {
    return coeffs;
  }

  public boolean calibrated() {
    return coeffs.calibrated;
  }

  public RegionMapping regionMapping() {
    return regionMapping;
  }

  public CostCard estimate(CostFeatures features, String stage) {
    CostCard card = new CostCard();
    if (features != null) {
      card.plan_id = features.planId;
      card.features.putAll(features.toFeatureMap());
    }
    card.stage = stage != null ? stage : "FINAL";
    card.model_version = coeffs.model_version != null ? coeffs.model_version : MODEL_VERSION;
    card.calibrated = coeffs.calibrated;

    double lIndex = 0, lSet = 0, lFetch = 0, lExact = 0, lRecon = 0, lSim = 0, lTopk = 0;
    boolean missingMap = regionMapping.missingRegionMap();

    if (features != null) {
      List<ScheduleEstimate.WorkItem> indexWorks = new ArrayList<ScheduleEstimate.WorkItem>();
      long ranges = Math.max(1L, features.scanRanges);
      double rowsPer = features.scanRanges > 0
          ? (double) features.estIndexRows / (double) features.scanRanges
          : features.estIndexRows;
      double bytesPer = rowsPer * features.avgChunkBytes / Math.max(1.0, features.avgPointsPerChunk);
      double rpcPerRange = features.rpcPerRange > 0 ? features.rpcPerRange : 1.0;
      for (int i = 0; i < ranges; i++) {
        String rs = "shard:" + (i % Math.max(1, features.shardCountHint));
        if (features.scanRsKeys != null && !features.scanRsKeys.isEmpty()) {
          rs = features.scanRsKeys.get(i % features.scanRsKeys.size());
        }
        // W_ar = α_rpc·R̂_ar + α_seek·K_ar + α_byte·B̂ + α_decode·N̂  (K_ar=1 per range)
        double w = coeffs.alpha_rpc * rpcPerRange
            + coeffs.alpha_seek
            + coeffs.alpha_byte * bytesPer
            + coeffs.alpha_decode * rowsPer;
        indexWorks.add(new ScheduleEstimate.WorkItem(rs, w));
      }
      ScheduleEstimate.Result idxSched = ScheduleEstimate.estimate(
          indexWorks, coeffs.concurrency_index, coeffs.concurrency_per_rs, missingMap);
      lIndex = idxSched.wallClockMs;
      if (idxSched.degradedMissingMap) {
        card.uncertainty.label = "HIGH";
        card.features.put("missing_region_map", Boolean.TRUE);
      }

      double mergeExtra = 0.0;
      if (features.mergeImpl != null && "SORT_MERGE".equals(features.mergeImpl)) {
        mergeExtra = coeffs.beta_sort_merge * features.estIndexRows
            * Math.log(Math.max(2.0, (double) features.estIndexRows));
      }
      long soft = coeffs.soft_memory_bytes > 0 ? coeffs.soft_memory_bytes : (512L * 1024L * 1024L);
      long spill = features.estSpillBytes > 0
          ? features.estSpillBytes
          : Math.max(0L, features.estRawBytes - soft);
      lSet = coeffs.beta_hash * features.estIndexRows
          + coeffs.beta_emit * features.estCandidateChunks
          + coeffs.beta_spill * spill
          + mergeExtra;

      List<ScheduleEstimate.WorkItem> getWorks = new ArrayList<ScheduleEstimate.WorkItem>();
      long gets = Math.max(1L, (features.estCandidateChunks + 499) / 500);
      for (int i = 0; i < gets; i++) {
        String rs = "shard:" + (i % Math.max(1, features.shardCountHint));
        if (features.getRsKeys != null && !features.getRsKeys.isEmpty()) {
          rs = features.getRsKeys.get(i % features.getRsKeys.size());
        } else if (features.scanRsKeys != null && !features.scanRsKeys.isEmpty()) {
          rs = features.scanRsKeys.get(i % features.scanRsKeys.size());
        }
        double w = coeffs.gamma_rpc
            + coeffs.gamma_byte * (features.estRawBytes / (double) gets);
        getWorks.add(new ScheduleEstimate.WorkItem(rs, w));
      }
      ScheduleEstimate.Result getSched = ScheduleEstimate.estimate(
          getWorks, coeffs.concurrency_get, coeffs.concurrency_per_rs, missingMap);
      lFetch = getSched.wallClockMs
          + coeffs.gamma_decode * features.estCandidateChunks;

      lExact = coeffs.delta_point * (features.estCandidateChunks * features.avgPointsPerChunk)
          + coeffs.delta_geometry * features.estGeometryOps;

      if (features.needsReconstruct) {
        long metaGets = features.estMetaGets > 0
            ? features.estMetaGets
            : Math.max(1L, features.estEligibleTrajs);
        // Trust extractor uncached bytes (0 = all reconstruct chunks already in FETCH cache).
        long uncachedBytes = features.estUncachedReconBytes;
        // ρ_linear reconstruct only (do not also add ρ_sort).
        lRecon = coeffs.gamma_rpc * metaGets
            + coeffs.gamma_byte * uncachedBytes
            + coeffs.rho_linear * features.estEligibleTrajs * features.avgTrajLen;
      }

      lSim = etaForMetric(features.simMetric) * features.estDtwCells;

      if (features.topK > 0) {
        lTopk = coeffs.theta_heap * features.estEligibleTrajs
            * Math.log(Math.max(2.0, (double) features.topK));
      }
    }

    card.l_hat_index = lIndex;
    card.l_hat_set = lSet;
    card.l_hat_fetch = lFetch;
    card.l_hat_exact = lExact;
    card.l_hat_reconstruct = lRecon;
    card.l_hat_sim = lSim;
    card.l_hat_topk = lTopk;
    card.estimated_ms = lIndex + lSet + lFetch + lExact + lRecon + lSim + lTopk;

    card.features.put("L_hat_index", Double.valueOf(lIndex));
    card.features.put("L_hat_set", Double.valueOf(lSet));
    card.features.put("L_hat_fetch", Double.valueOf(lFetch));
    card.features.put("L_hat_exact", Double.valueOf(lExact));
    card.features.put("L_hat_reconstruct", Double.valueOf(lRecon));
    card.features.put("L_hat_sim", Double.valueOf(lSim));
    card.features.put("L_hat_topk", Double.valueOf(lTopk));

    card.main_cost_drivers = mainDrivers(card);
    card.uncertainty.method = "SAMPLE";
    card.uncertainty.sample_size = features != null ? features.sampleSize : 0;
    if (features != null && features.missingStats) {
      card.uncertainty.label = "HIGH";
      card.features.put("missing_stats", Boolean.TRUE);
    } else if (card.uncertainty.sample_size <= 0) {
      if (!"HIGH".equals(card.uncertainty.label)) {
        card.uncertainty.label = "HIGH";
      }
    } else if (card.uncertainty.sample_size < 50) {
      if (!"HIGH".equals(card.uncertainty.label)) {
        card.uncertainty.label = "MEDIUM";
      }
    } else if (!"HIGH".equals(card.uncertainty.label)) {
      card.uncertainty.label = "LOW";
    }
    return card;
  }

  /**
   * When {@code calibrated=true}, DTW always uses fitted {@code eta_cell}
   * (fit zeroes {@code eta_cell_dtw}). Uncalibrated configs may override via {@code eta_cell_dtw}.
   */
  double etaForMetric(String metric) {
    if (metric == null) {
      return coeffs.eta_cell;
    }
    if ("FRECHET".equalsIgnoreCase(metric) && coeffs.eta_cell_frechet > 0) {
      return coeffs.eta_cell_frechet;
    }
    if ("HAUSDORFF".equalsIgnoreCase(metric) && coeffs.eta_cell_hausdorff > 0) {
      return coeffs.eta_cell_hausdorff;
    }
    if ("DTW".equalsIgnoreCase(metric) && !coeffs.calibrated && coeffs.eta_cell_dtw > 0) {
      return coeffs.eta_cell_dtw;
    }
    return coeffs.eta_cell;
  }

  public CostCard estimateFinal(CostFeatures features) {
    return estimate(features, "FINAL");
  }

  public CostCard estimateFast(CostFeatures features) {
    return estimate(features, "FAST");
  }

  private List<String> mainDrivers(CostCard card) {
    List<Driver> ds = new ArrayList<Driver>();
    ds.add(new Driver("INDEX", card.l_hat_index));
    ds.add(new Driver("SET", card.l_hat_set));
    ds.add(new Driver("RAW_FETCH", card.l_hat_fetch));
    ds.add(new Driver("EXACT_FILTER", card.l_hat_exact));
    if (card.l_hat_reconstruct > 0) {
      ds.add(new Driver("RECONSTRUCT", card.l_hat_reconstruct));
    }
    if (card.l_hat_sim > 0) {
      ds.add(new Driver("SIMILARITY", card.l_hat_sim));
    }
    if (card.l_hat_topk > 0) {
      ds.add(new Driver("TOP_K", card.l_hat_topk));
    }
    Collections.sort(ds, new Comparator<Driver>() {
      @Override
      public int compare(Driver a, Driver b) {
        return Double.compare(b.cost, a.cost);
      }
    });
    List<String> out = new ArrayList<String>();
    double total = card.estimated_ms;
    for (int i = 0; i < ds.size() && i < 3; i++) {
      if (ds.get(i).cost > total * 0.05 || i == 0) {
        out.add(ds.get(i).name);
      }
    }
    if (out.isEmpty()) {
      out.add("INDEX");
    }
    return out;
  }

  private static final class Driver {
    final String name;
    final double cost;

    Driver(String name, double cost) {
      this.name = name;
      this.cost = cost;
    }
  }
}
