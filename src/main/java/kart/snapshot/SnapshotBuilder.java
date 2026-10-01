package kart.snapshot;

import kart.catalog.CatalogStore;
import kart.catalog.Manifest;
import kart.catalog.StatsSnapshot;
import kart.data.AisParser;
import kart.data.Chunk;
import kart.data.Chunker;
import kart.data.Cleaner;
import kart.data.RawTrajectory;
import kart.data.TDriveParser;
import kart.data.TidAssigner;
import kart.data.Trajectory;
import kart.exec.HBaseBackend;
import kart.exec.KvBackend;
import kart.exec.MemoryBackend;
import kart.geo.Projection;
import kart.geo.Rect;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * End-to-end snapshot build: parse → clean → tid → write → verify → stats → READY.
 */
public final class SnapshotBuilder {

  public static final class Options {
    public String manifestId = "tdrive_v1_ready";
    public Path dataDir;
    public Rect domain; // required for production; if null, derived from data + 1km
    public Long epochMs; // if null, derived
    public boolean useMemory;
    public Path catalogDir;
    public Path hbaseSiteXml;
    public int shardCount = 4;
    public int chunkMaxPoints = 256;
    public long bucketMs = 600_000L;
    public int zorderLevel = 8;
    public int maxZorderRanges = 64;
    /** {@code tdrive} (part-* MULTIPOINT) or {@code ais} (trajectories.txt). */
    public String format = "tdrive";
    /** Projected CRS; default EPSG:32650 (T-Drive). AIS uses EPSG:32634. */
    public String crs = Projection.DEFAULT_CRS;
    /**
     * When set (e.g. {@code ais_v1}), HBase tables become
     * {@code traj_raw_<prefix>}, {@code traj_meta_<prefix>}, {@code idx_*_<prefix>}.
     * Null/empty keeps legacy {@code traj_*_v1} / {@code idx_*_v1} names.
     */
    public String tablePrefix;
  }

  public static final class BuildResult {
    public Manifest manifest;
    public PostingVerifier.Report verify;
    public StatsSnapshot stats;
    public long trajectories;
    public long points;
    public long elapsedMs;
    public String failureReason;
  }

  public BuildResult build(Options opt) throws Exception {
    long tStart = System.currentTimeMillis();
    BuildResult result = new BuildResult();

    List<RawTrajectory> raws = loadAll(opt, result);
    if (result.failureReason != null) {
      return result;
    }

    DataProfiler profiler = new DataProfiler();
    DataProfiler.Profile profile = new DataProfiler.Profile();
    // light profile from already-parsed raws
    Projection projection = new Projection(opt.crs);
    for (RawTrajectory rt : raws) {
      profile.trajectories++;
      for (RawTrajectory.RawPoint rp : rt.points) {
        profile.points++;
        profile.minTs = Math.min(profile.minTs, rp.timestampMs);
        profile.maxTs = Math.max(profile.maxTs, rp.timestampMs);
        Projection.Meters m = projection.toUtm(rp.lon, rp.lat);
        profile.minX = Math.min(profile.minX, m.x);
        profile.maxX = Math.max(profile.maxX, m.x);
        profile.minY = Math.min(profile.minY, m.y);
        profile.maxY = Math.max(profile.maxY, m.y);
      }
    }
    Rect domain = opt.domain != null ? opt.domain : profile.meterDomainExpanded(1000);
    long epoch = opt.epochMs != null ? opt.epochMs : profile.epochMs(opt.bucketMs);

    Manifest manifest = newManifest(opt, domain, epoch);
    manifest.status = Manifest.Status.BUILDING;
    CatalogStore catalog = new CatalogStore(opt.catalogDir);
    Files.createDirectories(opt.catalogDir);
    catalog.saveManifest(manifest);

    Cleaner cleaner = new Cleaner(new Projection(opt.crs), domain);
    Cleaner.Result cleaned = cleaner.cleanAll(raws);
    List<Trajectory> withTid = TidAssigner.assign(cleaned.trajectories);

    IndexBuilders.LayoutParams layout = new IndexBuilders.LayoutParams();
    layout.shardCount = opt.shardCount;
    layout.bucketMs = opt.bucketMs;
    layout.epochMs = epoch;
    layout.zorderLevel = opt.zorderLevel;
    layout.domain = domain;
    applyTableNames(layout, opt.tablePrefix);

    KvBackend kv;
    HBaseBackend hbase = null;
    if (opt.useMemory) {
      kv = new MemoryBackend();
    } else {
      hbase = new HBaseBackend(HBaseBackend.open(opt.hbaseSiteXml));
      Map<String, String> tables = new HashMap<String, String>();
      tables.put("raw", layout.tableRaw);
      tables.put("meta", layout.tableMeta);
      tables.put("time", layout.tableTime);
      tables.put("zorder", layout.tableZorder);
      tables.put("hash", layout.tableHash);
      hbase.ensureTables(opt.shardCount, tables);
      kv = hbase;
    }

    try {
      IndexBuilders builders = new IndexBuilders(layout);
      Chunker chunker = new Chunker(opt.chunkMaxPoints);
      long pointCount = 0;
      for (Trajectory t : withTid) {
        List<Chunk> chunks = chunker.chunk(t);
        builders.writeTrajectory(kv, t, chunks);
        pointCount += t.size();
      }

      PostingVerifier verifier = new PostingVerifier(layout);
      PostingVerifier.Report report = verifier.verify(kv, withTid);
      result.verify = report;
      if (!report.ok()) {
        result.failureReason = "verify failed: missing=" + report.missing.size()
            + " extra=" + report.extra.size();
        result.manifest = manifest;
        result.elapsedMs = System.currentTimeMillis() - tStart;
        return result;
      }

      StatsSnapshot stats = new StatsBuilder(layout).build(opt.manifestId, withTid);
      catalog.saveStats(stats);
      result.stats = stats;

      manifest.build_report = new Manifest.BuildReport();
      manifest.build_report.trajectories = withTid.size();
      manifest.build_report.points = pointCount;
      // result.trajectories temporarily held parseReject count from loadAll
      long parseRejects = result.trajectories;
      manifest.build_report.rejected.parse = parseRejects;
      manifest.build_report.rejected.out_of_domain = cleaned.stats.outOfDomainTrajectories;
      manifest.build_report.rejected.empty = cleaned.stats.emptyAfterClean;
      manifest.build_report.dedup_removed = cleaned.stats.dedupRemoved;
      manifest.transitionToReady();
      catalog.saveManifest(manifest);

      result.manifest = manifest;
      result.trajectories = withTid.size();
      result.points = pointCount;
      result.elapsedMs = System.currentTimeMillis() - tStart;
      return result;
    } finally {
      if (hbase != null) {
        hbase.closeConnection();
      } else {
        kv.close();
      }
    }
  }

  private static List<RawTrajectory> loadAll(Options opt, BuildResult result) throws IOException {
    Path dir = opt.dataDir;
    String format = opt.format == null ? "tdrive" : opt.format.trim().toLowerCase();
    List<Path> files = listInputFiles(dir, format);
    List<RawTrajectory> raws = new ArrayList<RawTrajectory>();
    long parseReject = 0;
    TDriveParser tdrive = "tdrive".equals(format) ? new TDriveParser() : null;
    AisParser ais = "ais".equals(format) ? new AisParser() : null;
    if (tdrive == null && ais == null) {
      result.failureReason = "unsupported format: " + opt.format + " (use tdrive|ais)";
      return raws;
    }
    for (Path part : files) {
      try (BufferedReader br = Files.newBufferedReader(part, StandardCharsets.UTF_8)) {
        String line;
        while ((line = br.readLine()) != null) {
          if (line.trim().isEmpty()) {
            continue;
          }
          try {
            if (ais != null) {
              raws.add(ais.parseLine(line));
            } else {
              raws.add(tdrive.parseLine(line));
            }
          } catch (IllegalArgumentException e) {
            parseReject++;
          }
        }
      }
    }
    if (raws.isEmpty()) {
      result.failureReason = "no trajectories parsed from " + dir + " format=" + format;
    }
    result.trajectories = parseReject; // stash parse rejects until overwrite with traj count
    return raws;
  }

  static List<Path> listInputFiles(Path dir, String format) throws IOException {
    if ("ais".equals(format)) {
      List<Path> out = new ArrayList<Path>();
      Path named = dir.resolve("trajectories.txt");
      if (Files.isRegularFile(named)) {
        out.add(named);
        return out;
      }
      try (java.nio.file.DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.txt")) {
        for (Path p : ds) {
          out.add(p);
        }
      }
      java.util.Collections.sort(out);
      return out;
    }
    return DataProfiler.listParts(dir);
  }

  static void applyTableNames(IndexBuilders.LayoutParams layout, String tablePrefix) {
    if (tablePrefix == null || tablePrefix.trim().isEmpty()) {
      return;
    }
    String p = tablePrefix.trim();
    layout.tableRaw = "traj_raw_" + p;
    layout.tableMeta = "traj_meta_" + p;
    layout.tableTime = "idx_time_" + p;
    layout.tableZorder = "idx_zorder_" + p;
    layout.tableHash = "idx_hash_" + p;
  }

  private static Manifest newManifest(Options opt, Rect domain, long epoch) {
    IndexBuilders.LayoutParams names = new IndexBuilders.LayoutParams();
    applyTableNames(names, opt.tablePrefix);
    Manifest m = new Manifest();
    m.manifest_id = opt.manifestId;
    m.semantics_version = "point_similarity_v2";
    m.stats_version = "stats_v1";
    m.tid_map_location = names.tableMeta;
    m.dataset = new Manifest.Dataset();
    m.dataset.source = opt.dataDir.toString();
    m.dataset.checksum = "see-build-report";
    m.dataset.assumptions = new String[]{"A1", "A2", "A3", "A4", "A5"};
    m.layout = new Manifest.Layout();
    m.layout.shard_count = opt.shardCount;
    m.layout.chunk_max_points = opt.chunkMaxPoints;
    m.layout.bucket_ms = opt.bucketMs;
    m.layout.epoch_ms = epoch;
    m.layout.zorder_level = opt.zorderLevel;
    m.layout.crs = opt.crs == null || opt.crs.trim().isEmpty()
        ? Projection.DEFAULT_CRS : opt.crs.trim();
    m.layout.hash_version = "murmur3_x64_128";
    m.layout.domain = new Manifest.Domain();
    m.layout.domain.xmin = domain.minX;
    m.layout.domain.xmax = domain.maxX;
    m.layout.domain.ymin = domain.minY;
    m.layout.domain.ymax = domain.maxY;
    m.layout.tables.put("raw", names.tableRaw);
    m.layout.tables.put("meta", names.tableMeta);
    m.layout.tables.put("time", names.tableTime);
    m.layout.tables.put("zorder", names.tableZorder);
    m.layout.tables.put("hash", names.tableHash);
    return m;
  }

  /** Fixture build into MemoryBackend for tests. */
  public static MemoryBackend buildFixtureInMemory() throws IOException {
    MemoryBackend kv = new MemoryBackend();
    writeFixture(kv);
    return kv;
  }

  /**
   * Load fixture R/A/B/C into isolated HBase tables and publish {@code fixture_v1_ready}
   * under {@code catalogDir}. Does not touch T-Drive tables ({@code traj_*_v1}).
   */
  public static Manifest loadFixtureToHBase(Path hbaseSiteXml, Path catalogDir) throws IOException {
    IndexBuilders.LayoutParams layout = FixtureBuilder.layoutParams();
    HBaseBackend hbase = new HBaseBackend(HBaseBackend.open(hbaseSiteXml));
    try {
      hbase.ensureTables(layout.shardCount, FixtureBuilder.tableNameMap());
      writeFixture(hbase);

      Manifest manifest = FixtureBuilder.fixtureManifest();
      manifest.status = Manifest.Status.READY;
      CatalogStore catalog = new CatalogStore(catalogDir);
      Files.createDirectories(catalogDir);
      catalog.saveManifest(manifest);

      StatsSnapshot stats = new StatsBuilder(layout)
          .build(FixtureBuilder.MANIFEST_ID, FixtureBuilder.trajectories());
      catalog.saveStats(stats);
      return manifest;
    } finally {
      hbase.closeConnection();
    }
  }

  private static void writeFixture(KvBackend kv) throws IOException {
    List<Trajectory> trajs = FixtureBuilder.trajectories();
    IndexBuilders.LayoutParams layout = FixtureBuilder.layoutParams();
    IndexBuilders builders = new IndexBuilders(layout);
    Chunker chunker = new Chunker(256);
    for (Trajectory t : trajs) {
      builders.writeTrajectory(kv, t, chunker.chunk(t));
    }
  }
}
