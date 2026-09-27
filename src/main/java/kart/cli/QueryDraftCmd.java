package kart.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import kart.catalog.CatalogStore;
import kart.catalog.Manifest;
import kart.catalog.StatsSnapshot;
import kart.compile.LayoutContext;
import kart.config.AppConfig;
import kart.exec.ExecLimits;
import kart.exec.HBaseBackend;
import kart.exec.KvBackend;
import kart.ir.DraftIr;
import kart.ir.IrBinder;
import kart.ir.IrSchemaValidator;
import kart.query.QueryEngine;
import kart.search.PlannerMode;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Bind DraftIR then execute (read-only) for SAG WorldAccess probes.
 * Prints one JSON object on stdout for machine consumers.
 */
@Command(name = "query-draft",
    description = "Bind DraftIR and execute on HBase (WorldAccess / alignment)")
public final class QueryDraftCmd implements Callable<Integer> {

  private static final ObjectMapper MAPPER = new ObjectMapper()
      .disable(SerializationFeature.INDENT_OUTPUT);

  @Option(names = "--draft", required = true, description = "DraftIR JSON file")
  private Path draftPath;

  @Option(names = "--manifest", defaultValue = "tdrive_v1_ready",
      description = "Manifest id")
  private String manifestId;

  @Option(names = "--catalog", defaultValue = "catalog", description = "Catalog directory")
  private Path catalogDir;

  @Option(names = "--config-root", description = "Project root")
  private Path configRoot;

  @Option(names = "--runs", defaultValue = "runs", description = "Run artifacts dir")
  private Path runsDir;

  @Option(names = "--force-plan", defaultValue = "P_FULL",
      description = "Force SafePlan id for deterministic Oracle-aligned probes")
  private String forcePlanId;

  @Override
  public Integer call() throws Exception {
    Path root = resolveRoot();
    Path resolved = draftPath.isAbsolute() ? draftPath : root.resolve(draftPath);
    String draftJson = new String(Files.readAllBytes(resolved), StandardCharsets.UTF_8);
    IrSchemaValidator schemas = new IrSchemaValidator(root.resolve("schemas"));
    java.util.Set<com.networknt.schema.ValidationMessage> errs = schemas.validateDraftIr(draftJson);
    if (errs != null && !errs.isEmpty()) {
      emitError("draft_schema_invalid", errs.iterator().next().getMessage());
      return 2;
    }
    DraftIr draft = DraftIr.fromJson(draftJson);

    Path cat = catalogDir.isAbsolute() ? catalogDir : root.resolve(catalogDir);
    CatalogStore catalog = new CatalogStore(cat);
    Manifest manifest = catalog.loadManifest(manifestId).orElse(null);
    if (manifest == null) {
      emitError("manifest_missing", manifestId);
      return 1;
    }

    AppConfig cfg = AppConfig.load(root);
    Path runsRoot = runsDir.isAbsolute() ? runsDir : root.resolve(runsDir);
    KvBackend kv = null;
    boolean closeKv = false;
    try {
      LayoutContext layout = LayoutContext.from(manifest);
      StatsSnapshot stats = catalog.loadStats(manifestId).orElse(null);
      Path site = root.resolve("config/hbase/hbase-site.xml");
      kv = new HBaseBackend(HBaseBackend.open(site));
      closeKv = true;

      IrBinder binder = new IrBinder(
          cfg.regions(), kv, layout.tableMeta, layout.shardCount,
          manifestId,
          manifest.semantics_version != null ? manifest.semantics_version : "point_similarity_v2");
      IrBinder.BindResult br = binder.bind(draft, System.currentTimeMillis());
      if (!IrBinder.STATUS_OK.equals(br.status) || br.bound == null) {
        emitError(br.status != null ? br.status : "bind_failed", br.error);
        return 2;
      }

      ExecLimits limits = ExecLimits.defaults();
      limits.maxCandidateChunks = (int) Math.min(Integer.MAX_VALUE,
          cfg.planner().max_candidate_chunks);
      limits.maxDtwCells = cfg.planner().max_dtw_cells;
      limits.fetchBatch = cfg.planner().fetch_batch_size;

      QueryEngine engine = new QueryEngine(kv, layout, limits, stats, cfg.planner(),
          null, PlannerMode.RULE);
      Path art = runsRoot.resolve("world-probe");
      Files.createDirectories(art);
      QueryEngine.RunResult rr = engine.run(br.bound, art, false, forcePlanId);

      Map<String, Object> out = new LinkedHashMap<String, Object>();
      out.put("ok", Boolean.valueOf(rr != null && rr.result != null
          && "OK".equals(rr.result.status)));
      out.put("backend", "hbase_query_draft");
      out.put("manifest_id", manifestId);
      out.put("force_plan_id", forcePlanId);
      out.put("bind_status", br.status);
      if (rr != null && rr.result != null) {
        out.put("status", rr.result.status);
        out.put("trajectory_ids", rr.result.trajectoryIds);
        out.put("error", rr.result.error);
        out.put("t_exec_ms", rr.t_exec_ms);
        out.put("t_plan_ms", rr.t_plan_ms);
      } else {
        out.put("trajectory_ids", java.util.Collections.emptyList());
        out.put("error", "null_run_result");
      }
      out.put("note", "hbase_world_probe_force_" + forcePlanId);
      System.out.println(MAPPER.writeValueAsString(out));
      if (rr == null || rr.result == null || !"OK".equals(rr.result.status)) {
        return 1;
      }
      return 0;
    } finally {
      if (closeKv && kv != null) {
        kv.close();
      }
    }
  }

  private void emitError(String code, String detail) throws Exception {
    Map<String, Object> out = new LinkedHashMap<String, Object>();
    out.put("ok", Boolean.FALSE);
    out.put("backend", null);
    out.put("error", code);
    out.put("message", detail);
    out.put("trajectory_ids", java.util.Collections.emptyList());
    System.out.println(MAPPER.writeValueAsString(out));
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
