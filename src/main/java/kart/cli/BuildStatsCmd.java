package kart.cli;

import kart.catalog.CatalogStore;
import kart.catalog.Manifest;
import kart.catalog.StatsSnapshot;
import kart.data.Cleaner;
import kart.data.RawTrajectory;
import kart.data.TDriveParser;
import kart.data.TidAssigner;
import kart.data.Trajectory;
import kart.geo.Projection;
import kart.geo.Rect;
import kart.snapshot.DataProfiler;
import kart.snapshot.IndexBuilders;
import kart.snapshot.StatsBuilder;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
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
    layout.domain = new Rect(m.layout.domain.xmin, m.layout.domain.ymin,
        m.layout.domain.xmax, m.layout.domain.ymax);

    Path data = dataDir.isAbsolute() ? dataDir : root.resolve(dataDir);
    System.out.println("STATS_PHASE=load_cleaned data=" + data);
    System.out.flush();
    long t0 = System.currentTimeMillis();

    TDriveParser parser = new TDriveParser();
    List<RawTrajectory> raws = new ArrayList<RawTrajectory>();
    long parseReject = 0;
    for (Path part : DataProfiler.listParts(data)) {
      try (BufferedReader br = Files.newBufferedReader(part, StandardCharsets.UTF_8)) {
        String line;
        while ((line = br.readLine()) != null) {
          if (line.trim().isEmpty()) {
            continue;
          }
          try {
            raws.add(parser.parseLine(line));
          } catch (IllegalArgumentException e) {
            parseReject++;
          }
        }
      }
    }
    Cleaner.Result cleaned = new Cleaner(new Projection(), layout.domain).cleanAll(raws);
    List<Trajectory> trajs = TidAssigner.assign(cleaned.trajectories);
    long points = 0;
    for (Trajectory t : trajs) {
      points += t.size();
    }
    System.out.println("STATS_PROGRESS phase=load_cleaned trajectories=" + trajs.size()
        + " points=" + points
        + " parse_reject=" + parseReject
        + " ood=" + cleaned.stats.outOfDomainTrajectories
        + " empty=" + cleaned.stats.emptyAfterClean
        + " dedup=" + cleaned.stats.dedupRemoved
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
    m.build_report.rejected.parse = parseReject;
    m.build_report.rejected.out_of_domain = cleaned.stats.outOfDomainTrajectories;
    m.build_report.rejected.empty = cleaned.stats.emptyAfterClean;
    m.build_report.dedup_removed = cleaned.stats.dedupRemoved;
    m.build_report.note = "stats+build_report filled by build-stats; posting verify already OK";
    m.stats_version = "stats_v1";
    // keep existing READY (or BUILDING — do not auto-promote here)
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
