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

  @Override
  public Integer call() throws Exception {
    Path root = resolveRoot();
    SnapshotBuilder.Options opt = new SnapshotBuilder.Options();
    opt.manifestId = manifestId;
    opt.dataDir = dataDir.isAbsolute() ? dataDir : root.resolve(dataDir);
    opt.catalogDir = catalogDir.isAbsolute() ? catalogDir : root.resolve(catalogDir);
    opt.hbaseSiteXml = root.resolve("config/hbase/hbase-site.xml");
    opt.epochMs = epochMs;
    if (xmin != null && xmax != null && ymin != null && ymax != null) {
      opt.domain = new Rect(xmin, ymin, xmax, ymax);
    }

    System.out.println("Building snapshot " + manifestId + " from " + opt.dataDir + " [HBase]");
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
