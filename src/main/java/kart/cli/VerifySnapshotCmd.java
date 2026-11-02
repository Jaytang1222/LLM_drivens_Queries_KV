package kart.cli;

import kart.catalog.CatalogStore;
import kart.catalog.Manifest;
import kart.data.Trajectory;
import kart.exec.HBaseBackend;
import kart.exec.KvBackend;
import kart.geo.Projection;
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

  @Option(names = "--format", defaultValue = "tdrive",
      description = "Input format: tdrive|ais|porto")
  private String format;

  @Option(names = "--crs",
      description = "Projected CRS (default: manifest.layout.crs or EPSG:32650)")
  private String crs;

  @Option(names = "--checkpoint",
      description = "Resume file for batched missing-key verify (default: catalog/<id>.verify.ckpt.json when --batched)")
  private Path checkpoint;

  @Option(names = "--batched",
      description = "Batched resume verify without holding all expected keys (required for Porto-scale)")
  private boolean batched;

  @Option(names = "--batch-size", defaultValue = "2000")
  private int batchSize;

  @Option(names = "--no-resume", description = "Ignore existing checkpoint and start from 0")
  private boolean noResume;

  @Option(names = "--check-extra",
      description = "Also scan for extra keys (memory-heavy; only for small fixtures)")
  private boolean checkExtra;

  @Override
  public Integer call() throws Exception {
    Path root = resolveRoot();
    Path cat = catalogDir.isAbsolute() ? catalogDir : root.resolve(catalogDir);
    CatalogStore store = new CatalogStore(cat);
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
    layout.xzLevel = m.layout.xz_level > 0 ? m.layout.xz_level : 12;
    layout.tshapeMaxDepth = m.layout.tshape_max_depth > 0 ? m.layout.tshape_max_depth : 12;
    layout.xztLevel = m.layout.xzt_level > 0 ? m.layout.xzt_level : 12;
    layout.xztHorizonMs = m.layout.xzt_horizon_ms;
    layout.domain = new Rect(m.layout.domain.xmin, m.layout.domain.ymin,
        m.layout.domain.xmax, m.layout.domain.ymax);
    if (m.layout.tables != null) {
      if (m.layout.tables.containsKey("raw")) layout.tableRaw = m.layout.tables.get("raw");
      if (m.layout.tables.containsKey("meta")) layout.tableMeta = m.layout.tables.get("meta");
      if (m.layout.tables.containsKey("time")) layout.tableTime = m.layout.tables.get("time");
      if (m.layout.tables.containsKey("zorder")) layout.tableZorder = m.layout.tables.get("zorder");
      if (m.layout.tables.containsKey("hash")) layout.tableHash = m.layout.tables.get("hash");
      if (m.layout.tables.containsKey("xz")) layout.tableXz = m.layout.tables.get("xz");
      if (m.layout.tables.containsKey("tshape")) layout.tableTshape = m.layout.tables.get("tshape");
      if (m.layout.tables.containsKey("xzt")) layout.tableXzt = m.layout.tables.get("xzt");
    }

    String fmt = format == null ? "tdrive" : format.trim().toLowerCase();
    String useCrs = crs;
    if (useCrs == null || useCrs.isEmpty()) {
      useCrs = m.layout != null && m.layout.crs != null && !m.layout.crs.isEmpty()
          ? m.layout.crs
          : Projection.DEFAULT_CRS;
    }

    Path data = dataDir.isAbsolute() ? dataDir : root.resolve(dataDir);
    System.out.println("VERIFY_PHASE=load_cleaned data=" + data + " format=" + fmt + " crs=" + useCrs);
    System.out.flush();
    long loadT0 = System.currentTimeMillis();
    List<Trajectory> trajs = TDriveLoader.loadCleaned(data, layout.domain, fmt, useCrs);
    System.out.println("VERIFY_PROGRESS phase=load_cleaned trajectories=" + trajs.size()
        + " elapsed_s=" + ((System.currentTimeMillis() - loadT0) / 1000));
    System.out.flush();
    // BUILDING manifests may still have xzt_horizon_ms=0 (saved before write).
    // Match SnapshotBuilder: data span + 7d, otherwise XZT codes will not match HBase.
    if (layout.xztHorizonMs <= layout.epochMs) {
      long dataMaxMs = layout.epochMs;
      for (Trajectory t : trajs) {
        if (t.size() > 0) {
          dataMaxMs = Math.max(dataMaxMs, t.points().get(t.size() - 1).timestampMs);
        }
      }
      layout.xztHorizonMs = dataMaxMs + 7L * 24L * 3600L * 1000L;
      System.out.println("VERIFY_XZT_HORIZON recomputed=" + layout.xztHorizonMs
          + " (manifest had " + m.layout.xzt_horizon_ms + ")");
      System.out.flush();
      m.layout.xzt_horizon_ms = layout.xztHorizonMs;
      store.saveManifest(m);
    }

    boolean useBatch = batched || trajs.size() > 20_000 || checkpoint != null;
    PostingVerifier.Options vOpt;
    if (useBatch) {
      Path ck = checkpoint != null
          ? (checkpoint.isAbsolute() ? checkpoint : root.resolve(checkpoint))
          : cat.resolve(manifestId + ".verify.ckpt.json");
      vOpt = PostingVerifier.Options.checkpointed(ck);
      vOpt.batchSize = Math.max(1, batchSize);
      vOpt.resume = !noResume;
      vOpt.checkExtra = checkExtra;
      System.out.println("VERIFY_MODE=checkpoint file=" + ck + " batch=" + vOpt.batchSize
          + " resume=" + vOpt.resume + " check_extra=" + vOpt.checkExtra);
    } else {
      vOpt = PostingVerifier.Options.memoryFull();
      // memoryFull already enables extra scan; --check-extra is for batched mode.
      System.out.println("VERIFY_MODE=memory check_extra=" + vOpt.checkExtra);
    }
    System.out.flush();

    HBaseBackend hbase = new HBaseBackend(
        HBaseBackend.open(root.resolve("src/main/resources/config/hbase/hbase-site.xml")));
    KvBackend kv = hbase;

    try {
      PostingVerifier.Report report = new PostingVerifier(layout).verify(kv, trajs, vOpt);
      if (report.ok()) {
        System.out.println("verify-snapshot OK for " + manifestId
            + " trajectories=" + trajs.size()
            + (useBatch ? " (missing-keys; extra scan skipped unless --check-extra)" : ""));
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
