package kart.cli;

import kart.geo.Rect;
import kart.snapshot.SnapshotBuilder;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.Callable;

@Command(name = "build-snapshot", description = "Build T-Drive snapshot on HBase: parse, index, verify, READY")
public final class BuildSnapshotCmd implements Callable<Integer> {

  @Option(names = "--data", defaultValue = "datasets/tdrive")
  private Path dataDir;

  @Option(names = "--manifest-id", defaultValue = "tdrive_v1_ready")
  private String manifestId;

  @Option(names = "--catalog", defaultValue = "catalog")
  private Path catalogDir;

  @Option(names = "--format", defaultValue = "tdrive",
      description = "Input format: tdrive (part-*) | ais (trajectories.txt) | porto (CSV)")
  private String format;

  @Option(names = "--crs", defaultValue = "EPSG:32650",
      description = "Projected CRS (AIS: EPSG:32634; Porto: EPSG:32629)")
  private String crs;

  @Option(names = "--table-prefix",
      description = "HBase table suffix prefix (e.g. ais_v1 / porto_v1 → traj_raw_<prefix>). Empty = legacy v1 names.")
  private String tablePrefix;

  @Option(names = "--xmin")
  private Double xmin;
  @Option(names = "--xmax")
  private Double xmax;
  @Option(names = "--ymin")
  private Double ymin;
  @Option(names = "--ymax")
  private Double ymax;

  @Option(names = "--epoch-ms")
  private Long epochMs;

  @Option(names = "--config-root")
  private Path configRoot;

  @Option(names = "--write-batch-size", defaultValue = "500",
      description = "Flush+checkpoint write progress every N trajectories (HBase resume)")
  private int writeBatchSize;

  @Option(names = "--no-resume",
      description = "Ignore checkpoints, truncate all snapshot HBase tables, rebuild from 0")
  private boolean noResume;

  @Option(names = "--verify-sample-size", defaultValue = "0",
      description = "Sampled posting verify: check this many trajectories (0=full). "
          + "Prior consecutive verify.ckpt done prefix still counts as covered.")
  private int verifySampleSize;

  @Option(names = "--verify-sample-frac", defaultValue = "0",
      description = "If --verify-sample-size=0 and >0, sample size = ceil(n * frac)")
  private double verifySampleFrac;

  @Option(names = "--verify-sample-seed", defaultValue = "20261027",
      description = "RNG seed for sampled verify index set")
  private long verifySampleSeed;

  @Override
  public Integer call() throws Exception {
    Path root = resolveRoot();
    SnapshotBuilder.Options opt = new SnapshotBuilder.Options();
    opt.manifestId = manifestId;
    opt.dataDir = dataDir.isAbsolute() ? dataDir : root.resolve(dataDir);
    opt.catalogDir = catalogDir.isAbsolute() ? catalogDir : root.resolve(catalogDir);
    opt.hbaseSiteXml = root.resolve("src/main/resources/config/hbase/hbase-site.xml");
    opt.epochMs = epochMs;
    opt.format = format;
    opt.crs = crs;
    opt.tablePrefix = tablePrefix;
    opt.writeBatchSize = Math.max(1, writeBatchSize);
    opt.resume = !noResume;
    opt.verifySampleSize = Math.max(0, verifySampleSize);
    opt.verifySampleFrac = Math.max(0, verifySampleFrac);
    opt.verifySampleSeed = verifySampleSeed;
    if (xmin != null && xmax != null && ymin != null && ymax != null) {
      opt.domain = new Rect(xmin, ymin, xmax, ymax);
    }

    System.out.println("Building snapshot " + manifestId + " from " + opt.dataDir
        + " format=" + format + " crs=" + crs
        + " tablePrefix=" + (tablePrefix == null ? "<legacy>" : tablePrefix)
        + " writeBatch=" + opt.writeBatchSize + " resume=" + opt.resume
        + " verifySampleSize=" + opt.verifySampleSize
        + " verifySampleFrac=" + opt.verifySampleFrac
        + " [HBase]");
    SnapshotBuilder.BuildResult r = new SnapshotBuilder().build(opt);
    if (r.failureReason != null) {
      System.err.println("FAILED: " + r.failureReason);
      if (r.verify != null) {
        int n = Math.min(10, r.verify.missing.size());
        for (int i = 0; i < n; i++) {
          System.err.println("  missing: " + r.verify.missing.get(i));
        }
        n = Math.min(10, r.verify.extra.size());
        for (int i = 0; i < n; i++) {
          System.err.println("  extra: " + r.verify.extra.get(i));
        }
      }
      System.err.println("manifest status remains BUILDING");
      return 1;
    }
    System.out.println("READY " + r.manifest.manifest_id
        + " trajectories=" + r.trajectories
        + " points=" + r.points
        + " elapsed_ms=" + r.elapsedMs);
    System.out.println("domain x=[" + r.manifest.layout.domain.xmin + "," + r.manifest.layout.domain.xmax
        + "] y=[" + r.manifest.layout.domain.ymin + "," + r.manifest.layout.domain.ymax + "]");
    System.out.println("epoch_ms=" + r.manifest.layout.epoch_ms);
    return 0;
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
