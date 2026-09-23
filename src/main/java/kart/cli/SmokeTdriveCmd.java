package kart.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import kart.catalog.CatalogStore;
import kart.catalog.Manifest;
import kart.compile.LayoutContext;
import kart.exec.HBaseBackend;
import kart.exec.QueryResult;
import kart.ir.BoundIr;
import kart.query.QueryEngine;
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
 * In-process T-Drive smoke: load BoundIR workload + oracle cache, run QueryEngine
 * against HBase, compare answers, write report (T2.9).
 */
@Command(name = "smoke-tdrive",
    description = "Run T-Drive BoundIR smoke vs oracle cache on HBase")
public final class SmokeTdriveCmd implements Callable<Integer> {

  private static final ObjectMapper MAPPER = new ObjectMapper()
      .enable(SerializationFeature.INDENT_OUTPUT);

  private static final double DIST_EPS = 1e-3;

  @Option(names = "--workload", defaultValue = "experiments/workloads/tdrive_smoke.json")
  private Path workloadPath;

  @Option(names = "--oracle", defaultValue = "experiments/workloads/tdrive_smoke.oracle.json")
  private Path oraclePath;

  @Option(names = "--catalog", defaultValue = "catalog")
  private Path catalogDir;

  @Option(names = "--manifest", defaultValue = "tdrive_v1_ready")
  private String manifestId;

  @Option(names = "--report", defaultValue = "experiments/results/tdrive_smoke_report.json")
  private Path reportPath;

  @Option(names = "--runs", defaultValue = "runs/smoke-tdrive")
  private Path runsDir;

  @Option(names = "--config-root")
  private Path configRoot;

  @Override
  public Integer call() throws Exception {
    Path root = resolveRoot();
    Path wPath = workloadPath.isAbsolute() ? workloadPath : root.resolve(workloadPath);
    Path oPath = oraclePath.isAbsolute() ? oraclePath : root.resolve(oraclePath);
    if (!Files.isRegularFile(wPath)) {
      System.err.println("workload not found: " + wPath);
      return 2;
    }
    if (!Files.isRegularFile(oPath)) {
      System.err.println("oracle cache not found: " + oPath + " (run build-oracle-cache first)");
      return 2;
    }

    JsonNode workload = MAPPER.readTree(Files.readAllBytes(wPath));
    JsonNode oracleRoot = MAPPER.readTree(Files.readAllBytes(oPath));
    List<BoundIr> queries = new ArrayList<BoundIr>();
    for (JsonNode q : workload.get("queries")) {
      queries.add(MAPPER.treeToValue(q, BoundIr.class));
    }
    Map<String, OracleAnswer> answers = new LinkedHashMap<String, OracleAnswer>();
    for (JsonNode a : oracleRoot.get("answers")) {
      OracleAnswer oa = new OracleAnswer();
      oa.queryId = a.get("query_id").asText();
      oa.trajectoryIds = new ArrayList<String>();
      for (JsonNode id : a.get("trajectory_ids")) {
        oa.trajectoryIds.add(id.asText());
      }
      if (a.has("top_k") && a.get("top_k").isArray()) {
        oa.topK = new ArrayList<OracleScored>();
        for (JsonNode s : a.get("top_k")) {
          OracleScored sc = new OracleScored();
          sc.tid = s.get("tid").asLong();
          sc.trajectoryId = s.get("trajectory_id").asText();
          sc.distance = s.get("distance").asDouble();
          oa.topK.add(sc);
        }
      }
      answers.put(oa.queryId, oa);
    }

    Path cat = catalogDir.isAbsolute() ? catalogDir : root.resolve(catalogDir);
    CatalogStore store = new CatalogStore(cat);
    Manifest manifest = store.loadManifest(manifestId).orElse(null);
    if (manifest == null) {
      System.err.println("manifest not found: " + manifestId);
      return 1;
    }
    LayoutContext layout = LayoutContext.from(manifest);
    kart.catalog.StatsSnapshot stats = store.loadStats(manifestId).orElse(null);
    kart.config.AppConfig cfg = kart.config.AppConfig.load(root);
    Path runsRoot = runsDir.isAbsolute() ? runsDir : root.resolve(runsDir);
    Files.createDirectories(runsRoot);

    Path site = root.resolve("config/hbase/hbase-site.xml");
    HBaseBackend kv = new HBaseBackend(HBaseBackend.open(site));
    kart.exec.ExecLimits limits = kart.exec.ExecLimits.defaults();
    limits.maxCandidateChunks = (int) Math.min(Integer.MAX_VALUE,
        cfg.planner().max_candidate_chunks);
    limits.maxDtwCells = cfg.planner().max_dtw_cells;
    limits.fetchBatch = cfg.planner().fetch_batch_size;
    QueryEngine engine = new QueryEngine(kv, layout, limits, stats, cfg.planner().cost);

    int passed = 0;
    int failed = 0;
    List<Map<String, Object>> results = new ArrayList<Map<String, Object>>();

    try {
      for (BoundIr ir : queries) {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("query_id", ir.query_id);
        OracleAnswer expected = answers.get(ir.query_id);
        if (expected == null) {
          row.put("pass", false);
          row.put("error", "missing oracle answer");
          failed++;
          results.add(row);
          System.out.println("SMOKE FAIL " + ir.query_id + " missing oracle");
          continue;
        }

        long t0 = System.currentTimeMillis();
        QueryEngine.RunResult rr;
        try {
          rr = engine.run(ir, runsRoot);
        } catch (Exception ex) {
          row.put("pass", false);
          row.put("error", ex.toString());
          row.put("elapsed_ms", System.currentTimeMillis() - t0);
          failed++;
          results.add(row);
          System.out.println("SMOKE FAIL " + ir.query_id + " exception=" + ex.getMessage());
          continue;
        }
        long elapsed = System.currentTimeMillis() - t0;

        int scanRanges = 0;
        String planId = null;
        if (rr.selected != null) {
          planId = rr.selected.plan().plan_id;
          if (rr.selected.physicalPlan() != null
              && rr.selected.physicalPlan().scanTasks != null) {
            scanRanges = rr.selected.physicalPlan().scanTasks.size();
          }
        }
        long candidateChunks = 0;
        if (rr.result != null && rr.result.trace != null) {
          candidateChunks = rr.result.trace.candidateChunks;
          if (rr.result.trace.elapsedMs > 0) {
            elapsed = rr.result.trace.elapsedMs;
          }
        }

        row.put("selected_plan", planId);
        row.put("candidate_chunks", candidateChunks);
        row.put("scan_ranges", scanRanges);
        row.put("elapsed_ms", elapsed);
        row.put("oracle_count", expected.trajectoryIds.size());
        row.put("result_count",
            rr.result == null || rr.result.trajectoryIds == null
                ? 0 : rr.result.trajectoryIds.size());

        String mismatch = compare(expected, rr.result);
        boolean ok = mismatch == null && rr.result != null && "OK".equals(rr.result.status);
        row.put("pass", ok);
        if (!ok) {
          row.put("error", mismatch != null ? mismatch
              : (rr.result == null ? "null result" : rr.result.status + ": " + rr.result.error));
          failed++;
          System.out.println("SMOKE FAIL " + ir.query_id
              + " plan=" + planId
              + " err=" + row.get("error"));
        } else {
          passed++;
          System.out.println("SMOKE PASS " + ir.query_id
              + " plan=" + planId
              + " candidates=" + candidateChunks
              + " scans=" + scanRanges
              + " elapsed_ms=" + elapsed
              + " count=" + expected.trajectoryIds.size());
        }
        results.add(row);
      }
    } finally {
      kv.closeConnection();
    }

    Map<String, Object> report = new LinkedHashMap<String, Object>();
    report.put("manifest_id", manifestId);
    report.put("passed", passed);
    report.put("failed", failed);
    report.put("total", queries.size());
    report.put("results", results);

    Path rOut = reportPath.isAbsolute() ? reportPath : root.resolve(reportPath);
    Files.createDirectories(rOut.getParent());
    Files.write(rOut, MAPPER.writeValueAsString(report).getBytes(StandardCharsets.UTF_8));

    System.out.println("SMOKE_SUMMARY passed=" + passed + " failed=" + failed
        + " total=" + queries.size() + " report=" + rOut);
    return failed == 0 ? 0 : 1;
  }

  private static String compare(OracleAnswer expected, QueryResult actual) {
    if (actual == null) {
      return "null QueryResult";
    }
    if (!"OK".equals(actual.status)) {
      return "status=" + actual.status + " error=" + actual.error;
    }
    if (actual.trajectoryIds == null) {
      return "null trajectoryIds";
    }
    if (expected.trajectoryIds.size() != actual.trajectoryIds.size()) {
      return "size mismatch oracle=" + expected.trajectoryIds.size()
          + " actual=" + actual.trajectoryIds.size();
    }
    for (int i = 0; i < expected.trajectoryIds.size(); i++) {
      if (!expected.trajectoryIds.get(i).equals(actual.trajectoryIds.get(i))) {
        return "id mismatch at " + i + " oracle=" + expected.trajectoryIds.get(i)
            + " actual=" + actual.trajectoryIds.get(i);
      }
    }
    if (expected.topK != null) {
      List<QueryResult.Scored> actualTop =
          actual.topK != null ? actual.topK : java.util.Collections.<QueryResult.Scored>emptyList();
      if (expected.topK.size() != actualTop.size()) {
        return "topK size mismatch oracle=" + expected.topK.size()
            + " actual=" + actualTop.size();
      }
      for (int i = 0; i < expected.topK.size(); i++) {
        OracleScored e = expected.topK.get(i);
        QueryResult.Scored a = actualTop.get(i);
        if (!e.trajectoryId.equals(a.trajectoryId)) {
          return "topK id mismatch at " + i;
        }
        if (Math.abs(e.distance - a.distance) > DIST_EPS
            && Math.abs(e.distance - a.distance)
            > DIST_EPS * Math.max(1.0, Math.abs(e.distance))) {
          return "topK distance mismatch at " + i + " oracle=" + e.distance
              + " actual=" + a.distance;
        }
      }
    }
    return null;
  }

  static final class OracleAnswer {
    String queryId;
    List<String> trajectoryIds;
    List<OracleScored> topK;
  }

  static final class OracleScored {
    long tid;
    String trajectoryId;
    double distance;
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
