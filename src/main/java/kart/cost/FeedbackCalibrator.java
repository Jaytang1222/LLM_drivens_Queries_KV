package kart.cost;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import kart.config.AppConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Offline Feedback (§16): train/val/test splits, filter-pass updates, coefficient fit
 * by replaying {@link CostModel#estimate} (ScheduleEstimate structure), not bare Xw.
 */
public final class FeedbackCalibrator {

  private static final ObjectMapper MAPPER = new ObjectMapper()
      .enable(SerializationFeature.INDENT_OUTPUT);

  /** Diagnostic drivers (report weights); fit target is CostModel MAE. */
  public static final String[] FEATURE_KEYS = new String[] {
      "rpc_est",
      "seek_ranges",
      "index_bytes",
      "decode_rows",
      "set_bytes",
      "fetch_gets",
      "exact_points",
      "recon_chunks",
      "dtw_cells"
  };

  /** Named §13.4 coeffs optimized via CostModel (includes gamma_byte). */
  public static final String[] NAMED_COEFFS = new String[] {
      "alpha_rpc",
      "alpha_seek",
      "alpha_byte",
      "alpha_decode",
      "beta_hash",
      "beta_emit",
      "beta_spill",
      "gamma_rpc",
      "gamma_byte",
      "gamma_decode",
      "delta_point",
      "rho_linear",
      "eta_cell",
      "theta_heap"
  };

  public static final class Pair {
    public String queryId;
    public double[] features;
    public CostFeatures costFeatures;
    public double actualMs;
    public Long matchedChunks;
    public Long candidateChunks;
  }

  public static final class Report {
    public int trainSize;
    public int valSize;
    public int testSize;
    public double trainMae;
    public double valMae;
    public double testMae;
    public boolean calibrated;
    public String modelVersion = CostModel.MODEL_VERSION;
    public Map<String, Double> weights = new LinkedHashMap<String, Double>();
    public Map<String, Double> namedCoeffs = new LinkedHashMap<String, Double>();
    public Double filterPassRate;
    public Long filterPassSamples;
    public Long filterPassHits;
    public String note =
        "§16 offline feedback: CostModel+ScheduleEstimate MAE fit; freeze coeffs during experiments.";
  }

  public Report calibrate(Path pairsDir, Path reportOut, double trainFrac, double valFrac)
      throws IOException {
    return calibrate(pairsDir, reportOut, null, trainFrac, valFrac);
  }

  public Report calibrate(Path pairsDir, Path reportOut, Path coeffsOut,
                          double trainFrac, double valFrac) throws IOException {
    List<Pair> pairs = loadPairs(pairsDir);
    Collections.sort(pairs, new Comparator<Pair>() {
      @Override
      public int compare(Pair a, Pair b) {
        return a.queryId.compareTo(b.queryId);
      }
    });
    int n = pairs.size();
    int nTrain = n > 0 ? Math.max(1, (int) Math.floor(n * trainFrac)) : 0;
    int nVal = n > 0 ? Math.max(0, (int) Math.floor(n * valFrac)) : 0;
    if (nTrain + nVal >= n && n > 0) {
      nVal = Math.max(0, n - nTrain - 1);
    }
    int nTest = Math.max(0, n - nTrain - nVal);

    List<Pair> train = nTrain > 0 ? pairs.subList(0, nTrain) : Collections.<Pair>emptyList();
    List<Pair> val = nVal > 0 ? pairs.subList(nTrain, nTrain + nVal) : Collections.<Pair>emptyList();
    List<Pair> test = nTest > 0 ? pairs.subList(nTrain + nVal, n) : Collections.<Pair>emptyList();

    AppConfig.CostCoeffs fitted = fitCostModel(train, 120);
    Report r = new Report();
    r.trainSize = train.size();
    r.valSize = val.size();
    r.testSize = test.size();
    r.calibrated = !train.isEmpty();
    r.trainMae = maeCostModel(train, fitted);
    r.valMae = maeCostModel(val, fitted);
    r.testMae = maeCostModel(test, fitted);
    for (String key : NAMED_COEFFS) {
      Double v = readCoeff(fitted, key);
      if (v != null) {
        r.namedCoeffs.put(key, v);
      }
    }
    // Clear DTW metric override so calibrated eta_cell is used.
    r.namedCoeffs.put("eta_cell_dtw", Double.valueOf(0.0));
    // Diagnostic linear weights from structural drivers (not the selection objective).
    double[] wDiag = nnlsDrivers(train, 400);
    for (int i = 0; i < FEATURE_KEYS.length; i++) {
      r.weights.put(FEATURE_KEYS[i], Double.valueOf(wDiag[i]));
    }

    long hits = 0L;
    long samples = 0L;
    for (Pair p : pairs) {
      if (p.matchedChunks != null && p.candidateChunks != null && p.candidateChunks.longValue() > 0) {
        hits += p.matchedChunks.longValue();
        samples += p.candidateChunks.longValue();
      }
    }
    if (samples > 0) {
      r.filterPassHits = Long.valueOf(hits);
      r.filterPassSamples = Long.valueOf(samples);
      r.filterPassRate = Double.valueOf((hits + 1.0) / (samples + 2.0));
    }

    if (reportOut != null) {
      Files.createDirectories(reportOut.getParent());
      Files.write(reportOut, MAPPER.writeValueAsBytes(r));
    }
    if (coeffsOut != null) {
      Map<String, Object> merge = new LinkedHashMap<String, Object>();
      merge.put("calibrated", Boolean.TRUE);
      merge.put("model_version", CostModel.MODEL_VERSION);
      merge.putAll(r.namedCoeffs);
      if (r.filterPassRate != null) {
        merge.put("filter_pass_rate", r.filterPassRate);
        merge.put("filter_pass_samples", r.filterPassSamples);
        merge.put("filter_pass_hits", r.filterPassHits);
      }
      Files.createDirectories(coeffsOut.getParent());
      Files.write(coeffsOut, MAPPER.writeValueAsBytes(merge));
    }
    return r;
  }

  /** Apply fitted named coeffs onto an existing CostCoeffs object (subsequent queries only). */
  public static void applyNamedCoeffs(AppConfig.CostCoeffs dest, Map<String, Double> named) {
    if (dest == null || named == null) {
      return;
    }
    for (String key : NAMED_COEFFS) {
      put(dest, named, key);
    }
    if (named.containsKey("eta_cell_dtw")) {
      dest.eta_cell_dtw = named.get("eta_cell_dtw").doubleValue();
    } else {
      dest.eta_cell_dtw = 0.0;
    }
    dest.calibrated = true;
    dest.model_version = CostModel.MODEL_VERSION;
  }

  private static void put(AppConfig.CostCoeffs dest, Map<String, Double> named, String key) {
    if (!named.containsKey(key) || named.get(key) == null) {
      return;
    }
    double v = named.get(key).doubleValue();
    if ("alpha_rpc".equals(key)) {
      dest.alpha_rpc = v;
    } else if ("alpha_seek".equals(key)) {
      dest.alpha_seek = v;
    } else if ("alpha_byte".equals(key)) {
      dest.alpha_byte = v;
    } else if ("alpha_decode".equals(key)) {
      dest.alpha_decode = v;
    } else if ("beta_hash".equals(key)) {
      dest.beta_hash = v;
    } else if ("beta_emit".equals(key)) {
      dest.beta_emit = v;
    } else if ("beta_spill".equals(key)) {
      dest.beta_spill = v;
    } else if ("gamma_rpc".equals(key)) {
      dest.gamma_rpc = v;
    } else if ("gamma_byte".equals(key)) {
      dest.gamma_byte = v;
    } else if ("gamma_decode".equals(key)) {
      dest.gamma_decode = v;
    } else if ("delta_point".equals(key)) {
      dest.delta_point = v;
    } else if ("rho_linear".equals(key)) {
      dest.rho_linear = v;
    } else if ("eta_cell".equals(key)) {
      dest.eta_cell = v;
    } else if ("theta_heap".equals(key)) {
      dest.theta_heap = v;
    }
  }

  private static Double readCoeff(AppConfig.CostCoeffs c, String key) {
    if (c == null) {
      return null;
    }
    if ("alpha_rpc".equals(key)) {
      return Double.valueOf(c.alpha_rpc);
    }
    if ("alpha_seek".equals(key)) {
      return Double.valueOf(c.alpha_seek);
    }
    if ("alpha_byte".equals(key)) {
      return Double.valueOf(c.alpha_byte);
    }
    if ("alpha_decode".equals(key)) {
      return Double.valueOf(c.alpha_decode);
    }
    if ("beta_hash".equals(key)) {
      return Double.valueOf(c.beta_hash);
    }
    if ("beta_emit".equals(key)) {
      return Double.valueOf(c.beta_emit);
    }
    if ("beta_spill".equals(key)) {
      return Double.valueOf(c.beta_spill);
    }
    if ("gamma_rpc".equals(key)) {
      return Double.valueOf(c.gamma_rpc);
    }
    if ("gamma_byte".equals(key)) {
      return Double.valueOf(c.gamma_byte);
    }
    if ("gamma_decode".equals(key)) {
      return Double.valueOf(c.gamma_decode);
    }
    if ("delta_point".equals(key)) {
      return Double.valueOf(c.delta_point);
    }
    if ("rho_linear".equals(key)) {
      return Double.valueOf(c.rho_linear);
    }
    if ("eta_cell".equals(key)) {
      return Double.valueOf(c.eta_cell);
    }
    if ("theta_heap".equals(key)) {
      return Double.valueOf(c.theta_heap);
    }
    return null;
  }

  /**
   * Projected coordinate descent: minimize MAE(CostModel(coeffs, features), actualMs).
   */
  private static AppConfig.CostCoeffs fitCostModel(List<Pair> train, int rounds) {
    AppConfig.CostCoeffs best = new AppConfig.CostCoeffs();
    best.calibrated = true;
    best.model_version = CostModel.MODEL_VERSION;
    best.eta_cell_dtw = 0.0;
    if (train.isEmpty()) {
      return best;
    }
    // Warm-start from defaults; scale soft memory from pairs if present.
    double bestMae = maeCostModel(train, best);
    double[] step = new double[NAMED_COEFFS.length];
    for (int i = 0; i < step.length; i++) {
      Double cur = readCoeff(best, NAMED_COEFFS[i]);
      step[i] = Math.max(1e-12, (cur == null ? 1e-6 : Math.abs(cur)) * 0.25);
    }
    for (int r = 0; r < rounds; r++) {
      boolean improved = false;
      for (int j = 0; j < NAMED_COEFFS.length; j++) {
        String key = NAMED_COEFFS[j];
        Double cur = readCoeff(best, key);
        double base = cur == null ? 0.0 : cur.doubleValue();
        double[] trials = new double[] {
            Math.max(0.0, base - step[j]),
            Math.max(0.0, base + step[j]),
            Math.max(0.0, base * 0.5),
            Math.max(0.0, base * 2.0)
        };
        for (double trial : trials) {
          AppConfig.CostCoeffs cand = copyCoeffs(best);
          Map<String, Double> one = new LinkedHashMap<String, Double>();
          one.put(key, Double.valueOf(trial));
          put(cand, one, key);
          cand.calibrated = true;
          double mae = maeCostModel(train, cand);
          if (mae + 1e-12 < bestMae) {
            best = cand;
            bestMae = mae;
            improved = true;
          }
        }
      }
      if (!improved) {
        for (int j = 0; j < step.length; j++) {
          step[j] *= 0.5;
        }
        if (step[0] < 1e-16) {
          break;
        }
      }
    }
    best.calibrated = true;
    best.eta_cell_dtw = 0.0;
    return best;
  }

  private static AppConfig.CostCoeffs copyCoeffs(AppConfig.CostCoeffs src) {
    AppConfig.CostCoeffs c = new AppConfig.CostCoeffs();
    if (src == null) {
      return c;
    }
    c.calibrated = src.calibrated;
    c.model_version = src.model_version;
    c.concurrency_index = src.concurrency_index;
    c.concurrency_get = src.concurrency_get;
    c.concurrency_per_rs = src.concurrency_per_rs;
    c.alpha_rpc = src.alpha_rpc;
    c.alpha_seek = src.alpha_seek;
    c.alpha_byte = src.alpha_byte;
    c.alpha_decode = src.alpha_decode;
    c.beta_hash = src.beta_hash;
    c.beta_emit = src.beta_emit;
    c.beta_spill = src.beta_spill;
    c.beta_sort_merge = src.beta_sort_merge;
    c.gamma_rpc = src.gamma_rpc;
    c.gamma_byte = src.gamma_byte;
    c.gamma_decode = src.gamma_decode;
    c.delta_point = src.delta_point;
    c.delta_geometry = src.delta_geometry;
    c.rho_linear = src.rho_linear;
    c.eta_cell = src.eta_cell;
    c.eta_cell_dtw = src.eta_cell_dtw;
    c.eta_cell_frechet = src.eta_cell_frechet;
    c.eta_cell_hausdorff = src.eta_cell_hausdorff;
    c.theta_heap = src.theta_heap;
    c.soft_memory_bytes = src.soft_memory_bytes;
    c.max_exec_ms = src.max_exec_ms;
    return c;
  }

  private static double maeCostModel(List<Pair> pairs, AppConfig.CostCoeffs coeffs) {
    if (pairs.isEmpty()) {
      return 0.0;
    }
    CostModel model = new CostModel(coeffs);
    double sum = 0.0;
    for (Pair p : pairs) {
      CostFeatures f = p.costFeatures != null ? p.costFeatures : featuresFromDrivers(p);
      double pred = model.estimateFinal(f).estimated_ms;
      sum += Math.abs(pred - p.actualMs);
    }
    return sum / pairs.size();
  }

  private static CostFeatures featuresFromDrivers(Pair p) {
    CostFeatures f = new CostFeatures();
    if (p.features == null) {
      return f;
    }
    // Approximate reconstruction when only diagnostic drivers were loaded.
    f.scanRanges = (long) Math.max(1.0, p.features.length > 1 ? p.features[1] : 1.0);
    f.rpcEst = p.features[0];
    f.rpcPerRange = f.scanRanges > 0 ? f.rpcEst / f.scanRanges : 1.0;
    f.estIndexRows = (long) (p.features.length > 3 ? p.features[3] : 0.0);
    f.estRawBytes = (long) (p.features.length > 4 ? p.features[4] : 0.0);
    f.estSetBytes = f.estRawBytes;
    f.estFetchGets = (long) (p.features.length > 5 ? p.features[5] : 1.0);
    f.estCandidateChunks = Math.max(1L, f.estFetchGets * 500L);
    f.estExactPoints = (long) (p.features.length > 6 ? p.features[6] : 0.0);
    f.estReconChunks = (long) (p.features.length > 7 ? p.features[7] : 0.0);
    f.estDtwCells = (long) (p.features.length > 8 ? p.features[8] : 0.0);
    f.estEligibleTrajs = Math.max(1L, f.estReconChunks);
    f.needsReconstruct = f.estReconChunks > 0;
    f.estUncachedReconBytes = (long) Math.ceil(f.estReconChunks * f.avgChunkBytes);
    f.estMetaGets = f.needsReconstruct ? Math.max(1L, f.estEligibleTrajs) : 0L;
    f.sampleSize = 50;
    return f;
  }

  /** Diagnostic NNLS on FEATURE_KEYS (not used for published coeffs). */
  private static double[] nnlsDrivers(List<Pair> train, int iters) {
    int dim = FEATURE_KEYS.length;
    double[] w = new double[dim];
    if (train.isEmpty()) {
      return w;
    }
    double[] scales = new double[dim];
    for (int j = 0; j < dim; j++) {
      double m = 1.0;
      for (Pair p : train) {
        double xj = p.features != null && j < p.features.length ? Math.abs(p.features[j]) : 0.0;
        if (xj > m) {
          m = xj;
        }
      }
      scales[j] = m;
    }
    double lr = 1e-8;
    for (int it = 0; it < iters; it++) {
      double[] grad = new double[dim];
      for (Pair p : train) {
        double pred = 0.0;
        for (int j = 0; j < dim; j++) {
          pred += w[j] * (p.features != null && j < p.features.length ? p.features[j] : 0.0);
        }
        double err = pred - p.actualMs;
        for (int j = 0; j < dim; j++) {
          double xj = p.features != null && j < p.features.length ? p.features[j] : 0.0;
          grad[j] += err * xj;
        }
      }
      for (int j = 0; j < dim; j++) {
        w[j] = Math.max(0.0, w[j] - (lr / scales[j]) * grad[j]);
      }
    }
    return w;
  }

  static List<Pair> loadPairs(Path dir) throws IOException {
    List<Pair> out = new ArrayList<Pair>();
    if (dir == null || !Files.isDirectory(dir)) {
      return out;
    }
    for (Path qdir : Files.newDirectoryStream(dir)) {
      if (!Files.isDirectory(qdir)) {
        continue;
      }
      Path feat = qdir.resolve("cost_features.json");
      Path trace = qdir.resolve("trace.json");
      if (!Files.isRegularFile(feat) || !Files.isRegularFile(trace)) {
        continue;
      }
      JsonNode f = MAPPER.readTree(feat.toFile());
      JsonNode t = MAPPER.readTree(trace.toFile());
      JsonNode featRoot = f.has("features") && f.get("features").isObject() ? f.get("features") : f;
      Pair p = new Pair();
      p.queryId = qdir.getFileName().toString();
      p.actualMs = t.path("elapsedMs").asDouble(t.path("elapsed_ms").asDouble(0));
      p.features = new double[FEATURE_KEYS.length];
      for (int i = 0; i < FEATURE_KEYS.length; i++) {
        p.features[i] = readFeature(featRoot, FEATURE_KEYS[i]);
      }
      p.costFeatures = costFeaturesFromJson(featRoot);
      if (t.has("matched_chunks") && !t.get("matched_chunks").isNull()) {
        p.matchedChunks = Long.valueOf(t.get("matched_chunks").asLong());
      }
      if (t.has("candidate_chunks") && !t.get("candidate_chunks").isNull()) {
        p.candidateChunks = Long.valueOf(t.get("candidate_chunks").asLong());
      }
      out.add(p);
    }
    return out;
  }

  static CostFeatures costFeaturesFromJson(JsonNode featRoot) {
    CostFeatures f = new CostFeatures();
    f.scanRanges = (long) featRoot.path("scan_ranges").asDouble(1.0);
    f.estIndexRows = (long) featRoot.path("estimated_index_rows").asDouble(0.0);
    f.estCandidateChunks = (long) featRoot.path("estimated_candidate_chunks").asDouble(0.0);
    f.estRawBytes = (long) featRoot.path("estimated_raw_bytes").asDouble(0.0);
    f.estEligibleTrajs = (long) featRoot.path("estimated_eligible_trajectories").asDouble(0.0);
    f.estDtwCells = (long) featRoot.path("estimated_dtw_cells")
        .asDouble(featRoot.path("dtw_cells").asDouble(0.0));
    f.estSpillBytes = (long) featRoot.path("estimated_spill_bytes").asDouble(0.0);
    f.estGeometryOps = (long) featRoot.path("estimated_geometry_ops").asDouble(0.0);
    f.filterPassRate = featRoot.path("filter_pass_rate").asDouble(0.5);
    f.needsReconstruct = featRoot.path("needs_reconstruct").asBoolean(false);
    f.topK = featRoot.path("top_k").asInt(0);
    f.rpcEst = featRoot.path("rpc_est").asDouble(f.scanRanges);
    f.rpcPerRange = f.scanRanges > 0 ? Math.max(1.0, f.rpcEst / f.scanRanges) : 1.0;
    f.estSetBytes = (long) featRoot.path("set_bytes").asDouble(f.estRawBytes);
    f.estFetchGets = (long) featRoot.path("fetch_gets")
        .asDouble(Math.max(1.0, Math.ceil(f.estCandidateChunks / 500.0)));
    f.estExactPoints = (long) featRoot.path("exact_points")
        .asDouble(f.estCandidateChunks * 128.0);
    f.estReconChunks = (long) featRoot.path("recon_chunks").asDouble(0.0);
    f.estMetaGets = (long) featRoot.path("meta_gets").asDouble(0.0);
    f.estUncachedReconBytes = (long) featRoot.path("uncached_recon_bytes").asDouble(0.0);
    if (f.estReconChunks > 0 || f.estUncachedReconBytes > 0 || f.needsReconstruct) {
      f.needsReconstruct = true;
      if (f.estMetaGets <= 0) {
        f.estMetaGets = Math.max(1L, f.estEligibleTrajs);
      }
      if (f.estUncachedReconBytes <= 0 && f.estReconChunks > 0) {
        f.estUncachedReconBytes = (long) Math.ceil(f.estReconChunks * f.avgChunkBytes);
      }
    }
    if (featRoot.has("merge_impl") && !featRoot.get("merge_impl").isNull()) {
      f.mergeImpl = featRoot.get("merge_impl").asText();
    }
    if (featRoot.has("sim_metric") && !featRoot.get("sim_metric").isNull()) {
      f.simMetric = featRoot.get("sim_metric").asText();
    }
    f.sampleSize = 50;
    f.missingStats = featRoot.path("missing_stats").asBoolean(false);
    return f;
  }

  /** Prefer structural keys; fall back to legacy coarse keys for old traces. */
  private static double readFeature(JsonNode featRoot, String key) {
    if (featRoot.has(key) && !featRoot.get(key).isNull()) {
      return featRoot.get(key).asDouble(0.0);
    }
    if ("rpc_est".equals(key) || "seek_ranges".equals(key)) {
      return featRoot.path("scan_ranges").asDouble(0.0);
    }
    if ("decode_rows".equals(key)) {
      return featRoot.path("estimated_index_rows").asDouble(0.0);
    }
    if ("index_bytes".equals(key) || "set_bytes".equals(key)) {
      return featRoot.path("estimated_raw_bytes").asDouble(0.0);
    }
    if ("fetch_gets".equals(key)) {
      double cand = featRoot.path("estimated_candidate_chunks").asDouble(0.0);
      return Math.max(1.0, Math.ceil(cand / 500.0));
    }
    if ("exact_points".equals(key)) {
      return featRoot.path("estimated_candidate_chunks").asDouble(0.0) * 128.0;
    }
    if ("recon_chunks".equals(key)) {
      return featRoot.path("estimated_eligible_trajectories").asDouble(0.0);
    }
    if ("dtw_cells".equals(key)) {
      return featRoot.path("estimated_dtw_cells").asDouble(0.0);
    }
    return 0.0;
  }
}
