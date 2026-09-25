package kart.cli;

import kart.catalog.CatalogStore;
import kart.catalog.Manifest;
import kart.data.Trajectory;
import kart.exec.HBaseBackend;
import kart.exec.KvBackend;
import kart.geo.Rect;
import kart.snapshot.IndexBuilders;
import kart.snapshot.PostingVerifier;
import kart.snapshot.TDriveLoader;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;

@Command(name = "verify-snapshot", description = "Re-verify posting completeness for a HBase snapshot")
public final class VerifySnapshotCmd implements Callable<Integer> {

  @Parameters(index = "0", description = "manifest_id")
  private String manifestId;

  @Option(names = "--data", defaultValue = "datasets/tdrive")
  private Path dataDir;

  @Option(names = "--catalog", defaultValue = "catalog")
  private Path catalogDir;

  @Option(names = "--config-root")
  private Path configRoot;

  @Override
  public Integer call() throws Exception {
    Path root = resolveRoot();
    CatalogStore store = new CatalogStore(catalogDir.isAbsolute() ? catalogDir : root.resolve(catalogDir));
    Optional<Manifest> om = store.loadManifest(manifestId);
    if (!om.isPresent()) {
      System.err.println("manifest not found: " + manifestId);
      return 1;
    }
    Manifest m = om.get();
    if (m.status != Manifest.Status.READY && m.status != Manifest.Status.BUILDING) {
      System.err.println("unexpected status: " + m.status);
    }

    IndexBuilders.LayoutParams layout = new IndexBuilders.LayoutParams();
    layout.shardCount = m.layout.shard_count;
    layout.bucketMs = m.layout.bucket_ms;
    layout.epochMs = m.layout.epoch_ms;
    layout.zorderLevel = m.layout.zorder_level;
    layout.domain = new Rect(m.layout.domain.xmin, m.layout.domain.ymin,
        m.layout.domain.xmax, m.layout.domain.ymax);
    if (m.layout.tables != null) {
      if (m.layout.tables.containsKey("raw")) layout.tableRaw = m.layout.tables.get("raw");
      if (m.layout.tables.containsKey("meta")) layout.tableMeta = m.layout.tables.get("meta");
      if (m.layout.tables.containsKey("time")) layout.tableTime = m.layout.tables.get("time");
      if (m.layout.tables.containsKey("zorder")) layout.tableZorder = m.layout.tables.get("zorder");
      if (m.layout.tables.containsKey("hash")) layout.tableHash = m.layout.tables.get("hash");
    }

    Path data = dataDir.isAbsolute() ? dataDir : root.resolve(dataDir);
    System.out.println("VERIFY_PHASE=load_cleaned data=" + data);
    System.out.flush();
    long loadT0 = System.currentTimeMillis();
    List<Trajectory> trajs = TDriveLoader.loadCleaned(data, layout.domain);
    System.out.println("VERIFY_PROGRESS phase=load_cleaned trajectories=" + trajs.size()
        + " elapsed_s=" + ((System.currentTimeMillis() - loadT0) / 1000));
    System.out.flush();

    HBaseBackend hbase = new HBaseBackend(
        HBaseBackend.open(root.resolve("config/hbase/hbase-site.xml")));
    KvBackend kv = hbase;

    try {
      PostingVerifier.Report report = new PostingVerifier(layout).verify(kv, trajs);
      if (report.ok()) {
        System.out.println("verify-snapshot OK for " + manifestId
            + " trajectories=" + trajs.size());
        return 0;
      }
      System.err.println("verify-snapshot FAILED missing=" + report.missing.size()
          + " extra=" + report.extra.size());
      for (int i = 0; i < Math.min(20, report.missing.size()); i++) {
        System.err.println("  missing: " + report.missing.get(i));
      }
      for (int i = 0; i < Math.min(20, report.extra.size()); i++) {
        System.err.println("  extra: " + report.extra.get(i));
      }
      return 1;
    } finally {
      hbase.closeConnection();
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
