package kart.cost;

import kart.config.AppConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Shared Fast/Final cost formula (design.md §10). Coefficients from planner.yaml;
 * {@code calibrated=false} until offline fit.
 */
public final class CostModel {

  public static final String MODEL_VERSION = "cost_v1_uncalibrated";

  private final AppConfig.CostCoeffs coeffs;

  public CostModel(AppConfig.CostCoeffs coeffs) {
    this.coeffs = coeffs != null ? coeffs : new AppConfig.CostCoeffs();
  }

  public AppConfig.CostCoeffs coeffs() {
    return coeffs;
  }

  public boolean calibrated() {
    return coeffs.calibrated;
  }

  public CostCard estimate(CostFeatures features, String stage) {
    CostCard card = new CostCard();
    if (features != null) {
      card.plan_id = features.planId;
      card.features.putAll(features.toFeatureMap());
    }
    card.stage = stage != null ? stage : "FINAL";
    card.model_version = MODEL_VERSION;
    card.calibrated = coeffs.calibrated;

    double ms = 0.0;
    if (features != null) {
      ms = coeffs.c_scan * features.scanRanges
          + coeffs.c_row * features.estIndexRows
          + coeffs.c_get * features.estCandidateChunks
          + coeffs.c_byte * features.estRawBytes
          + coeffs.c_point * (features.estCandidateChunks * features.avgPointsPerChunk)
          + coeffs.c_dtw * features.estDtwCells;
    }
    card.estimated_ms = ms;
    card.main_cost_drivers = mainDrivers(features, ms);
    card.uncertainty.method = "SAMPLE";
    card.uncertainty.sample_size = features != null ? features.sampleSize : 0;
    if (features != null && features.missingStats) {
      card.uncertainty.label = "HIGH";
      card.features.put("missing_stats", Boolean.TRUE);
    } else if (card.uncertainty.sample_size <= 0) {
      card.uncertainty.label = "HIGH";
    } else if (card.uncertainty.sample_size < 50) {
      card.uncertainty.label = "MEDIUM";
    } else {
      card.uncertainty.label = "LOW";
    }
    return card;
  }

  public CostCard estimateFinal(CostFeatures features) {
    return estimate(features, "FINAL");
  }

  public CostCard estimateFast(CostFeatures features) {
    return estimate(features, "FAST");
  }

  private List<String> mainDrivers(CostFeatures f, double total) {
    if (f == null || total <= 0) {
      return Collections.singletonList("NONE");
    }
    List<Driver> ds = new ArrayList<Driver>();
    ds.add(new Driver("SCAN", coeffs.c_scan * f.scanRanges));
    ds.add(new Driver("INDEX_ROWS", coeffs.c_row * f.estIndexRows));
    ds.add(new Driver("RAW_FETCH", coeffs.c_get * f.estCandidateChunks
        + coeffs.c_byte * f.estRawBytes));
    ds.add(new Driver("EXACT_FILTER",
        coeffs.c_point * (f.estCandidateChunks * f.avgPointsPerChunk)));
    if (f.estDtwCells > 0) {
      ds.add(new Driver("DTW", coeffs.c_dtw * f.estDtwCells));
    }
    Collections.sort(ds, new Comparator<Driver>() {
      @Override
      public int compare(Driver a, Driver b) {
        return Double.compare(b.cost, a.cost);
      }
    });
    List<String> out = new ArrayList<String>();
    for (int i = 0; i < ds.size() && i < 3; i++) {
      if (ds.get(i).cost > total * 0.05 || i == 0) {
        out.add(ds.get(i).name);
      }
    }
    if (out.isEmpty()) {
      out.add("SCAN");
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
