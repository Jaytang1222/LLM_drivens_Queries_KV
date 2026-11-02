package kart.snapshot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import kart.catalog.CatalogStore;
import kart.catalog.Manifest;
import kart.catalog.StatsSnapshot;
import kart.data.AisParser;
import kart.data.Chunk;
import kart.data.Chunker;
import kart.data.Cleaner;
import kart.data.PortoParser;
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
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * End-to-end snapshot build: parse → clean → spill → tid-ordered write → verify → stats → READY.
 * Cleaned trajectories are spilled to disk so large scale replicas (x50/x100) do not OOM;
 * write/verify still checkpoint for HBase resume.
 */
public final class SnapshotBuilder {

  private static final ObjectMapper MAPPER = new ObjectMapper()
      .enable(SerializationFeature.INDENT_OUTPUT);

  /** On-disk write resume state (Jackson-friendly). */
  public static final class WriteCheckpoint {
    public String phase = "write";
    public int done;
    public int total;
    public int batch_size;
    public long points;
    public String note;
  }

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
    public int xzLevel = 12;
    public int tshapeMaxDepth = 12;
    public int xztLevel = 12;
    public int maxZorderRanges = 64;
    /** {@code tdrive} (part-* MULTIPOINT), {@code ais} (trajectories.txt), or {@code porto} (CSV). */
    public String format = "tdrive";
    /** Projected CRS; default EPSG:32650 (T-Drive). AIS: 32634. Porto: 32629. */
    public String crs = Projection.DEFAULT_CRS;
    /**
     * When set (e.g. {@code ais_v1} / {@code porto_v1}), HBase tables become
     * {@code traj_raw_<prefix>}, {@code traj_meta_<prefix>}, {@code idx_*_<prefix>}.
     * Null/empty keeps legacy {@code traj_*_v1} / {@code idx_*_v1} names.
     */
    public String tablePrefix;
    /**
     * Write resume file. Null → HBase builds use {@code catalog/<id>.write.ckpt.json};
     * memory builds skip unless this is set explicitly (tests).
     */
    public Path writeCheckpointFile;
    /** Trajectories per flush+checkpoint boundary during write. */
    public int writeBatchSize = 500;
    /** When false, ignore existing write/verify checkpoints and start from 0. */
    public boolean resume = true;
    /**
     * Sampled posting verify: {@code >0} = absolute count. Prefer over {@link #verifySampleFrac}.
     * {@code 0} + frac {@code 0} = full verify (default; Porto / native tdrive unchanged).
     */
    public int verifySampleSize = 0;
    /** When {@link #verifySampleSize} is 0 and this {@code >0}, sample size = ceil(n * frac). */
    public double verifySampleFrac = 0;
    public long verifySampleSeed = 20261027L;
    /**
     * When true (default), parse+clean streams into a disk spill under catalog then
     * write/verify/stats read by tid order — required for multi-million trajectory builds.
     * Set false only for tiny in-process experiments that must avoid spill I/O.
     */
    public boolean streamSpill = true;
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
    if (opt.streamSpill) {
      return buildStreaming(opt);
    }
    return buildInMemory(opt);
  }

  /** Legacy all-in-RAM path (tests / tiny fixtures). */
  private BuildResult buildInMemory(Options opt) throws Exception {
    long tStart = System.currentTimeMillis();
    BuildResult result = new BuildResult();

    List<RawTrajectory> raws = loadAll(opt, result);
    if (result.failureReason != null) {
      return result;
    }

    DataProfiler.Profile profile = profileRaws(raws, opt.crs);
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

    IndexBuilders.LayoutParams layout = layoutFrom(opt, domain, epoch, withTid);
    manifest.layout.xzt_horizon_ms = layout.xztHorizonMs;
    applyTableNames(layout, opt.tablePrefix);
    catalog.saveManifest(manifest);

    return finishBuild(opt, result, manifest, catalog, layout, withTid,
        cleaned.stats, result.trajectories, tStart);
  }

  /**
   * Production path: profile (optional) → stream parse+clean into spill → tid-ordered
   * write/verify/stats from spill. Peak heap ≈ id index + one trajectory + write buffers.
   */
  private BuildResult buildStreaming(Options opt) throws Exception {
    long tStart = System.currentTimeMillis();
    BuildResult result = new BuildResult();
    Files.createDirectories(opt.catalogDir);
    Path spillDir = opt.catalogDir.resolve(opt.manifestId + ".spill");

    Rect domain;
    long epoch;
    if (opt.domain == null || opt.epochMs == null) {
      DataProfiler.Profile profile = profileDirectoryStreaming(opt, result);
      if (result.failureReason != null) {
        return result;
      }
      domain = opt.domain != null ? opt.domain : profile.meterDomainExpanded(1000);
      epoch = opt.epochMs != null ? opt.epochMs : profile.epochMs(opt.bucketMs);
    } else {
      domain = opt.domain;
      epoch = opt.epochMs;
    }

    Manifest manifest = newManifest(opt, domain, epoch);
    manifest.status = Manifest.Status.BUILDING;
    CatalogStore catalog = new CatalogStore(opt.catalogDir);
    catalog.saveManifest(manifest);

    Cleaner.Stats cleanStats = new Cleaner.Stats();
    long parseRejects;
    long dataMaxMs;
    TrajectorySpill spill = null;
    try {
      if (opt.resume && TrajectorySpill.isComplete(spillDir)) {
        System.out.println("SPILL_PHASE=reuse dir=" + spillDir);
        System.out.flush();
        spill = TrajectorySpill.openComplete(spillDir);
        SpillFill meta = new SpillFill();
        loadSpillMeta(spillDir, cleanStats, meta);
        parseRejects = meta.parseRejects;
        dataMaxMs = meta.dataMaxMs > 0 ? meta.dataMaxMs : scanSpillMaxTs(spill, epoch);
      } else {
        TrajectorySpill.deleteQuietly(spillDir);
        SpillFill filled = fillSpill(opt, spillDir, domain, cleanStats, result);
        if (result.failureReason != null) {
          return result;
        }
        spill = filled.spill;
        parseRejects = filled.parseRejects;
        dataMaxMs = filled.dataMaxMs;
      }

      IndexBuilders.LayoutParams layout = new IndexBuilders.LayoutParams();
      layout.shardCount = opt.shardCount;
      layout.bucketMs = opt.bucketMs;
      layout.epochMs = epoch;
      layout.zorderLevel = opt.zorderLevel;
      layout.xzLevel = opt.xzLevel;
      layout.tshapeMaxDepth = opt.tshapeMaxDepth;
      layout.xztLevel = opt.xztLevel;
      layout.domain = domain;
      layout.xztHorizonMs = dataMaxMs + 7L * 24L * 3600L * 1000L;
      manifest.layout.xzt_horizon_ms = layout.xztHorizonMs;
      applyTableNames(layout, opt.tablePrefix);
      catalog.saveManifest(manifest);

      int n = spill.size();
      if (n == 0) {
        result.failureReason = "no trajectories after clean from " + opt.dataDir;
        result.manifest = manifest;
        result.elapsedMs = System.currentTimeMillis() - tStart;
        return result;
      }

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
        tables.put("xz", layout.tableXz);
        tables.put("tshape", layout.tableTshape);
        tables.put("xzt", layout.tableXzt);
        hbase.ensureTables(opt.shardCount, tables);
        truncateIfNoResume(opt, hbase, tables);
        kv = hbase;
      }

      try {
        IndexBuilders builders = new IndexBuilders(layout);
        Chunker chunker = new Chunker(opt.chunkMaxPoints);
        Path writeCkpt = resolveWriteCheckpoint(opt);
        long pointCount = writeTrajectoriesFromSpill(
            kv, hbase, spill, builders, chunker, writeCkpt, opt);

        PostingVerifier verifier = new PostingVerifier(layout);
        PostingVerifier.Options vOpt = (!opt.useMemory || n > 20_000)
            ? PostingVerifier.Options.checkpointed(
                opt.catalogDir.resolve(opt.manifestId + ".verify.ckpt.json"))
            : PostingVerifier.Options.memoryFull();
        if (vOpt.checkpointFile != null) {
          vOpt.resume = opt.resume;
          vOpt.sampleSize = Math.max(0, opt.verifySampleSize);
          vOpt.sampleFrac = Math.max(0, opt.verifySampleFrac);
          vOpt.sampleSeed = opt.verifySampleSeed;
          if (vOpt.sampleEnabled()) {
            int k = PostingVerifier.resolveSampleSize(n, vOpt);
            System.out.println("VERIFY_MODE=checkpoint+sample file=" + vOpt.checkpointFile
                + " batch=" + vOpt.batchSize + " resume=" + vOpt.resume
                + " sample_size=" + k + " sample_seed=" + vOpt.sampleSeed
                + " (extra-key scan skipped; trajs loaded from spill)");
          } else {
            System.out.println("VERIFY_MODE=checkpoint file=" + vOpt.checkpointFile
                + " batch=" + vOpt.batchSize + " resume=" + vOpt.resume
                + " (extra-key scan skipped; trajs loaded from spill)");
          }
          System.out.flush();
        }

        List<Trajectory> forVerify = loadTrajectoriesForVerify(spill, n, vOpt);
        PostingVerifier.Report report = verifier.verify(kv, forVerify, vOpt);
        forVerify = null;
        result.verify = report;
        if (!report.ok()) {
          result.failureReason = "verify failed: missing=" + report.missing.size()
              + " extra=" + report.extra.size();
          result.manifest = manifest;
          result.elapsedMs = System.currentTimeMillis() - tStart;
          return result;
        }

        StatsSnapshot stats = buildStatsFromSpill(layout, opt.manifestId, spill);
        catalog.saveStats(stats);
        result.stats = stats;

        manifest.build_report = new Manifest.BuildReport();
        manifest.build_report.trajectories = n;
        manifest.build_report.points = pointCount;
        manifest.build_report.rejected.parse = parseRejects;
        manifest.build_report.rejected.out_of_domain = cleanStats.outOfDomainTrajectories;
        manifest.build_report.rejected.empty = cleanStats.emptyAfterClean;
        manifest.build_report.dedup_removed = cleanStats.dedupRemoved;
        manifest.transitionToReady();
        catalog.saveManifest(manifest);
        deleteQuietly(writeCkpt);
        if (vOpt.checkpointFile != null) {
          deleteQuietly(vOpt.checkpointFile);
        }

        result.manifest = manifest;
        result.trajectories = n;
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
    } finally {
      if (spill != null) {
        try {
          spill.close();
        } catch (IOException ignored) {
          // best-effort
        }
      }
      if (result.failureReason == null && result.manifest != null
          && result.manifest.status == Manifest.Status.READY) {
        TrajectorySpill.deleteQuietly(spillDir);
      }
    }
  }

  private BuildResult finishBuild(Options opt, BuildResult result, Manifest manifest,
                                  CatalogStore catalog, IndexBuilders.LayoutParams layout,
                                  List<Trajectory> withTid, Cleaner.Stats cleanStats,
                                  long parseRejects, long tStart) throws Exception {
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
      tables.put("xz", layout.tableXz);
      tables.put("tshape", layout.tableTshape);
      tables.put("xzt", layout.tableXzt);
      hbase.ensureTables(opt.shardCount, tables);
      truncateIfNoResume(opt, hbase, tables);
      kv = hbase;
    }

    try {
      IndexBuilders builders = new IndexBuilders(layout);
      Chunker chunker = new Chunker(opt.chunkMaxPoints);
      Path writeCkpt = resolveWriteCheckpoint(opt);
      long pointCount = writeTrajectories(kv, hbase, withTid, builders, chunker, writeCkpt, opt);

      PostingVerifier verifier = new PostingVerifier(layout);
      PostingVerifier.Options vOpt = (!opt.useMemory || withTid.size() > 20_000)
          ? PostingVerifier.Options.checkpointed(
              opt.catalogDir.resolve(opt.manifestId + ".verify.ckpt.json"))
          : PostingVerifier.Options.memoryFull();
      if (vOpt.checkpointFile != null) {
        vOpt.resume = opt.resume;
        vOpt.sampleSize = Math.max(0, opt.verifySampleSize);
        vOpt.sampleFrac = Math.max(0, opt.verifySampleFrac);
        vOpt.sampleSeed = opt.verifySampleSeed;
        if (vOpt.sampleEnabled()) {
          int k = PostingVerifier.resolveSampleSize(withTid.size(), vOpt);
          System.out.println("VERIFY_MODE=checkpoint+sample file=" + vOpt.checkpointFile
              + " batch=" + vOpt.batchSize + " resume=" + vOpt.resume
              + " sample_size=" + k + " sample_seed=" + vOpt.sampleSeed
              + " (extra-key scan skipped)");
        } else {
          System.out.println("VERIFY_MODE=checkpoint file=" + vOpt.checkpointFile
              + " batch=" + vOpt.batchSize + " resume=" + vOpt.resume
              + " (extra-key scan skipped)");
        }
        System.out.flush();
      }
      PostingVerifier.Report report = verifier.verify(kv, withTid, vOpt);
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
      manifest.build_report.rejected.parse = parseRejects;
      manifest.build_report.rejected.out_of_domain = cleanStats.outOfDomainTrajectories;
      manifest.build_report.rejected.empty = cleanStats.emptyAfterClean;
      manifest.build_report.dedup_removed = cleanStats.dedupRemoved;
      manifest.transitionToReady();
      catalog.saveManifest(manifest);
      deleteQuietly(writeCkpt);
      if (vOpt.checkpointFile != null) {
        deleteQuietly(vOpt.checkpointFile);
      }

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

  private static final class SpillFill {
    TrajectorySpill spill;
    long parseRejects;
    long dataMaxMs;
  }

  private static final class SpillMeta {
    public long parse_rejects;
    public long data_max_ms;
    public long out_of_domain;
    public long empty_after_clean;
    public long dedup_removed;
    public long same_t_dropped;
    public long projected;
  }

  private static SpillFill fillSpill(Options opt, Path spillDir, Rect domain,
                                     Cleaner.Stats cleanStats, BuildResult result)
      throws IOException {
    SpillFill filled = new SpillFill();
    Cleaner cleaner = new Cleaner(new Projection(opt.crs), domain);
    TrajectorySpill spill = TrajectorySpill.create(spillDir);
    long parseReject = 0;
    long dataMaxMs = opt.epochMs != null ? opt.epochMs : 0L;
    long t0 = System.currentTimeMillis();
    int kept = 0;
    System.out.println("SPILL_PHASE=fill dir=" + spillDir);
    System.out.flush();
    try {
      for (RawTrajectory raw : iterateRaw(opt, result)) {
        if (result.failureReason != null) {
          spill.close();
          TrajectorySpill.deleteQuietly(spillDir);
          return filled;
        }
        try {
          Trajectory t = cleaner.cleanOne(raw, 0L);
          cleanStats.projected += t.size();
          cleanStats.dedupRemoved += t.dedupRemoved;
          cleanStats.sameTDropped += t.sameTDropped;
          if (t.size() > 0) {
            dataMaxMs = Math.max(dataMaxMs, t.points().get(t.size() - 1).timestampMs);
          }
          spill.append(t);
          kept++;
          if (kept == 1 || kept % 2000 == 0) {
            long elapsed = System.currentTimeMillis() - t0;
            System.out.println("SPILL_PROGRESS kept=" + kept
                + " parse_reject=" + parseReject
                + " out_of_domain=" + cleanStats.outOfDomainTrajectories
                + " empty=" + cleanStats.emptyAfterClean
                + " elapsed_s=" + (elapsed / 1000));
            System.out.flush();
          }
        } catch (Cleaner.OutOfDomainException e) {
          cleanStats.outOfDomainTrajectories++;
        } catch (Cleaner.EmptyTrajectoryException e) {
          cleanStats.emptyAfterClean++;
        }
      }
      // iterateRaw stashes parse rejects in result.trajectories
      parseReject = result.trajectories;
      if (kept == 0) {
        if (result.failureReason == null) {
          result.failureReason = "no trajectories after clean from " + opt.dataDir
              + " format=" + opt.format;
        }
        spill.close();
        TrajectorySpill.deleteQuietly(spillDir);
        return filled;
      }
      spill.finish();
    } catch (IOException e) {
      try {
        spill.close();
      } catch (IOException ignored) {
        // ignore
      }
      TrajectorySpill.deleteQuietly(spillDir);
      throw e;
    }

    SpillMeta meta = new SpillMeta();
    meta.parse_rejects = parseReject;
    meta.data_max_ms = dataMaxMs;
    meta.out_of_domain = cleanStats.outOfDomainTrajectories;
    meta.empty_after_clean = cleanStats.emptyAfterClean;
    meta.dedup_removed = cleanStats.dedupRemoved;
    meta.same_t_dropped = cleanStats.sameTDropped;
    meta.projected = cleanStats.projected;
    MAPPER.writeValue(spillDir.resolve("meta.json").toFile(), meta);

    filled.spill = spill;
    filled.parseRejects = parseReject;
    filled.dataMaxMs = dataMaxMs;
    return filled;
  }

  /** Yields raw trajectories one-by-one; sets {@code result.trajectories} = parseReject count. */
  private static Iterable<RawTrajectory> iterateRaw(final Options opt, final BuildResult result)
      throws IOException {
    final String format = opt.format == null ? "tdrive" : opt.format.trim().toLowerCase();
    final List<Path> files = listInputFiles(opt.dataDir, format);
    if (!"tdrive".equals(format) && !"ais".equals(format) && !"porto".equals(format)) {
      result.failureReason = "unsupported format: " + opt.format + " (use tdrive|ais|porto)";
      return Collections.emptyList();
    }
    return new Iterable<RawTrajectory>() {
      @Override
      public java.util.Iterator<RawTrajectory> iterator() {
        return new java.util.Iterator<RawTrajectory>() {
          private final TDriveParser tdrive = "tdrive".equals(format) ? new TDriveParser() : null;
          private final AisParser ais = "ais".equals(format) ? new AisParser() : null;
          private final PortoParser porto = "porto".equals(format) ? new PortoParser() : null;
          private int fileIdx = 0;
          private BufferedReader br;
          private RawTrajectory next;
          private long parseReject;
          private boolean exhausted;

          private void advance() {
            try {
              while (true) {
                if (br == null) {
                  if (fileIdx >= files.size()) {
                    exhausted = true;
                    next = null;
                    result.trajectories = parseReject;
                    return;
                  }
                  br = Files.newBufferedReader(files.get(fileIdx++), StandardCharsets.UTF_8);
                }
                String line = br.readLine();
                if (line == null) {
                  br.close();
                  br = null;
                  continue;
                }
                if (line.trim().isEmpty()) {
                  continue;
                }
                try {
                  if (ais != null) {
                    next = ais.parseLine(line);
                  } else if (porto != null) {
                    next = porto.parseLine(line);
                  } else {
                    next = tdrive.parseLine(line);
                  }
                  return;
                } catch (IllegalArgumentException e) {
                  parseReject++;
                }
              }
            } catch (IOException e) {
              throw new RuntimeException(e);
            }
          }

          @Override
          public boolean hasNext() {
            if (exhausted) {
              return false;
            }
            if (next == null) {
              advance();
            }
            return next != null;
          }

          @Override
          public RawTrajectory next() {
            if (!hasNext()) {
              throw new java.util.NoSuchElementException();
            }
            RawTrajectory out = next;
            next = null;
            return out;
          }

          @Override
          public void remove() {
            throw new UnsupportedOperationException();
          }
        };
      }
    };
  }

  private static long scanSpillMaxTs(TrajectorySpill spill, long epoch) throws IOException {
    long max = epoch;
    int n = spill.size();
    for (int i = 0; i < n; i++) {
      Trajectory t = spill.readEncounter(i);
      if (t.size() > 0) {
        max = Math.max(max, t.points().get(t.size() - 1).timestampMs);
      }
    }
    return max;
  }

  private static void loadSpillMeta(Path spillDir, Cleaner.Stats cleanStats, SpillFill into)
      throws IOException {
    Path metaFile = spillDir.resolve("meta.json");
    if (!Files.isRegularFile(metaFile)) {
      into.parseRejects = 0;
      into.dataMaxMs = 0;
      return;
    }
    SpillMeta meta = MAPPER.readValue(metaFile.toFile(), SpillMeta.class);
    into.parseRejects = meta.parse_rejects;
    into.dataMaxMs = meta.data_max_ms;
    cleanStats.outOfDomainTrajectories = meta.out_of_domain;
    cleanStats.emptyAfterClean = meta.empty_after_clean;
    cleanStats.dedupRemoved = meta.dedup_removed;
    cleanStats.sameTDropped = meta.same_t_dropped;
    cleanStats.projected = meta.projected;
  }

  static long writeTrajectoriesFromSpill(KvBackend kv, HBaseBackend hbase, TrajectorySpill spill,
                                         IndexBuilders builders, Chunker chunker, Path writeCkpt,
                                         Options opt) throws IOException {
    int n = spill.size();
    int batch = Math.max(1, opt.writeBatchSize);
    int start = 0;
    long pointCount = 0;
    if (writeCkpt != null && opt.resume) {
      WriteCheckpoint ck = loadWriteCheckpoint(writeCkpt);
      if (ck.total > 0 && ck.total != n) {
        throw new IllegalStateException("write checkpoint total=" + ck.total + " but trajectories=" + n
            + " (" + writeCkpt + "); truncate tables and pass --no-resume, or delete the ckpt");
      }
      if ("done".equals(ck.phase) && ck.done >= n) {
        System.out.println("WRITE_PHASE=already_done done=" + ck.done + "/" + n
            + " points=" + ck.points + " checkpoint=" + writeCkpt);
        System.out.flush();
        return ck.points;
      }
      start = Math.max(0, Math.min(ck.done, n));
      pointCount = Math.max(0L, ck.points);
      if (start > 0) {
        System.out.println("WRITE_RESUME done=" + start + "/" + n
            + " points=" + pointCount + " checkpoint=" + writeCkpt);
        System.out.flush();
      }
    } else if (writeCkpt != null) {
      System.out.println("WRITE_MODE=checkpoint+spill file=" + writeCkpt
          + " batch=" + batch + " resume=false");
      System.out.flush();
    }
    if (writeCkpt != null && start == 0) {
      System.out.println("WRITE_MODE=checkpoint+spill file=" + writeCkpt
          + " batch=" + batch + " resume=" + opt.resume);
      System.out.flush();
    }

    long t0 = System.currentTimeMillis();
    for (int tid0 = start; tid0 < n; tid0++) {
      Trajectory raw = spill.readByTid0(tid0);
      Trajectory t = TidAssigner.withTid(raw, tid0 + 1L);
      List<Chunk> chunks = chunker.chunk(t);
      builders.writeTrajectory(kv, t, chunks);
      pointCount += t.size();
      int done = tid0 + 1;
      boolean boundary = done % batch == 0 || done == n;
      if (done == 1 || done % 200 == 0 || boundary) {
        long elapsed = System.currentTimeMillis() - t0;
        int progressed = done - start;
        double rate = progressed / Math.max(0.001, elapsed / 1000.0);
        long etaSec = (long) ((n - done) / Math.max(0.001, rate));
        System.out.println("WRITE_PROGRESS done=" + done + "/" + n
            + " points=" + pointCount
            + " elapsed_s=" + (elapsed / 1000)
            + " rate_traj_s=" + String.format("%.2f", rate)
            + " eta_s=" + etaSec);
        System.out.flush();
      }
      if (writeCkpt != null && boundary) {
        if (hbase != null) {
          hbase.flushWrites();
        }
        saveWriteCheckpoint(writeCkpt, n, done, batch, pointCount,
            done >= n ? "done" : "write",
            done >= n ? "write_flushed_complete" : "in_progress");
      }
    }
    if (writeCkpt == null && hbase != null) {
      hbase.flushWrites();
    }
    return pointCount;
  }

  /**
   * Full list when sample verify is off; sparse list (nulls elsewhere) when sampling so
   * {@link PostingVerifier} indices still match tid order without loading all points.
   */
  static List<Trajectory> loadTrajectoriesForVerify(TrajectorySpill spill, int n,
                                                    PostingVerifier.Options vOpt)
      throws IOException {
    if (vOpt != null && vOpt.sampleEnabled()) {
      int k = PostingVerifier.resolveSampleSize(n, vOpt);
      List<Integer> sample = PostingVerifier.sampleIndices(n, k, vOpt.sampleSeed);
      Set<Integer> need = new HashSet<Integer>(sample);
      List<Trajectory> sparse = new ArrayList<Trajectory>(n);
      for (int i = 0; i < n; i++) {
        sparse.add(null);
      }
      System.out.println("VERIFY_LOAD=spill_sample need=" + need.size() + " of n=" + n);
      System.out.flush();
      for (int idx : need) {
        sparse.set(idx, TidAssigner.withTid(spill.readByTid0(idx), idx + 1L));
      }
      return sparse;
    }
    System.out.println("VERIFY_LOAD=spill_full n=" + n);
    System.out.flush();
    List<Trajectory> all = new ArrayList<Trajectory>(n);
    for (int tid0 = 0; tid0 < n; tid0++) {
      all.add(TidAssigner.withTid(spill.readByTid0(tid0), tid0 + 1L));
    }
    return all;
  }

  static StatsSnapshot buildStatsFromSpill(IndexBuilders.LayoutParams layout, String manifestId,
                                           TrajectorySpill spill) throws IOException {
    StatsBuilder sb = new StatsBuilder(layout);
    StatsSnapshot s = sb.newStats(manifestId);
    int n = spill.size();
    long t0 = System.currentTimeMillis();
    System.out.println("STATS_PHASE=build trajectories=" + n + " mode=spill");
    System.out.flush();
    for (int tid0 = 0; tid0 < n; tid0++) {
      Trajectory t = TidAssigner.withTid(spill.readByTid0(tid0), tid0 + 1L);
      sb.accumulate(s, t);
      sb.maybeLogProgress(s, tid0 + 1, n, t0);
    }
    return sb.finish(s, t0);
  }

  private static DataProfiler.Profile profileRaws(List<RawTrajectory> raws, String crs) {
    DataProfiler.Profile profile = new DataProfiler.Profile();
    Projection projection = new Projection(crs);
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
    return profile;
  }

  private static DataProfiler.Profile profileDirectoryStreaming(Options opt, BuildResult result)
      throws IOException {
    DataProfiler.Profile profile = new DataProfiler.Profile();
    Projection projection = new Projection(opt.crs);
    System.out.println("PROFILE_PHASE=stream format=" + opt.format);
    System.out.flush();
    for (RawTrajectory rt : iterateRaw(opt, result)) {
      if (result.failureReason != null) {
        return profile;
      }
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
    if (profile.trajectories == 0 && result.failureReason == null) {
      result.failureReason = "no trajectories parsed from " + opt.dataDir + " format=" + opt.format;
    }
    return profile;
  }

  private static IndexBuilders.LayoutParams layoutFrom(Options opt, Rect domain, long epoch,
                                                       List<Trajectory> withTid) {
    IndexBuilders.LayoutParams layout = new IndexBuilders.LayoutParams();
    layout.shardCount = opt.shardCount;
    layout.bucketMs = opt.bucketMs;
    layout.epochMs = epoch;
    layout.zorderLevel = opt.zorderLevel;
    layout.xzLevel = opt.xzLevel;
    layout.tshapeMaxDepth = opt.tshapeMaxDepth;
    layout.xztLevel = opt.xztLevel;
    layout.domain = domain;
    long dataMaxMs = epoch;
    for (Trajectory t : withTid) {
      if (t.size() > 0) {
        dataMaxMs = Math.max(dataMaxMs, t.points().get(t.size() - 1).timestampMs);
      }
    }
    layout.xztHorizonMs = dataMaxMs + 7L * 24L * 3600L * 1000L;
    applyTableNames(layout, opt.tablePrefix);
    return layout;
  }

  /**
   * Write all trajectories; when {@code writeCkpt} is non-null, flush+checkpoint every
   * {@link Options#writeBatchSize} completed trajectories so a crash can resume.
   * Checkpoint advances only after a successful flush (HBase) so buffered puts are durable.
   */
  static long writeTrajectories(KvBackend kv, HBaseBackend hbase, List<Trajectory> withTid,
                                IndexBuilders builders, Chunker chunker, Path writeCkpt,
                                Options opt) throws IOException {
    int n = withTid.size();
    int batch = Math.max(1, opt.writeBatchSize);
    int start = 0;
    long pointCount = 0;
    if (writeCkpt != null && opt.resume) {
      WriteCheckpoint ck = loadWriteCheckpoint(writeCkpt);
      if (ck.total > 0 && ck.total != n) {
        throw new IllegalStateException("write checkpoint total=" + ck.total + " but trajectories=" + n
            + " (" + writeCkpt + "); truncate tables and pass --no-resume, or delete the ckpt");
      }
      if ("done".equals(ck.phase) && ck.done >= n) {
        System.out.println("WRITE_PHASE=already_done done=" + ck.done + "/" + n
            + " points=" + ck.points + " checkpoint=" + writeCkpt);
        System.out.flush();
        return ck.points;
      }
      start = Math.max(0, Math.min(ck.done, n));
      pointCount = Math.max(0L, ck.points);
      if (start > 0) {
        System.out.println("WRITE_RESUME done=" + start + "/" + n
            + " points=" + pointCount + " checkpoint=" + writeCkpt);
        System.out.flush();
      }
    } else if (writeCkpt != null) {
      System.out.println("WRITE_MODE=checkpoint file=" + writeCkpt
          + " batch=" + batch + " resume=false");
      System.out.flush();
    }
    if (writeCkpt != null && start == 0) {
      System.out.println("WRITE_MODE=checkpoint file=" + writeCkpt
          + " batch=" + batch + " resume=" + opt.resume);
      System.out.flush();
    }

    long t0 = System.currentTimeMillis();
    for (int i = start; i < n; i++) {
      Trajectory t = withTid.get(i);
      List<Chunk> chunks = chunker.chunk(t);
      builders.writeTrajectory(kv, t, chunks);
      pointCount += t.size();
      int done = i + 1;
      boolean boundary = done % batch == 0 || done == n;
      if (done == 1 || done % 200 == 0 || boundary) {
        long elapsed = System.currentTimeMillis() - t0;
        int progressed = done - start;
        double rate = progressed / Math.max(0.001, elapsed / 1000.0);
        long etaSec = (long) ((n - done) / Math.max(0.001, rate));
        System.out.println("WRITE_PROGRESS done=" + done + "/" + n
            + " points=" + pointCount
            + " elapsed_s=" + (elapsed / 1000)
            + " rate_traj_s=" + String.format("%.2f", rate)
            + " eta_s=" + etaSec);
        System.out.flush();
      }
      if (writeCkpt != null && boundary) {
        if (hbase != null) {
          hbase.flushWrites();
        }
        saveWriteCheckpoint(writeCkpt, n, done, batch, pointCount,
            done >= n ? "done" : "write",
            done >= n ? "write_flushed_complete" : "in_progress");
      }
    }
    if (writeCkpt == null && hbase != null) {
      hbase.flushWrites();
    }
    return pointCount;
  }

  static Path resolveWriteCheckpoint(Options opt) {
    if (opt.writeCheckpointFile != null) {
      return opt.writeCheckpointFile;
    }
    if (opt.useMemory || opt.catalogDir == null) {
      return null;
    }
    return opt.catalogDir.resolve(opt.manifestId + ".write.ckpt.json");
  }

  private static WriteCheckpoint loadWriteCheckpoint(Path file) throws IOException {
    if (file == null || !Files.isRegularFile(file) || Files.size(file) == 0) {
      return new WriteCheckpoint();
    }
    WriteCheckpoint ck = MAPPER.readValue(file.toFile(), WriteCheckpoint.class);
    return ck == null ? new WriteCheckpoint() : ck;
  }

  private static void saveWriteCheckpoint(Path file, int total, int done, int batch,
                                          long points, String phase, String note)
      throws IOException {
    Path parent = file.getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
    WriteCheckpoint ck = new WriteCheckpoint();
    ck.phase = phase;
    ck.done = done;
    ck.total = total;
    ck.batch_size = batch;
    ck.points = points;
    ck.note = note;
    Path tmp = file.resolveSibling(file.getFileName().toString() + ".tmp");
    MAPPER.writeValue(tmp.toFile(), ck);
    try {
      Files.move(tmp, file,
          java.nio.file.StandardCopyOption.REPLACE_EXISTING,
          java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    } catch (IOException moveEx) {
      Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }
    System.out.println("WRITE_CHECKPOINT saved done=" + done + "/" + total
        + " phase=" + phase + " path=" + file);
    System.out.flush();
  }

  private static void deleteQuietly(Path file) {
    if (file == null) {
      return;
    }
    try {
      Files.deleteIfExists(file);
    } catch (IOException ignored) {
      // best-effort cleanup after READY
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
    PortoParser porto = "porto".equals(format) ? new PortoParser() : null;
    if (tdrive == null && ais == null && porto == null) {
      result.failureReason = "unsupported format: " + opt.format + " (use tdrive|ais|porto)";
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
            } else if (porto != null) {
              raws.add(porto.parseLine(line));
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
    if ("porto".equals(format)) {
      List<Path> out = new ArrayList<Path>();
      Path clean = dir.resolve("porto_clean.csv");
      if (Files.isRegularFile(clean)) {
        out.add(clean);
        return out;
      }
      Path raw = dir.resolve("porto.csv");
      if (Files.isRegularFile(raw)) {
        out.add(raw);
        return out;
      }
      try (java.nio.file.DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.csv")) {
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
    layout.tableXz = "idx_xz_" + p;
    layout.tableTshape = "idx_tshape_" + p;
    layout.tableXzt = "idx_xzt_" + p;
  }

  /**
   * {@code --no-resume} must wipe prior HBase rows: ensureTables alone leaves orphan
   * raw chunks under reused (shard,tid) keys (x100 false-positive oracle class).
   */
  private static void truncateIfNoResume(Options opt, HBaseBackend hbase,
                                         Map<String, String> tables) throws Exception {
    if (opt.resume || hbase == null || tables == null || tables.isEmpty()) {
      return;
    }
    System.out.println("TRUNCATE_ON_NO_RESUME tables=" + tables.size());
    System.out.flush();
    hbase.truncateTables(tables.values());
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
    m.layout.xz_level = opt.xzLevel;
    m.layout.tshape_max_depth = opt.tshapeMaxDepth;
    m.layout.xzt_level = opt.xztLevel;
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
    m.layout.tables.put("xz", names.tableXz);
    m.layout.tables.put("tshape", names.tableTshape);
    m.layout.tables.put("xzt", names.tableXzt);
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
