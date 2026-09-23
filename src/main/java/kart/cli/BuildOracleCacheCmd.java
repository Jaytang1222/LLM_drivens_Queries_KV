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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

  @Override
  public Integer call() throws Exception {
    Path root = resolveRoot();
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
    List<Trajectory> trajs = TDriveLoader.loadCleaned(data, domain);
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
    return topk(id, t, s, refTid, k, m, null);
  }

  private static BoundIr topk(String id, BoundIr.Temporal t, BoundIr.Spatial s,
                              long refTid, int k, Manifest m, List<BoundIr.Predicate> preds) {
    BoundIr ir = base(id, m);
    ir.temporal = t;
    ir.spatial = s;
    if (preds != null) {
      ir.predicates = preds;
    }
    ir.similarity = new BoundIr.Similarity();
    ir.similarity.metric = "DTW";
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
