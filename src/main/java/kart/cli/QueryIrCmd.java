package kart.cli;

import kart.catalog.CatalogStore;
import kart.catalog.Manifest;
import kart.catalog.StatsSnapshot;
import kart.compile.LayoutContext;
import kart.config.AppConfig;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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
      QueryEngine.RunResult rr = engine.run(ir, runsRoot, planOnly);
      System.out.println("policy=" + mode.wireName()
          + " plan_only=" + planOnly
          + " t_plan_ms=" + rr.t_plan_ms
          + " t_exec_ms=" + rr.t_exec_ms
          + " regret_ms=" + rr.plan_regret_ms);
      if (rr.selected != null) {
        System.out.println("selected_plan=" + rr.selected.plan().plan_id
            + " estimated_ms=" + (rr.selectedCost != null ? rr.selectedCost.estimated_ms : "?")
            + " safe=" + rr.safe.size() + "/" + rr.candidates.size());
      } else {
        System.out.println("selected_plan=none safe=0/" + rr.candidates.size());
      }
      System.out.println("status=" + rr.result.status);
      if (!planOnly) {
        System.out.println("trajectory_ids=" + rr.result.trajectoryIds);
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
}
