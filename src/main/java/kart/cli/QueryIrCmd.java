package kart.cli;

import kart.catalog.CatalogStore;
import kart.catalog.Manifest;
import kart.catalog.StatsSnapshot;
import kart.compile.LayoutContext;
import kart.config.AppConfig;
import kart.cost.AdvFinalCalibrator;
import kart.cost.PlanSelector;
import kart.exec.ExecLimits;
import kart.exec.HBaseBackend;
import kart.exec.KvBackend;
import kart.ir.BoundIr;
import kart.llm.LlmClient;
import kart.llm.OpenAiCompatibleClient;
import kart.query.QueryEngine;
import kart.search.PlannerMode;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

@Command(name = "query-ir", description = "Execute a BoundIR JSON query on HBase")
public final class QueryIrCmd implements Callable<Integer> {

  @Option(names = "--ir", required = true, description = "BoundIR JSON file")
  private Path irPath;

  @Option(names = "--manifest", description = "Manifest id (default: from IR snapshot)")
  private String manifestId;

  @Option(names = "--catalog", defaultValue = "catalog", description = "Catalog directory")
  private Path catalogDir;

  @Option(names = "--config-root", description = "Project root (default: -Dkart.root or cwd)")
  private Path configRoot;

  @Option(names = "--runs", defaultValue = "runs", description = "Directory for run artifacts")
  private Path runsDir;

  @Option(names = "--max-candidate-chunks", description = "Override planner max_candidate_chunks")
  private Long maxCandidateChunks;

  @Option(names = "--policy",
      description = "Plan policy: rule|best_first|llm|llm_direct (default: rule)")
  private String policy;

  @Option(names = "--plan-only",
      description = "Plan+select only (E2); no Coordinator execute")
  private boolean planOnly;

  @Option(names = "--force-plan",
      description = "Force plan_id among SafePlans (e.g. P_FULL for WorldAccess align)")
  private String forcePlanId;

  @Option(names = "--no-artifacts",
      description = "Skip large run artifacts and print a compact result count/hash")
  private boolean noArtifacts;

  @Option(names = "--trajectory-ids-out",
      description = "Write returned trajectory IDs as a JSON array to a new diagnostic file")
  private Path trajectoryIdsOut;

  @Override
  public Integer call() throws Exception {
    Path root = resolveRoot();
    Path resolvedIr = irPath.isAbsolute() ? irPath : root.resolve(irPath);
    String irJson = new String(Files.readAllBytes(resolvedIr), StandardCharsets.UTF_8);
    kart.ir.IrSchemaValidator schemas = new kart.ir.IrSchemaValidator(root.resolve("schemas"));
    java.util.Set<com.networknt.schema.ValidationMessage> irErrs = schemas.validateBoundIr(irJson);
    if (irErrs != null && !irErrs.isEmpty()) {
      System.err.println("BoundIR schema invalid: " + irErrs.iterator().next().getMessage());
      return 2;
    }
    BoundIr ir = BoundIr.fromJson(irJson);

    String mid = resolveManifestId(ir);
    if (mid == null) {
      System.err.println("manifest id required (--manifest or IR.snapshot.manifest_id)");
      return 2;
    }

    PlannerMode mode;
    try {
      mode = policy != null ? PlannerMode.parse(policy) : PlannerMode.RULE;
    } catch (IllegalArgumentException e) {
      System.err.println(e.getMessage());
      return 2;
    }

    AppConfig cfg = AppConfig.load(root);
    Path runsRoot = runsDir.isAbsolute() ? runsDir : root.resolve(runsDir);
    KvBackend kv = null;
    boolean closeKv = false;
    try {
      Path cat = catalogDir.isAbsolute() ? catalogDir : root.resolve(catalogDir);
      CatalogStore catalog = new CatalogStore(cat);
      Manifest manifest = catalog.loadManifest(mid).orElse(null);
      if (manifest == null) {
        System.err.println("manifest not found: " + mid + " in " + cat);
        System.err.println("Hint: kart run build-snapshot --data datasets/tdrive");
        return 1;
      }
      LayoutContext layout = LayoutContext.from(manifest);
      StatsSnapshot stats = catalog.loadStats(mid).orElse(null);
      Path site = root.resolve("config/hbase/hbase-site.xml");
      kv = new HBaseBackend(HBaseBackend.open(site));
      closeKv = true;

      ExecLimits limits = ExecLimits.defaults();
      limits.maxCandidateChunks = (int) Math.min(Integer.MAX_VALUE,
          cfg.planner().max_candidate_chunks);
      if (maxCandidateChunks != null) {
        limits.maxCandidateChunks = maxCandidateChunks.intValue();
      }
      limits.maxDtwCells = cfg.planner().max_dtw_cells;
      limits.fetchBatch = cfg.planner().fetch_batch_size;

      LlmClient llm = null;
      if (mode == PlannerMode.LLM || mode == PlannerMode.LLM_DIRECT) {
        try {
          llm = new OpenAiCompatibleClient();
        } catch (Exception e) {
          System.err.println("LLM unavailable (" + e.getMessage() + "); falling back to rule");
          mode = PlannerMode.RULE;
        }
      }

      QueryEngine engine = new QueryEngine(kv, layout, limits, stats, cfg.planner(), llm, mode);
      QueryEngine.RunResult rr = engine.run(ir, noArtifacts ? null : runsRoot,
          planOnly, forcePlanId);
      System.out.println("policy=" + mode.wireName()
          + " plan_only=" + planOnly
          + " advanced_indexes_enabled=" + rr.advanced_indexes_enabled
          + (forcePlanId != null && !forcePlanId.trim().isEmpty()
              ? " force_plan=" + forcePlanId.trim() : "")
          + " t_plan_ms=" + rr.t_plan_ms
          + " t_exec_ms=" + rr.t_exec_ms
          + " regret_ms=" + rr.plan_regret_ms);
      if (noArtifacts) {
        System.out.println("planning_stages search_ms=" + rr.t_search_ms
            + " safety_validation_ms=" + rr.t_validation_ms
            + " final_cost_ms=" + rr.t_final_cost_ms);
      }
      if (rr.selected != null) {
        System.out.println("selected_plan=" + rr.selected.plan().plan_id
            + " estimated_ms=" + (rr.selectedCost != null ? rr.selectedCost.estimated_ms : "?")
            + " safe=" + rr.safe.size() + "/" + rr.candidates.size());
        if (noArtifacts && rr.selectedCost != null) {
          printCompactCostFeatures(rr.selectedCost.features);
          System.out.println("selected_exec_proxy_ms="
              + AdvFinalCalibrator.predMs(new PlanSelector.Scored(rr.selected, rr.selectedCost)));
        }
      } else {
        System.out.println("selected_plan=none safe=0/" + rr.candidates.size());
      }
      System.out.println("status=" + rr.result.status);
      if (noArtifacts && rr.result.trace != null) {
        printCompactExecutionTrace(rr.result.trace);
      }
      if (!planOnly) {
        if (noArtifacts) {
          List<String> ids = rr.result.trajectoryIds != null
              ? rr.result.trajectoryIds : Collections.<String>emptyList();
          System.out.println("trajectory_count=" + ids.size()
              + " trajectory_ids_sha256=" + trajectoryIdsSha256(ids));
        } else {
          System.out.println("trajectory_ids=" + rr.result.trajectoryIds);
        }
        if (trajectoryIdsOut != null && "OK".equals(rr.result.status)) {
          Path resolvedIdsOut = trajectoryIdsOut.isAbsolute()
              ? trajectoryIdsOut : root.resolve(trajectoryIdsOut);
          writeTrajectoryIds(resolvedIdsOut, rr.result.trajectoryIds);
          System.out.println("trajectory_ids_out=" + resolvedIdsOut.toAbsolutePath().normalize());
        }
      }
      if (rr.runDir != null) {
        System.out.println("run_dir=" + rr.runDir);
      }
      if ("PLAN_ONLY".equals(rr.result.status)) {
        return 0;
      }
      if (!"OK".equals(rr.result.status)) {
        if (rr.result.error != null) {
          System.err.println("error: " + rr.result.error);
        }
        return 1;
      }
      return 0;
    } finally {
      if (closeKv && kv != null) {
        kv.close();
      }
    }
  }

  private String resolveManifestId(BoundIr ir) {
    if (manifestId != null) {
      return manifestId;
    }
    if (ir.snapshot != null) {
      return ir.snapshot.manifest_id;
    }
    return null;
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

  private static void printCompactCostFeatures(Map<String, Object> features) {
    String[] keys = new String[] {
        "scan_ranges", "estimated_index_rows", "estimated_index_rows_after_code_filter",
        "est_postings_precise", "est_postings_merged", "estimated_candidate_chunks",
        "time_index_rows", "z_index_rows", "hash_index_rows",
        "xz_scan_range_count", "xz_estimated_postings_scanned",
        "tshape_scan_range_count", "tshape_estimated_postings_scanned",
        "xzt_scan_range_count", "xzt_estimated_postings_scanned",
        "xz_gap_merge_source", "tshape_gap_merge_source", "xzt_gap_merge_source"
    };
    StringBuilder out = new StringBuilder("selected_cost_features=");
    boolean first = true;
    for (String key : keys) {
      Object value = features != null ? features.get(key) : null;
      if (value == null) {
        continue;
      }
      if (!first) {
        out.append(',');
      }
      out.append(key).append(':').append(value);
      first = false;
    }
    System.out.println(out.toString());
  }

  private static String trajectoryIdsSha256(List<String> ids) {
    try {
      List<String> sorted = new ArrayList<String>(ids);
      Collections.sort(sorted, Comparator.nullsFirst(String::compareTo));
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      for (String id : sorted) {
        digest.update((id == null ? "<null>" : id).getBytes(StandardCharsets.UTF_8));
        digest.update((byte) '\n');
      }
      StringBuilder hex = new StringBuilder(64);
      for (byte b : digest.digest()) {
        hex.append(String.format("%02x", Integer.valueOf(b & 0xff)));
      }
      return hex.toString();
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }

  private static void writeTrajectoryIds(Path out, List<String> ids) throws Exception {
    Path parent = out.toAbsolutePath().normalize().getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
    byte[] json = new com.fasterxml.jackson.databind.ObjectMapper()
        .writeValueAsBytes(ids != null ? ids : Collections.<String>emptyList());
    Files.write(out, json, java.nio.file.StandardOpenOption.CREATE_NEW,
        java.nio.file.StandardOpenOption.WRITE);
  }

  private static void printCompactExecutionTrace(kart.exec.ExecutionTrace trace) {
    long emittedRanges = 0L;
    if (trace.nodes != null) {
      for (kart.exec.ExecutionTrace.NodeTrace node : trace.nodes.values()) {
        if (node != null && node.emitted_ranges != null) {
          emittedRanges += node.emitted_ranges.longValue();
        }
      }
    }
    System.out.println("execution_summary emitted_ranges=" + emittedRanges
        + " client_ops=" + trace.client_ops_count
        + " postings_raw=" + trace.postings_raw
        + " postings_after_code_filter=" + trace.postings_after_code_filter
        + " candidates=" + trace.candidate_chunks
        + " fetched_chunks=" + trace.fetched_chunks
        + " matched_chunks=" + trace.matched_chunks
        + " filter_passed=" + trace.filter_passed
        + " matched_trajectories=" + trace.matched_trajectories
        + " total_rows=" + trace.totalRows
        + " total_bytes=" + trace.totalBytes);
    System.out.println("execution_phases index_ms=" + trace.phase_index_ms
        + " set_ms=" + trace.phase_set_ms
        + " fetch_ms=" + trace.phase_fetch_ms
        + " exact_ms=" + trace.phase_exact_ms
        + " reconstruct_ms=" + trace.phase_reconstruct_ms
        + " similarity_ms=" + trace.phase_sim_ms
        + " topk_ms=" + trace.phase_topk_ms);
  }
}
