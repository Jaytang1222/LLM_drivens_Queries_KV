package kart.cli;

import kart.catalog.CatalogStore;
import kart.catalog.Manifest;
import kart.catalog.StatsSnapshot;
import kart.compile.LayoutContext;
import kart.config.AppConfig;
import kart.exec.ExecLimits;
import kart.exec.HBaseBackend;
import kart.exec.KvBackend;
import kart.exec.MemoryBackend;
import kart.geo.Rect;
import kart.ir.BoundIr;
import kart.query.QueryEngine;
import kart.snapshot.FixtureBuilder;
import kart.snapshot.IndexBuilders;
import kart.snapshot.SnapshotBuilder;
import kart.snapshot.StatsBuilder;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.Callable;

@Command(name = "query-ir", description = "Execute a BoundIR JSON query (P2/P5)")
public final class QueryIrCmd implements Callable<Integer> {

  @Option(names = "--ir", required = true, description = "BoundIR JSON file")
  private Path irPath;

  @Option(names = "--manifest", description = "Manifest id (default: from IR snapshot)")
  private String manifestId;

  @Option(names = "--catalog", defaultValue = "catalog", description = "Catalog directory")
  private Path catalogDir;

  @Option(names = "--memory", description = "Use in-memory fixture snapshot (no HBase)")
  private boolean memory;

  @Option(names = "--config-root", description = "Project root (default: -Dkart.root or cwd)")
  private Path configRoot;

  @Option(names = "--runs", defaultValue = "runs", description = "Directory for run artifacts")
  private Path runsDir;

  @Option(names = "--max-candidate-chunks", description = "Override planner max_candidate_chunks")
  private Long maxCandidateChunks;

  @Override
  public Integer call() throws Exception {
    Path root = resolveRoot();
    BoundIr ir = BoundIr.fromJson(new String(Files.readAllBytes(
        irPath.isAbsolute() ? irPath : root.resolve(irPath)), StandardCharsets.UTF_8));

    String mid = resolveManifestId(ir);
    if (mid == null) {
      System.err.println("manifest id required (--manifest or IR.snapshot.manifest_id)");
      return 2;
    }

    AppConfig cfg = AppConfig.load(root);
    Path runsRoot = runsDir.isAbsolute() ? runsDir : root.resolve(runsDir);
    KvBackend kv = null;
    boolean closeKv = false;
    try {
      LayoutContext layout;
      StatsSnapshot stats = null;
      if (memory) {
        MemoryBackend mem = SnapshotBuilder.buildFixtureInMemory();
        kv = mem;
        closeKv = true;
        layout = LayoutContext.from(FixtureBuilder.fixtureManifest());
        if (ir.snapshot == null) {
          ir.snapshot = new BoundIr.Snapshot();
        }
        if (ir.snapshot.manifest_id == null) {
          ir.snapshot.manifest_id = FixtureBuilder.MANIFEST_ID;
        }
        stats = fixtureStats();
      } else {
        Path cat = catalogDir.isAbsolute() ? catalogDir : root.resolve(catalogDir);
        CatalogStore catalog = new CatalogStore(cat);
        Manifest manifest = catalog.loadManifest(mid).orElse(null);
        if (manifest == null) {
          System.err.println("manifest not found: " + mid + " in " + cat);
          return 1;
        }
        layout = LayoutContext.from(manifest);
        stats = catalog.loadStats(mid).orElse(null);
        Path site = root.resolve("config/hbase/hbase-site.xml");
        kv = new HBaseBackend(HBaseBackend.open(site));
        closeKv = true;
      }

      ExecLimits limits = ExecLimits.defaults();
      limits.maxCandidateChunks = (int) Math.min(Integer.MAX_VALUE,
          cfg.planner().max_candidate_chunks);
      if (maxCandidateChunks != null) {
        limits.maxCandidateChunks = maxCandidateChunks.intValue();
      }
      limits.maxDtwCells = cfg.planner().max_dtw_cells;
      limits.fetchBatch = cfg.planner().fetch_batch_size;

      QueryEngine engine = new QueryEngine(kv, layout, limits, stats, cfg.planner().cost);
      QueryEngine.RunResult rr = engine.run(ir, runsRoot);
      if (rr.selected != null) {
        System.out.println("selected_plan=" + rr.selected.plan().plan_id
            + " estimated_ms=" + (rr.selectedCost != null ? rr.selectedCost.estimated_ms : "?")
            + " safe=" + rr.safe.size() + "/" + rr.candidates.size());
      } else {
        System.out.println("selected_plan=none safe=0/" + rr.candidates.size());
      }
      System.out.println("status=" + rr.result.status);
      System.out.println("trajectory_ids=" + rr.result.trajectoryIds);
      if (rr.runDir != null) {
        System.out.println("run_dir=" + rr.runDir);
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

  private static StatsSnapshot fixtureStats() {
    IndexBuilders.LayoutParams lp = new IndexBuilders.LayoutParams();
    lp.epochMs = FixtureBuilder.T0;
    lp.domain = new Rect(0, 0, 100, 100);
    return new StatsBuilder(lp).build(FixtureBuilder.MANIFEST_ID, FixtureBuilder.trajectories());
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
