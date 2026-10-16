package kart.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import kart.catalog.CatalogStore;
import kart.catalog.Manifest;
import kart.data.CanonicalPoint;
import kart.data.Trajectory;
import kart.geo.Rect;
import kart.ir.BoundIr;
import kart.oracle.FullScanOracle;
import kart.snapshot.TDriveLoader;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * Build T-Drive smoke workload BoundIRs + FullScanOracle answer cache (T2.9).
 * Samples anchors from cleaned data so most queries are non-empty and hash/Top-K
 * reference identifiers exist.
 */
@Command(name = "build-oracle-cache",
    description = "Build tdrive_smoke.json workload and oracle answer cache")
public final class BuildOracleCacheCmd implements Callable<Integer> {

  private static final ObjectMapper MAPPER = new ObjectMapper()
      .enable(SerializationFeature.INDENT_OUTPUT);

  @Option(names = "--data", defaultValue = "datasets/tdrive")
  private Path dataDir;

  @Option(names = "--catalog", defaultValue = "catalog")
  private Path catalogDir;

  @Option(names = "--manifest", defaultValue = "tdrive_v1_ready")
  private String manifestId;

  @Option(names = "--workload-out", defaultValue = "experiments/workloads/tdrive_smoke.json")
  private Path workloadOut;

  @Option(names = "--oracle-out", defaultValue = "experiments/workloads/tdrive_smoke.oracle.json")
  private Path oracleOut;

  @Option(names = "--config-root")
  private Path configRoot;

  @Option(names = "--append-topk-metrics",
      description = "Append FRECHET/HAUSDORFF Top-K clones of topk_st_1 into existing smoke+oracle (preserve other answers)")
  private boolean appendTopkMetrics;

  @Option(names = "--evaluate-workload",
      description = "Evaluate BoundIRs from an existing workload JSON and write/merge oracle answers")
  private boolean evaluateWorkload;

  @Option(names = "--workload-in",
      description = "Input BoundIR workload for --evaluate-workload (default: --workload-out)")
  private Path workloadIn;

  @Option(names = "--format", defaultValue = "tdrive",
      description = "Input format for --data: tdrive|ais")
  private String format;

  @Option(names = "--crs",
      description = "Projected CRS when loading data (default: from manifest.layout.crs or EPSG:32650)")
  private String crs;

  @Option(names = "--advanced-complex-diagnostic",
      description = "Build a fresh 12-query, multi-anchor advanced-index diagnostic workload and FullScanOracle")
  private boolean advancedComplexDiagnostic;

  @Option(names = "--diagnostic-seed", defaultValue = "20261014",
      description = "Fixed seed for deterministic multi-anchor selection")
  private long diagnosticSeed;

  @Option(names = "--diagnostic-workload-out",
      defaultValue = "experiments/workloads/bound_ir_adv_complex_diag_v1.json")
  private Path diagnosticWorkloadOut;

  @Option(names = "--diagnostic-oracle-out",
      defaultValue = "experiments/workloads/bound_ir_adv_complex_diag_v1.oracle.json")
  private Path diagnosticOracleOut;

  @Option(names = "--diagnostic-overwrite",
      description = "Allow replacing the diagnostic workload and Oracle outputs when both already exist")
  private boolean diagnosticOverwrite;

  @Override
  public Integer call() throws Exception {
    Path root = resolveRoot();
    if (advancedComplexDiagnostic) {
      return buildAdvancedComplexDiagnostic(root);
    }
    if (appendTopkMetrics) {
      return appendTopkMetrics(root);
    }
    if (evaluateWorkload) {
      return evaluateExistingWorkload(root);
    }
    Path cat = catalogDir.isAbsolute() ? catalogDir : root.resolve(catalogDir);
    CatalogStore store = new CatalogStore(cat);
    Manifest m = store.loadManifest(manifestId).orElse(null);
    if (m == null) {
      System.err.println("manifest not found: " + manifestId);
      return 1;
    }
    Rect domain = new Rect(m.layout.domain.xmin, m.layout.domain.ymin,
        m.layout.domain.xmax, m.layout.domain.ymax);
    long bucketMs = m.layout.bucket_ms;
    long epochMs = m.layout.epoch_ms;

    Path data = dataDir.isAbsolute() ? dataDir : root.resolve(dataDir);
    System.out.println("ORACLE_CACHE_PHASE=load_cleaned data=" + data);
    System.out.flush();
    long t0 = System.currentTimeMillis();
    List<Trajectory> trajs = loadTrajectories(data, domain, m);
    System.out.println("ORACLE_CACHE_PROGRESS trajectories=" + trajs.size()
        + " elapsed_s=" + ((System.currentTimeMillis() - t0) / 1000));
    System.out.flush();

    Anchor a = pickAnchor(trajs, domain, bucketMs);
    System.out.println("ORACLE_CACHE_ANCHOR vehicle_id=" + a.vehicleId
        + " tid=" + a.tid + " trajectory_id=" + a.trajectoryId
        + " points=" + a.pointCount
        + " t=" + a.tMs + " x=" + a.x + " y=" + a.y);

    List<BoundIr> queries = buildWorkload(a, domain, bucketMs, epochMs, m);
    FullScanOracle oracle = new FullScanOracle(trajs);

    Map<String, Object> workload = new LinkedHashMap<String, Object>();
    workload.put("manifest_id", manifestId);
    workload.put("semantics_version", m.semantics_version);
    workload.put("anchor", a.toMap());
    workload.put("queries", queries);

    List<Map<String, Object>> answers = new ArrayList<Map<String, Object>>();
    int nonEmpty = 0;
    for (BoundIr ir : queries) {
      FullScanOracle.Answer ans = oracle.evaluate(ir);
      Map<String, Object> row = new LinkedHashMap<String, Object>();
      row.put("query_id", ir.query_id);
      row.put("trajectory_ids", ans.trajectoryIds);
      if (ans.topK != null) {
        List<Map<String, Object>> scored = new ArrayList<Map<String, Object>>();
        for (FullScanOracle.ScoredId s : ans.topK) {
          Map<String, Object> sc = new LinkedHashMap<String, Object>();
          sc.put("tid", s.tid);
          sc.put("trajectory_id", s.trajectoryId);
          sc.put("distance", s.distance);
          scored.add(sc);
        }
        row.put("top_k", scored);
      }
      answers.add(row);
      if (ans.trajectoryIds != null && !ans.trajectoryIds.isEmpty()) {
        nonEmpty++;
      }
      System.out.println("ORACLE_CACHE_QUERY id=" + ir.query_id
          + " count=" + (ans.trajectoryIds == null ? 0 : ans.trajectoryIds.size()));
    }

    Map<String, Object> cache = new LinkedHashMap<String, Object>();
    cache.put("manifest_id", manifestId);
    cache.put("trajectory_count", trajs.size());
    cache.put("non_empty", nonEmpty);
    cache.put("total", queries.size());
    cache.put("answers", answers);

    Path wOut = workloadOut.isAbsolute() ? workloadOut : root.resolve(workloadOut);
    Path oOut = oracleOut.isAbsolute() ? oracleOut : root.resolve(oracleOut);
    Files.createDirectories(wOut.getParent());
    Files.createDirectories(oOut.getParent());
    Files.write(wOut, MAPPER.writeValueAsString(workload).getBytes(StandardCharsets.UTF_8));
    Files.write(oOut, MAPPER.writeValueAsString(cache).getBytes(StandardCharsets.UTF_8));

    System.out.println("ORACLE_CACHE_OK workload=" + wOut + " oracle=" + oOut
        + " queries=" + queries.size() + " non_empty=" + nonEmpty);
    return 0;
  }

  /**
   * Build a diagnostic workload from independently sampled real trajectories.
   * Selection depends only on the fixed seed, trajectory identity, and spatial
   * grid; it never uses KART/CBO plans, costs, or execution results.
   */
  private int buildAdvancedComplexDiagnostic(Path root) throws Exception {
    Path cat = catalogDir.isAbsolute() ? catalogDir : root.resolve(catalogDir);
    CatalogStore store = new CatalogStore(cat);
    Manifest m = store.loadManifest(manifestId).orElse(null);
    if (m == null) {
      System.err.println("manifest not found: " + manifestId);
      return 1;
    }
    if (!"tdrive_v1_ready".equals(manifestId)) {
      System.err.println("advanced complex diagnostic currently requires manifest tdrive_v1_ready");
      return 2;
    }
    Rect domain = new Rect(m.layout.domain.xmin, m.layout.domain.ymin,
        m.layout.domain.xmax, m.layout.domain.ymax);
    long bucketMs = m.layout.bucket_ms;
    long epochMs = m.layout.epoch_ms;
    Path data = dataDir.isAbsolute() ? dataDir : root.resolve(dataDir);
    System.out.println("ADV_DIAG_PHASE=load_cleaned seed=" + diagnosticSeed + " data=" + data);
    System.out.flush();
    long loadStart = System.currentTimeMillis();
    List<Trajectory> trajs = loadTrajectories(data, domain, m);
    System.out.println("ADV_DIAG_PROGRESS trajectories=" + trajs.size()
        + " elapsed_s=" + ((System.currentTimeMillis() - loadStart) / 1000));
    List<Anchor> anchors = pickDiverseAnchors(trajs, domain, bucketMs, diagnosticSeed, 12);
    if (anchors.size() != 12) {
      System.err.println("need 12 distinct trajectory anchors; found " + anchors.size());
      return 2;
    }
    List<BoundIr> queries = buildAdvancedComplexDiagnosticWorkload(
        anchors, domain, bucketMs, epochMs, m);
    FullScanOracle oracle = new FullScanOracle(trajs);

    Map<String, Object> workload = new LinkedHashMap<String, Object>();
    workload.put("workload_id", "bound_ir_adv_complex_diag_v1");
    workload.put("manifest_id", manifestId);
    workload.put("semantics_version", m.semantics_version);
    workload.put("evaluation_role", "mechanism_diagnostic_not_population_estimate");
    workload.put("selection_seed", Long.valueOf(diagnosticSeed));
    workload.put("selection_policy",
        "eligible trajectories shuffled by seed; distinct vehicle and coarse spatial cell where possible; no method outcomes used");
    workload.put("query_families", new String[] {
        "temporal_only_control", "spatial_only", "vehicle_equality_control",
        "short_spatiotemporal", "long_spatiotemporal", "vehicle_spatiotemporal",
        "topk_dtw", "topk_frechet", "topk_hausdorff"
    });
    List<Map<String, Object>> anchorRows = new ArrayList<Map<String, Object>>();
    for (int i = 0; i < anchors.size(); i++) {
      Map<String, Object> row = anchors.get(i).toMap();
      row.put("query_id", queries.get(i).query_id);
      anchorRows.add(row);
    }
    workload.put("anchors", anchorRows);
    workload.put("queries", queries);

    List<Map<String, Object>> answers = new ArrayList<Map<String, Object>>();
    int nonEmpty = 0;
    int topKNonEmpty = 0;
    boolean topKComplete = true;
    for (BoundIr ir : queries) {
      FullScanOracle.Answer ans = oracle.evaluate(ir);
      Map<String, Object> row = new LinkedHashMap<String, Object>();
      row.put("query_id", ir.query_id);
      row.put("trajectory_ids", ans.trajectoryIds);
      if (ans.topK != null) {
        List<Map<String, Object>> scored = new ArrayList<Map<String, Object>>();
        for (FullScanOracle.ScoredId s : ans.topK) {
          Map<String, Object> sc = new LinkedHashMap<String, Object>();
          sc.put("tid", Long.valueOf(s.tid));
          sc.put("trajectory_id", s.trajectoryId);
          sc.put("distance", Double.valueOf(s.distance));
          scored.add(sc);
        }
        row.put("top_k", scored);
        row.put("top_k_count", Integer.valueOf(scored.size()));
        if (!scored.isEmpty()) {
          topKNonEmpty++;
        }
        int requestedK = ir.result != null && ir.result.k != null
            ? ir.result.k.intValue() : 0;
        if (scored.size() < requestedK) {
          topKComplete = false;
          System.err.println("ADV_DIAG_INCOMPLETE_TOPK id=" + ir.query_id
              + " requested=" + requestedK + " actual=" + scored.size());
        }
      }
      answers.add(row);
      if (ans.trajectoryIds != null && !ans.trajectoryIds.isEmpty()) {
        nonEmpty++;
      }
      System.out.println("ADV_DIAG_ORACLE id=" + ir.query_id
          + " candidates=" + (ans.trajectoryIds == null ? 0 : ans.trajectoryIds.size())
          + (ans.topK == null ? "" : " topk=" + ans.topK.size()));
    }

    Map<String, Object> cache = new LinkedHashMap<String, Object>();
    cache.put("manifest_id", manifestId);
    cache.put("semantics_version", m.semantics_version);
    cache.put("trajectory_count", Integer.valueOf(trajs.size()));
    cache.put("non_empty", Integer.valueOf(nonEmpty));
    cache.put("topk_non_empty", Integer.valueOf(topKNonEmpty));
    cache.put("total", Integer.valueOf(queries.size()));
    cache.put("source", "FullScanOracle");
    cache.put("answers", answers);

    Path wOut = diagnosticWorkloadOut.isAbsolute()
        ? diagnosticWorkloadOut : root.resolve(diagnosticWorkloadOut);
    Path oOut = diagnosticOracleOut.isAbsolute()
        ? diagnosticOracleOut : root.resolve(diagnosticOracleOut);
    if (!topKComplete) {
      System.err.println("refusing to write diagnostic set with underfilled Top-K Oracle answers");
      return 2;
    }
    if ((Files.exists(wOut) || Files.exists(oOut)) && !diagnosticOverwrite) {
      System.err.println("refusing to overwrite existing diagnostic output: " + wOut + " or " + oOut);
      return 2;
    }
    if (Files.exists(wOut) != Files.exists(oOut)) {
      System.err.println("refusing partial overwrite; workload and Oracle outputs must both exist or both be absent");
      return 2;
    }
    Files.createDirectories(wOut.getParent());
    Files.createDirectories(oOut.getParent());
    Files.write(wOut, MAPPER.writeValueAsString(workload).getBytes(StandardCharsets.UTF_8));
    Files.write(oOut, MAPPER.writeValueAsString(cache).getBytes(StandardCharsets.UTF_8));
    System.out.println("ADV_DIAG_OK workload=" + wOut + " oracle=" + oOut
        + " queries=" + queries.size() + " non_empty=" + nonEmpty
        + " topk_non_empty=" + topKNonEmpty + " seed=" + diagnosticSeed);
    return 0;
  }

  static List<Anchor> pickDiverseAnchors(List<Trajectory> trajectories, Rect domain,
                                        long bucketMs, long seed, int requested) {
    if (trajectories == null || trajectories.isEmpty() || requested <= 0) {
      return Collections.emptyList();
    }
    List<Trajectory> pool = new ArrayList<Trajectory>(trajectories);
    Collections.sort(pool, new Comparator<Trajectory>() {
      @Override
      public int compare(Trajectory a, Trajectory b) {
        return Long.compare(a.tid, b.tid);
      }
    });
    Collections.shuffle(pool, new Random(seed));
    List<Anchor> out = new ArrayList<Anchor>(requested);
    Set<String> vehicles = new HashSet<String>();
    Set<Integer> cells = new HashSet<Integer>();
    Set<Long> tids = new HashSet<Long>();
    for (Trajectory t : pool) {
      Anchor a = anchorAtMidpoint(t, domain, bucketMs);
      if (a == null || tids.contains(Long.valueOf(a.tid))) {
        continue;
      }
      int cell = coarseSpatialCell(a, domain);
      if (vehicles.contains(a.vehicleId) || cells.contains(Integer.valueOf(cell))) {
        continue;
      }
      out.add(a);
      tids.add(Long.valueOf(a.tid));
      vehicles.add(a.vehicleId);
      cells.add(Integer.valueOf(cell));
      if (out.size() >= requested) {
        return out;
      }
    }
    // If some grid cells are empty in the sampled data, fill deterministically
    // with new trajectories, preserving unique vehicles whenever possible.
    for (Trajectory t : pool) {
      Anchor a = anchorAtMidpoint(t, domain, bucketMs);
      if (a == null || tids.contains(Long.valueOf(a.tid))) {
        continue;
      }
      if (vehicles.contains(a.vehicleId)) {
        continue;
      }
      out.add(a);
      tids.add(Long.valueOf(a.tid));
      vehicles.add(a.vehicleId);
      if (out.size() >= requested) {
        break;
      }
    }
    return out;
  }

  private static Anchor anchorAtMidpoint(Trajectory t, Rect domain, long bucketMs) {
    if (t == null || t.size() < 8 || t.vehicleId == null || t.vehicleId.trim().isEmpty()) {
      return null;
    }
    CanonicalPoint p = t.points().get(t.size() / 2);
    if (p == null || !Double.isFinite(p.xM) || !Double.isFinite(p.yM)
        || p.xM < domain.minX || p.xM > domain.maxX
        || p.yM < domain.minY || p.yM > domain.maxY) {
      return null;
    }
    Anchor a = new Anchor();
    a.vehicleId = t.vehicleId;
    a.trajectoryId = t.trajectoryId;
    a.tid = t.tid;
    a.pointCount = t.size();
    a.tMs = p.timestampMs;
    a.x = p.xM;
    a.y = p.yM;
    a.bucketMs = bucketMs;
    return a;
  }

  private static int coarseSpatialCell(Anchor a, Rect domain) {
    int x = gridCoordinate(a.x, domain.minX, domain.maxX);
    int y = gridCoordinate(a.y, domain.minY, domain.maxY);
    return y * 4 + x;
  }

  private static int gridCoordinate(double value, double min, double max) {
    if (!(max > min)) {
      return 0;
    }
    int cell = (int) Math.floor((value - min) * 4.0d / (max - min));
    return Math.max(0, Math.min(3, cell));
  }

  static List<BoundIr> buildAdvancedComplexDiagnosticWorkload(List<Anchor> anchors,
                                                              Rect domain,
                                                              long bucketMs,
                                                              long epochMs,
                                                              Manifest m) {
    if (anchors == null || anchors.size() < 12) {
      throw new IllegalArgumentException("12 distinct anchors are required");
    }
    double dx = domain.maxX - domain.minX;
    double dy = domain.maxY - domain.minY;
    List<BoundIr> out = new ArrayList<BoundIr>(12);
    Anchor a = anchors.get(0);
    out.add(ids("advdiag_t_only", window(a, 6, bucketMs, epochMs), null, null, m));

    a = anchors.get(1);
    out.add(ids("advdiag_spatial_only", null,
        spatial(a.x, a.y, 700.0d, 700.0d, domain), null, m));

    a = anchors.get(2);
    out.add(ids("advdiag_vehicle_only", null, null, vehicleEq(a.vehicleId), m));

    a = anchors.get(3);
    out.add(ids("advdiag_st_short_tight", window(a, 3, bucketMs, epochMs),
        spatial(a.x, a.y, 1_500.0d, 1_500.0d, domain), null, m));

    a = anchors.get(4);
    out.add(ids("advdiag_st_short_wide", window(a, 6, bucketMs, epochMs),
        spatial(a.x, a.y, dx * 0.06d, dy * 0.06d, domain), null, m));

    a = anchors.get(5);
    out.add(ids("advdiag_st_long_tight", window(a, 72, bucketMs, epochMs),
        spatial(a.x, a.y, 500.0d, 500.0d, domain), null, m));

    a = anchors.get(6);
    out.add(ids("advdiag_st_long_wide", window(a, 72, bucketMs, epochMs),
        spatial(a.x, a.y, dx * 0.18d, dy * 0.18d, domain), null, m));

    a = anchors.get(7);
    out.add(ids("advdiag_vehicle_time", window(a, 24, bucketMs, epochMs),
        null, vehicleEq(a.vehicleId), m));

    a = anchors.get(8);
    out.add(ids("advdiag_vehicle_st", window(a, 24, bucketMs, epochMs),
        spatial(a.x, a.y, dx * 0.10d, dy * 0.10d, domain),
        vehicleEq(a.vehicleId), m));

    a = anchors.get(9);
    out.add(topk("advdiag_topk_dtw", window(a, 72, bucketMs, epochMs),
        spatial(a.x, a.y, dx * 0.15d, dy * 0.15d, domain),
        a.tid, 3, m, null, "DTW"));

    a = anchors.get(10);
    out.add(topk("advdiag_topk_frechet", window(a, 24, bucketMs, epochMs),
        spatial(a.x, a.y, dx * 0.10d, dy * 0.10d, domain),
        a.tid, 5, m, null, "FRECHET"));

    a = anchors.get(11);
    out.add(topk("advdiag_topk_hausdorff", null,
        spatial(a.x, a.y, dx * 0.07d, dy * 0.07d, domain),
        a.tid, 3, m, null, "HAUSDORFF"));
    return out;
  }

  private static BoundIr.Temporal window(Anchor a, int bucketCount,
                                         long bucketMs, long epochMs) {
    long center = alignBucket(a.tMs, bucketMs, epochMs);
    long start = Math.max(epochMs, center - (bucketCount / 2L) * bucketMs);
    return temporal(start, start + bucketCount * bucketMs);
  }

  /** Pick a mid-sized trajectory near domain center for stable smoke anchors. */
  static Anchor pickAnchor(List<Trajectory> trajs, Rect domain, long bucketMs) {
    double cx = (domain.minX + domain.maxX) / 2.0;
    double cy = (domain.minY + domain.maxY) / 2.0;
    Trajectory best = null;
    double bestScore = Double.POSITIVE_INFINITY;
    for (Trajectory t : trajs) {
      int n = t.size();
      if (n < 8 || n > 120) {
        continue;
      }
      CanonicalPoint mid = t.points().get(n / 2);
      double dx = mid.xM - cx;
      double dy = mid.yM - cy;
      double dist2 = dx * dx + dy * dy;
      // prefer moderate length + near center
      double score = dist2 + (Math.abs(n - 40) * 1e8);
      if (score < bestScore) {
        bestScore = score;
        best = t;
      }
    }
    if (best == null) {
      best = trajs.get(0);
    }
    CanonicalPoint p = best.points().get(best.size() / 2);
    Anchor a = new Anchor();
    a.vehicleId = best.vehicleId;
    a.trajectoryId = best.trajectoryId;
    a.tid = best.tid;
    a.pointCount = best.size();
    a.tMs = p.timestampMs;
    a.x = p.xM;
    a.y = p.yM;
    a.bucketMs = bucketMs;
    return a;
  }

  static List<BoundIr> buildWorkload(Anchor a, Rect domain, long bucketMs, long epochMs,
                                      Manifest m) {
    List<BoundIr> out = new ArrayList<BoundIr>();
    double dx = domain.maxX - domain.minX;
    double dy = domain.maxY - domain.minY;

    // Clamp helpers relative to domain
    double smallHalf = 400.0;      // ~800m box
    double largeHalfX = dx * 0.12; // ~12% of domain
    double largeHalfY = dy * 0.12;

    long tSmallStart = alignBucket(a.tMs, bucketMs, epochMs);
    long tSmallEnd = tSmallStart + bucketMs; // one bucket
    long tMedStart = alignBucket(a.tMs - 3 * bucketMs, bucketMs, epochMs);
    long tMedEnd = tMedStart + 6 * bucketMs;
    long tLargeStart = epochMs;
    long tLargeEnd = epochMs + 24L * 60L * 60L * 1000L; // 24h from epoch
    // keep within observed profile (~6 days); cap at epoch+5d
    long tHugeEnd = Math.min(epochMs + 5L * 24L * 60L * 60L * 1000L,
        (long) Math.ceil(domainTimeCap(a.tMs, epochMs)));

    // --- temporal only ---
    out.add(ids("t_small_1", temporal(tSmallStart, tSmallEnd), null, null, m));
    out.add(ids("t_small_2", temporal(tMedStart, tMedStart + 2 * bucketMs), null, null, m));
    out.add(ids("t_large_1", temporal(tLargeStart, tLargeEnd), null, null, m));
    out.add(ids("t_large_2", temporal(tMedStart, tHugeEnd), null, null, m));

    // --- spatial only ---
    out.add(ids("s_small_1", null, spatial(a.x, a.y, smallHalf, smallHalf, domain), null, m));
    out.add(ids("s_small_2", null, spatial(a.x + 200, a.y - 150, 250, 250, domain), null, m));
    out.add(ids("s_large_1", null, spatial(a.x, a.y, largeHalfX, largeHalfY, domain), null, m));
    out.add(ids("s_large_2", null,
        spatial((domain.minX + domain.maxX) / 2, (domain.minY + domain.maxY) / 2,
            dx * 0.2, dy * 0.2, domain), null, m));

    // --- ST combinations ---
    out.add(ids("st_ss_1", temporal(tSmallStart, tSmallEnd),
        spatial(a.x, a.y, smallHalf, smallHalf, domain), null, m));
    out.add(ids("st_ss_2", temporal(tMedStart, tMedStart + 2 * bucketMs),
        spatial(a.x, a.y, 600, 600, domain), null, m));
    out.add(ids("st_sl_1", temporal(tSmallStart, tSmallEnd),
        spatial(a.x, a.y, largeHalfX, largeHalfY, domain), null, m));
    out.add(ids("st_ls_1", temporal(tLargeStart, tLargeEnd),
        spatial(a.x, a.y, smallHalf, smallHalf, domain), null, m));
    out.add(ids("st_ll_1", temporal(tMedStart, tHugeEnd),
        spatial(a.x, a.y, largeHalfX * 0.5, largeHalfY * 0.5, domain), null, m));

    // --- hash / vehicle_id ---
    out.add(ids("h_eq_1", null, null, vehicleEq(a.vehicleId), m));
    out.add(ids("h_t_small", temporal(tSmallStart, tSmallEnd), null, vehicleEq(a.vehicleId), m));
    out.add(ids("h_t_large", temporal(tLargeStart, tLargeEnd), null, vehicleEq(a.vehicleId), m));
    out.add(ids("h_s_small", null, spatial(a.x, a.y, smallHalf * 2, smallHalf * 2, domain),
        vehicleEq(a.vehicleId), m));
    out.add(ids("h_st_1", temporal(tMedStart, tMedEnd),
        spatial(a.x, a.y, largeHalfX * 0.3, largeHalfY * 0.3, domain),
        vehicleEq(a.vehicleId), m));

    // --- Top-K (tight enough for speed, wide enough for non-empty after exclude_reference) ---
    out.add(topk("topk_st_1", temporal(tMedStart, tMedStart + 3 * bucketMs),
        spatial(a.x, a.y, 800, 800, domain), a.tid, 3, m));
    out.add(topk("topk_st_2", temporal(tMedStart, tMedStart + 4 * bucketMs),
        spatial(a.x, a.y, 1200, 1200, domain), a.tid, 5, m));
    // Prefer ST over vehicle-only so exclude_reference still leaves other taxis
    out.add(topk("topk_st_3", temporal(tSmallStart, tSmallEnd + 2 * bucketMs),
        spatial(a.x, a.y, largeHalfX * 0.15, largeHalfY * 0.15, domain), a.tid, 3, m));
    out.add(topk("topk_s_1", null, spatial(a.x, a.y, 700, 700, domain), a.tid, 3, m));
    // Non-DTW metrics (FR-6.3 / supported-semantics): same window as topk_st_1
    out.add(topk("topk_st_frechet_1", temporal(tMedStart, tMedStart + 3 * bucketMs),
        spatial(a.x, a.y, 800, 800, domain), a.tid, 3, m, null, "FRECHET"));
    out.add(topk("topk_st_hausdorff_1", temporal(tMedStart, tMedStart + 3 * bucketMs),
        spatial(a.x, a.y, 800, 800, domain), a.tid, 3, m, null, "HAUSDORFF"));

    return out;
  }

  private static long domainTimeCap(long tMs, long epochMs) {
    return Math.max(tMs + 2L * 24L * 60L * 60L * 1000L, epochMs + 2L * 24L * 60L * 60L * 1000L);
  }

  private static long alignBucket(long tMs, long bucketMs, long epochMs) {
    long b = (tMs - epochMs) / bucketMs;
    if (b < 0) {
      b = 0;
    }
    return epochMs + b * bucketMs;
  }

  private static BoundIr.Temporal temporal(long start, long end) {
    BoundIr.Temporal t = new BoundIr.Temporal();
    t.start_ms = start;
    t.end_ms = end;
    return t;
  }

  private static BoundIr.Spatial spatial(double cx, double cy, double halfX, double halfY,
                                         Rect domain) {
    BoundIr.Spatial s = new BoundIr.Spatial();
    s.min_x = Math.max(domain.minX, cx - halfX);
    s.max_x = Math.min(domain.maxX, cx + halfX);
    s.min_y = Math.max(domain.minY, cy - halfY);
    s.max_y = Math.min(domain.maxY, cy + halfY);
    s.relation = "INTERSECTS";
    s.boundary = "INCLUDED";
    return s;
  }

  private static List<BoundIr.Predicate> vehicleEq(String vehicleId) {
    BoundIr.Predicate p = new BoundIr.Predicate();
    p.field = "vehicle_id";
    p.op = "EQ";
    p.value = vehicleId;
    List<BoundIr.Predicate> list = new ArrayList<BoundIr.Predicate>();
    list.add(p);
    return list;
  }

  private static BoundIr ids(String id, BoundIr.Temporal t, BoundIr.Spatial s,
                             List<BoundIr.Predicate> preds, Manifest m) {
    BoundIr ir = base(id, m);
    ir.temporal = t;
    ir.spatial = s;
    if (preds != null) {
      ir.predicates = preds;
    }
    ir.result = new BoundIr.Result();
    ir.result.mode = "TRAJECTORY_IDS";
    ir.result.tie_breaker = "TID_ASC";
    return ir;
  }

  private static BoundIr topk(String id, BoundIr.Temporal t, BoundIr.Spatial s,
                              long refTid, int k, Manifest m) {
    return topk(id, t, s, refTid, k, m, null, "DTW");
  }

  private static BoundIr topk(String id, BoundIr.Temporal t, BoundIr.Spatial s,
                              long refTid, int k, Manifest m, List<BoundIr.Predicate> preds) {
    return topk(id, t, s, refTid, k, m, preds, "DTW");
  }

  private static BoundIr topk(String id, BoundIr.Temporal t, BoundIr.Spatial s,
                              long refTid, int k, Manifest m, List<BoundIr.Predicate> preds,
                              String metric) {
    BoundIr ir = base(id, m);
    ir.temporal = t;
    ir.spatial = s;
    if (preds != null) {
      ir.predicates = preds;
    }
    ir.similarity = new BoundIr.Similarity();
    ir.similarity.metric = metric != null ? metric : "DTW";
    ir.similarity.reference_tid = refTid;
    ir.similarity.scope = "FULL_TRAJECTORY";
    ir.similarity.exclude_reference = true;
    ir.similarity.local_distance = "EUCLIDEAN";
    ir.similarity.normalization = "NONE";
    ir.result = new BoundIr.Result();
    ir.result.mode = "TOP_K";
    ir.result.k = k;
    ir.result.tie_breaker = "TID_ASC";
    return ir;
  }

  private static BoundIr base(String id, Manifest m) {
    BoundIr ir = new BoundIr();
    ir.ir_version = "1.0";
    ir.query_id = id;
    ir.source = new BoundIr.Source();
    ir.source.dataset_id = "tdrive_v1";
    ir.source.entity = "trajectory";
    ir.semantics = new BoundIr.Semantics();
    ir.semantics.mode = "OBSERVED_POINT";
    ir.semantics.coupling = "SAME_POINT";
    ir.snapshot = new BoundIr.Snapshot();
    ir.snapshot.manifest_id = m.manifest_id;
    ir.snapshot.semantics_version = m.semantics_version;
    return ir;
  }

  static final class Anchor {
    String vehicleId;
    String trajectoryId;
    long tid;
    int pointCount;
    long tMs;
    double x;
    double y;
    long bucketMs;

    Map<String, Object> toMap() {
      Map<String, Object> m = new LinkedHashMap<String, Object>();
      m.put("vehicle_id", vehicleId);
      m.put("trajectory_id", trajectoryId);
      m.put("tid", tid);
      m.put("point_count", pointCount);
      m.put("t_ms", tMs);
      m.put("x", x);
      m.put("y", y);
      return m;
    }
  }

  /**
   * Append FRECHET / HAUSDORFF clones of existing {@code topk_st_1} without regenerating
   * the rest of the frozen smoke set.
   */
  private int appendTopkMetrics(Path root) throws Exception {
    Path wOut = workloadOut.isAbsolute() ? workloadOut : root.resolve(workloadOut);
    Path oOut = oracleOut.isAbsolute() ? oracleOut : root.resolve(oracleOut);
    if (!Files.isRegularFile(wOut) || !Files.isRegularFile(oOut)) {
      System.err.println("existing smoke workload/oracle required: " + wOut + " / " + oOut);
      return 2;
    }
    Path cat = catalogDir.isAbsolute() ? catalogDir : root.resolve(catalogDir);
    CatalogStore store = new CatalogStore(cat);
    Manifest m = store.loadManifest(manifestId).orElse(null);
    if (m == null) {
      System.err.println("manifest not found: " + manifestId);
      return 1;
    }
    Rect domain = new Rect(m.layout.domain.xmin, m.layout.domain.ymin,
        m.layout.domain.xmax, m.layout.domain.ymax);

    @SuppressWarnings("unchecked")
    Map<String, Object> workload = MAPPER.readValue(Files.readAllBytes(wOut), Map.class);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> queries = (List<Map<String, Object>>) workload.get("queries");
    Map<String, Map<String, Object>> byId = new LinkedHashMap<String, Map<String, Object>>();
    Map<String, Object> topkSt1 = null;
    for (Map<String, Object> q : queries) {
      String id = String.valueOf(q.get("query_id"));
      byId.put(id, q);
      if ("topk_st_1".equals(id)) {
        topkSt1 = q;
      }
    }
    if (topkSt1 == null) {
      System.err.println("topk_st_1 not found in workload; cannot clone");
      return 2;
    }

    String[][] specs = new String[][] {
        {"topk_st_frechet_1", "FRECHET"},
        {"topk_st_hausdorff_1", "HAUSDORFF"}
    };
    List<BoundIr> toEval = new ArrayList<BoundIr>();
    for (String[] spec : specs) {
      if (byId.containsKey(spec[0])) {
        System.out.println("ORACLE_CACHE_APPEND skip existing id=" + spec[0]);
        continue;
      }
      @SuppressWarnings("unchecked")
      Map<String, Object> clone = MAPPER.convertValue(topkSt1, Map.class);
      clone.put("query_id", spec[0]);
      @SuppressWarnings("unchecked")
      Map<String, Object> sim = (Map<String, Object>) clone.get("similarity");
      if (sim == null) {
        System.err.println("topk_st_1 missing similarity");
        return 2;
      }
      sim.put("metric", spec[1]);
      queries.add(clone);
      toEval.add(MAPPER.convertValue(clone, BoundIr.class));
      System.out.println("ORACLE_CACHE_APPEND add id=" + spec[0] + " metric=" + spec[1]);
    }
    if (toEval.isEmpty()) {
      System.out.println("ORACLE_CACHE_APPEND nothing to add");
      return 0;
    }

    Path data = dataDir.isAbsolute() ? dataDir : root.resolve(dataDir);
    System.out.println("ORACLE_CACHE_PHASE=load_cleaned data=" + data);
    List<Trajectory> trajs = loadTrajectories(data, domain, m);
    FullScanOracle oracle = new FullScanOracle(trajs);

    @SuppressWarnings("unchecked")
    Map<String, Object> cache = MAPPER.readValue(Files.readAllBytes(oOut), Map.class);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> answers = (List<Map<String, Object>>) cache.get("answers");
    if (answers == null) {
      answers = new ArrayList<Map<String, Object>>();
      cache.put("answers", answers);
    }
    int nonEmpty = cache.get("non_empty") instanceof Number
        ? ((Number) cache.get("non_empty")).intValue() : 0;
    for (BoundIr ir : toEval) {
      FullScanOracle.Answer ans = oracle.evaluate(ir);
      Map<String, Object> row = new LinkedHashMap<String, Object>();
      row.put("query_id", ir.query_id);
      row.put("trajectory_ids", ans.trajectoryIds);
      if (ans.topK != null) {
        List<Map<String, Object>> scored = new ArrayList<Map<String, Object>>();
        for (FullScanOracle.ScoredId s : ans.topK) {
          Map<String, Object> sc = new LinkedHashMap<String, Object>();
          sc.put("tid", Long.valueOf(s.tid));
          sc.put("trajectory_id", s.trajectoryId);
          sc.put("distance", Double.valueOf(s.distance));
          scored.add(sc);
        }
        row.put("top_k", scored);
      }
      answers.add(row);
      if (ans.trajectoryIds != null && !ans.trajectoryIds.isEmpty()) {
        nonEmpty++;
      }
      System.out.println("ORACLE_CACHE_QUERY id=" + ir.query_id
          + " count=" + (ans.trajectoryIds == null ? 0 : ans.trajectoryIds.size()));
    }
    cache.put("non_empty", Integer.valueOf(nonEmpty));
    cache.put("total", Integer.valueOf(answers.size()));
    workload.put("queries", queries);

    Files.write(wOut, MAPPER.writeValueAsString(workload).getBytes(StandardCharsets.UTF_8));
    Files.write(oOut, MAPPER.writeValueAsString(cache).getBytes(StandardCharsets.UTF_8));
    System.out.println("ORACLE_CACHE_APPEND_OK workload=" + wOut + " oracle=" + oOut
        + " total=" + answers.size());
    return 0;
  }

  /**
   * Recompute FullScanOracle answers for every BoundIR in an existing workload.
   * Merges into oracle-out (replacing answers for matching query_id).
   */
  private int evaluateExistingWorkload(Path root) throws Exception {
    Path wIn = workloadIn != null ? workloadIn : workloadOut;
    wIn = wIn.isAbsolute() ? wIn : root.resolve(wIn);
    Path oOut = oracleOut.isAbsolute() ? oracleOut : root.resolve(oracleOut);
    if (!Files.isRegularFile(wIn)) {
      System.err.println("workload not found: " + wIn);
      return 2;
    }
    Path cat = catalogDir.isAbsolute() ? catalogDir : root.resolve(catalogDir);
    CatalogStore store = new CatalogStore(cat);
    Manifest m = store.loadManifest(manifestId).orElse(null);
    if (m == null) {
      System.err.println("manifest not found: " + manifestId);
      return 1;
    }
    Rect domain = new Rect(m.layout.domain.xmin, m.layout.domain.ymin,
        m.layout.domain.xmax, m.layout.domain.ymax);

    com.fasterxml.jackson.databind.JsonNode rootNode = MAPPER.readTree(Files.readAllBytes(wIn));
    com.fasterxml.jackson.databind.JsonNode arr = rootNode.get("queries");
    if (arr == null || !arr.isArray()) {
      System.err.println("workload missing queries[]: " + wIn);
      return 2;
    }
    List<BoundIr> queries = new ArrayList<BoundIr>();
    for (com.fasterxml.jackson.databind.JsonNode q : arr) {
      if (q.has("utterance") && !q.has("ir_version")) {
        continue;
      }
      queries.add(MAPPER.treeToValue(q, BoundIr.class));
    }
    Path data = dataDir.isAbsolute() ? dataDir : root.resolve(dataDir);
    System.out.println("ORACLE_EVAL_PHASE=load_cleaned data=" + data + " queries=" + queries.size());
    List<Trajectory> trajs = loadTrajectories(data, domain, m);
    FullScanOracle oracle = new FullScanOracle(trajs);

    Map<String, Map<String, Object>> byId = new LinkedHashMap<String, Map<String, Object>>();
    if (Files.isRegularFile(oOut)) {
      @SuppressWarnings("unchecked")
      Map<String, Object> prev = MAPPER.readValue(Files.readAllBytes(oOut), Map.class);
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> prevAns = (List<Map<String, Object>>) prev.get("answers");
      if (prevAns != null) {
        for (Map<String, Object> row : prevAns) {
          if (row != null && row.get("query_id") != null) {
            byId.put(String.valueOf(row.get("query_id")), row);
          }
        }
      }
    }
    int nonEmpty = 0;
    for (BoundIr ir : queries) {
      FullScanOracle.Answer ans = oracle.evaluate(ir);
      Map<String, Object> row = new LinkedHashMap<String, Object>();
      row.put("query_id", ir.query_id);
      row.put("trajectory_ids", ans.trajectoryIds);
      if (ans.topK != null) {
        List<Map<String, Object>> scored = new ArrayList<Map<String, Object>>();
        for (FullScanOracle.ScoredId s : ans.topK) {
          Map<String, Object> sc = new LinkedHashMap<String, Object>();
          sc.put("tid", Long.valueOf(s.tid));
          sc.put("trajectory_id", s.trajectoryId);
          sc.put("distance", Double.valueOf(s.distance));
          scored.add(sc);
        }
        row.put("top_k", scored);
      }
      byId.put(ir.query_id, row);
      if (ans.trajectoryIds != null && !ans.trajectoryIds.isEmpty()) {
        nonEmpty++;
      }
      System.out.println("ORACLE_EVAL id=" + ir.query_id
          + " count=" + (ans.trajectoryIds == null ? 0 : ans.trajectoryIds.size()));
    }
    Map<String, Object> cache = new LinkedHashMap<String, Object>();
    cache.put("manifest_id", manifestId);
    cache.put("semantics_version",
        rootNode.has("semantics_version") ? rootNode.get("semantics_version").asText() : m.semantics_version);
    List<Map<String, Object>> answers = new ArrayList<Map<String, Object>>();
    for (BoundIr ir : queries) {
      answers.add(byId.get(ir.query_id));
    }
    cache.put("answers", answers);
    cache.put("total", Integer.valueOf(answers.size()));
    cache.put("non_empty", Integer.valueOf(nonEmpty));
    Files.createDirectories(oOut.getParent());
    Files.write(oOut, MAPPER.writeValueAsString(cache).getBytes(StandardCharsets.UTF_8));
    System.out.println("ORACLE_EVAL_OK oracle=" + oOut + " total=" + answers.size()
        + " non_empty=" + nonEmpty);
    return 0;
  }

  private List<Trajectory> loadTrajectories(Path data, Rect domain, Manifest m) throws Exception {
    String fmt = format == null || format.trim().isEmpty() ? "tdrive" : format.trim();
    String useCrs = crs;
    if (useCrs == null || useCrs.trim().isEmpty()) {
      if (m != null && m.layout != null && m.layout.crs != null && !m.layout.crs.trim().isEmpty()) {
        useCrs = m.layout.crs.trim();
      } else {
        useCrs = kart.geo.Projection.DEFAULT_CRS;
      }
    }
    System.out.println("ORACLE_LOAD format=" + fmt + " crs=" + useCrs);
    return TDriveLoader.loadCleaned(data, domain, fmt, useCrs);
  }

  private Path resolveRoot() {
    if (configRoot != null) {
      return configRoot;
    }
    String prop = System.getProperty("kart.root");
    if (prop != null && !prop.isEmpty()) {
      return Paths.get(prop);
    }
    return Paths.get(".").toAbsolutePath().normalize();
  }
}
