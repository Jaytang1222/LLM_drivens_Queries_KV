package kart.cli;

import kart.catalog.CatalogStore;
import kart.catalog.Manifest;
import kart.catalog.StatsSnapshot;
import kart.data.Chunk;
import kart.data.Chunker;
import kart.data.Cleaner;
import kart.data.RawTrajectory;
import kart.data.TDriveParser;
import kart.data.TidAssigner;
import kart.data.Trajectory;
import kart.exec.HBaseBackend;
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
 * Truncate + rewrite {@code idx_xzt} with data-span horizon, then refresh stats/manifest.
 */
@Command(name = "rebuild-xzt", description = "Rebuild idx_xzt postings + stats for a READY snapshot")
public final class RebuildXztCmd implements Callable<Integer> {

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

    IndexBuilders.LayoutParams layout = layoutFrom(m);
    Path data = dataDir.isAbsolute() ? dataDir : root.resolve(dataDir);
    System.out.println("REBUILD_XZT phase=load data=" + data);
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
    long dataMaxMs = layout.epochMs;
    for (Trajectory t : trajs) {
      if (t.size() > 0) {
        dataMaxMs = Math.max(dataMaxMs, t.points().get(t.size() - 1).timestampMs);
      }
    }
    layout.xztHorizonMs = dataMaxMs + 7L * 24L * 3600L * 1000L;
    m.layout.xzt_horizon_ms = layout.xztHorizonMs;
    System.out.println("REBUILD_XZT horizon_ms=" + layout.xztHorizonMs
        + " trajs=" + trajs.size() + " parse_reject=" + parseReject);
    System.out.flush();

    Path hbaseSite = root.resolve("config/hbase/hbase-site.xml");
    HBaseBackend hbase = new HBaseBackend(HBaseBackend.open(hbaseSite));
    try {
      if (m.layout.tables != null && m.layout.tables.get("xzt") != null) {
        layout.tableXzt = m.layout.tables.get("xzt");
      }
      System.out.println("REBUILD_XZT truncate " + layout.tableXzt);
      System.out.flush();
      hbase.truncateTable(layout.tableXzt);

      IndexBuilders builders = new IndexBuilders(layout);
      Chunker chunker = new Chunker(256);
      int i = 0;
      for (Trajectory t : trajs) {
        List<Chunk> chunks = chunker.chunk(t);
        builders.rewriteXztPostings(hbase, t, chunks);
        i++;
        if (i == 1 || i % 2000 == 0 || i == trajs.size()) {
          System.out.println("REBUILD_XZT progress=" + i + "/" + trajs.size());
          System.out.flush();
        }
      }

      StatsSnapshot stats = new StatsBuilder(layout).build(manifestId, trajs);
      store.saveStats(stats);
      store.saveManifest(m);
      System.out.println("REBUILD_XZT OK status=" + m.status
          + " xzt_codes=" + stats.xzt_code_posting_counts.size()
          + " samples=" + stats.sample_chunks.size()
          + " elapsed_s=" + ((System.currentTimeMillis() - t0) / 1000));
      return 0;
    } finally {
      hbase.close();
    }
  }

  private static IndexBuilders.LayoutParams layoutFrom(Manifest m) {
    IndexBuilders.LayoutParams layout = new IndexBuilders.LayoutParams();
    layout.shardCount = m.layout.shard_count;
    layout.bucketMs = m.layout.bucket_ms;
    layout.epochMs = m.layout.epoch_ms;
    layout.zorderLevel = m.layout.zorder_level;
    layout.xzLevel = m.layout.xz_level > 0 ? m.layout.xz_level : 12;
    layout.tshapeMaxDepth = m.layout.tshape_max_depth > 0 ? m.layout.tshape_max_depth : 12;
    layout.xztLevel = m.layout.xzt_level > 0 ? m.layout.xzt_level : 12;
    layout.domain = new Rect(m.layout.domain.xmin, m.layout.domain.ymin,
        m.layout.domain.xmax, m.layout.domain.ymax);
    if (m.layout.tables != null) {
      if (m.layout.tables.get("raw") != null) {
        layout.tableRaw = m.layout.tables.get("raw");
      }
      if (m.layout.tables.get("meta") != null) {
        layout.tableMeta = m.layout.tables.get("meta");
      }
      if (m.layout.tables.get("xzt") != null) {
        layout.tableXzt = m.layout.tables.get("xzt");
      }
    }
    return layout;
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
