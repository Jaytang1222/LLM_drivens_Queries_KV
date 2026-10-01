package kart.cli;

import kart.bench.OpportunityCensus;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.Callable;

@Command(name = "opportunity-census",
    description = "CBO opportunity census (development diagnostic; no LLM)")
public final class OpportunityCensusCmd implements Callable<Integer> {

  @Option(names = "--pool", required = true,
      description = "Primary BoundIR workload JSON (e.g. bound_ir_advantage_v2.json)")
  private Path pool;

  @Option(names = "--extra-pool",
      description = "Optional candidates JSON (pilot candidates)")
  private Path extraPool;

  @Option(names = "--exclude",
      description = "Exclude workload by BoundIR fingerprint (holdout)")
  private Path exclude;

  @Option(names = "--oracle", split = ",",
      description = "Extra oracle JSON paths (comma-separated); defaults also load adv2+pilot")
  private Path[] oracles;

  @Option(names = "--run-id", description = "Output id under experiments/opportunity/")
  private String runId;

  @Option(names = "--cache", description = "cold|warm (default warm)")
  private String cache = "warm";

  @Option(names = "--initial-trials", description = "Coarse timed trials per plan (default 1)")
  private int initialTrials = 1;

  @Option(names = "--repeat-trials", description = "Repeat trials for promising plans (default 3)")
  private int repeatTrials = 3;

  @Option(names = "--max-exec-ms", description = "Soft per-exec wall budget (default 60000)")
  private long maxExecMs = 60_000L;

  @Option(names = "--max-wall-minutes", description = "Global wall budget (default 180)")
  private long maxWallMinutes = 180L;

  @Option(names = "--hybrid-extra-plan-ms",
      description = "Provisional hybrid extra plan cost for labeling (default 900)")
  private long hybridExtraPlanMs = 900L;

  @Option(names = "--repeat-gain-threshold-ms",
      description = "Coarse saving threshold to enter repeats (default 500)")
  private long repeatGainThresholdMs = 500L;

  @Option(names = "--manifest", description = "Manifest id (default tdrive_v1_ready)")
  private String manifest = "tdrive_v1_ready";

  @Option(names = "--limit", description = "Optional query limit after dedupe")
  private Integer limit;

  @Option(names = "--search-only", description = "Phase 1 only (no execution)")
  private boolean searchOnly;

  @Option(names = "--exec-only",
      description = "Phase 2 only (still re-searches lightly to rebuild SafePlans)")
  private boolean execOnly;

  @Option(names = "--include-fullscan-exec",
      description = "Also time P_FULL when CBO did not select it (expensive)")
  private boolean includeFullscanExec;

  @Option(names = "--config-root", description = "Project root")
  private Path configRoot;

  @Override
  public Integer call() throws Exception {
    OpportunityCensus.Options opt = new OpportunityCensus.Options();
    opt.root = resolveRoot();
    opt.pool = pool;
    opt.extraPool = extraPool;
    opt.exclude = exclude;
    if (oracles != null) {
      for (Path p : oracles) {
        if (p != null) {
          opt.oraclePaths.add(p);
        }
      }
    }
    opt.runId = (runId == null || runId.trim().isEmpty())
        ? "opportunity-" + new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date())
        : runId.trim();
    opt.cache = cache == null ? "warm" : cache.trim().toLowerCase();
    if (!"cold".equals(opt.cache) && !"warm".equals(opt.cache)) {
      throw new IllegalArgumentException("--cache must be cold or warm");
    }
    opt.initialTrials = Math.max(1, initialTrials);
    opt.repeatTrials = Math.max(1, repeatTrials);
    opt.maxExecMs = Math.max(1000L, maxExecMs);
    opt.maxWallMinutes = Math.max(1L, maxWallMinutes);
    opt.hybridExtraPlanMs = Math.max(0L, hybridExtraPlanMs);
    opt.repeatGainThresholdMs = Math.max(0L, repeatGainThresholdMs);
    opt.manifestId = manifest;
    opt.limit = limit;
    opt.includeFullscanExec = includeFullscanExec;
    if (searchOnly && execOnly) {
      throw new IllegalArgumentException("choose at most one of --search-only / --exec-only");
    }
    if (searchOnly) {
      opt.phaseSearch = true;
      opt.phaseExec = false;
    } else if (execOnly) {
      opt.phaseSearch = true; // need SafePlans
      opt.phaseExec = true;
    } else {
      opt.phaseSearch = true;
      opt.phaseExec = true;
    }

    System.out.println("OPPORTUNITY_CENSUS run_id=" + opt.runId
        + " cache=" + opt.cache
        + " max_wall_minutes=" + opt.maxWallMinutes
        + " development_diagnostic=true");
    OpportunityCensus.Result r = new OpportunityCensus().run(opt);
    System.out.println("OPPORTUNITY_CENSUS done dir=" + r.outDir
        + " queries=" + r.queries
        + " search_rows=" + r.searchRows
        + " meas_rows=" + r.measurementRows);
    return Integer.valueOf(r.exitCode);
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
