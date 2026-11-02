package kart.cli;

import kart.catalog.CatalogStore;
import kart.catalog.Manifest;
import kart.catalog.StatsSnapshot;
import kart.data.Trajectory;
import kart.geo.Projection;
import kart.geo.Rect;
import kart.snapshot.IndexBuilders;
import kart.snapshot.StatsBuilder;
import kart.snapshot.TDriveLoader;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * Rebuild stats + fill build_report for an existing READY (or BUILDING) snapshot
 * without rewriting HBase tables or re-running posting verify.
 */
@Command(name = "build-stats", description = "Build StatsSnapshot and fill build_report for a snapshot")
public final class BuildStatsCmd implements Callable<Integer> {

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

  @Option(names = "--promote-ready",
      description = "If status is BUILDING, transition to READY after stats are written")
  private boolean promoteReady;

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
      return 1;
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
      if (m.layout.tables.get("raw") != null) layout.tableRaw = m.layout.tables.get("raw");
      if (m.layout.tables.get("meta") != null) layout.tableMeta = m.layout.tables.get("meta");
      if (m.layout.tables.get("time") != null) layout.tableTime = m.layout.tables.get("time");
      if (m.layout.tables.get("zorder") != null) layout.tableZorder = m.layout.tables.get("zorder");
      if (m.layout.tables.get("hash") != null) layout.tableHash = m.layout.tables.get("hash");
      if (m.layout.tables.get("xz") != null) layout.tableXz = m.layout.tables.get("xz");
      if (m.layout.tables.get("tshape") != null) layout.tableTshape = m.layout.tables.get("tshape");
      if (m.layout.tables.get("xzt") != null) layout.tableXzt = m.layout.tables.get("xzt");
    }

    String fmt = format == null ? "tdrive" : format.trim().toLowerCase();
    String useCrs = crs;
    if (useCrs == null || useCrs.isEmpty()) {
      useCrs = m.layout != null && m.layout.crs != null && !m.layout.crs.isEmpty()
          ? m.layout.crs
          : Projection.DEFAULT_CRS;
    }

    Path data = dataDir.isAbsolute() ? dataDir : root.resolve(dataDir);
    System.out.println("STATS_PHASE=load_cleaned data=" + data + " format=" + fmt + " crs=" + useCrs);
    System.out.flush();
    long t0 = System.currentTimeMillis();

    List<Trajectory> trajs = TDriveLoader.loadCleaned(data, layout.domain, fmt, useCrs);
    if (layout.xztHorizonMs <= layout.epochMs) {
      long dataMaxMs = layout.epochMs;
      for (Trajectory t : trajs) {
        if (t.size() > 0) {
          dataMaxMs = Math.max(dataMaxMs, t.points().get(t.size() - 1).timestampMs);
        }
      }
      layout.xztHorizonMs = dataMaxMs + 7L * 24L * 3600L * 1000L;
      m.layout.xzt_horizon_ms = layout.xztHorizonMs;
    }
    long points = 0;
    for (Trajectory t : trajs) {
      points += t.size();
    }
    System.out.println("STATS_PROGRESS phase=load_cleaned trajectories=" + trajs.size()
        + " points=" + points
        + " elapsed_s=" + ((System.currentTimeMillis() - t0) / 1000));
    System.out.flush();

    StatsSnapshot stats = new StatsBuilder(layout).build(manifestId, trajs);
    store.saveStats(stats);
    System.out.println("STATS_PHASE=saved path=" + store.dir().resolve(manifestId + ".stats.json")
        + " samples=" + stats.sample_chunks.size()
        + " time_buckets=" + stats.time_bucket_posting_counts.size()
        + " z_cells=" + stats.zorder_cell_posting_counts.size());
    System.out.flush();

    if (m.build_report == null) {
      m.build_report = new Manifest.BuildReport();
    }
    m.build_report.trajectories = trajs.size();
    m.build_report.points = points;
    m.build_report.note = "stats+build_report filled by build-stats; posting verify already OK";
    m.stats_version = "stats_v1";
    if (promoteReady && m.status == Manifest.Status.BUILDING) {
      m.transitionToReady();
      System.out.println("STATS_PHASE=promoted status=READY");
    }
    store.saveManifest(m);

    System.out.println("build-stats OK for " + manifestId
        + " status=" + m.status
        + " trajectories=" + trajs.size()
        + " points=" + points
        + " elapsed_s=" + ((System.currentTimeMillis() - t0) / 1000));
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
