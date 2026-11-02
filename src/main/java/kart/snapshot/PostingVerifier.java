package kart.snapshot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import kart.codec.RowKeyCodec;
import kart.data.Chunk;
import kart.data.Chunker;
import kart.data.Trajectory;
import kart.exec.KvBackend;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Full posting verification: recompute expected keys per chunk and optionally scan indexes for extras.
 *
 * <p>Large snapshots (e.g. Porto) must use {@link Options#checkpointed(Path)}: missing-keys are
 * checked in batches with a resume file, and the full expected-key {@link HashSet} is never held
 * (that path OOMed at ~180M Z-order keys). Extra-key scan stays available for small fixtures via
 * {@link Options#memoryFull()}.
 */
public final class PostingVerifier {

  private static final ObjectMapper MAPPER = new ObjectMapper()
      .enable(SerializationFeature.INDENT_OUTPUT);

  public static final class Finding {
    public final String table;
    public final long tid;
    public final int chunkId;
    public final String detail;

    public Finding(String table, long tid, int chunkId, String detail) {
      this.table = table;
      this.tid = tid;
      this.chunkId = chunkId;
      this.detail = detail;
    }

    @Override
    public String toString() {
      return table + " tid=" + tid + " chunk=" + chunkId + ": " + detail;
    }
  }

  public static final class Report {
    public final List<Finding> missing = new ArrayList<Finding>();
    public final List<Finding> extra = new ArrayList<Finding>();

    public boolean ok() {
      return missing.isEmpty() && extra.isEmpty();
    }
  }

  /** Verify knobs. Default {@link #memoryFull()} matches historical in-memory behavior. */
  public static final class Options {
    public Path checkpointFile;
    public int batchSize = 2000;
    public boolean checkExtra = true;
    public boolean resume = true;
    /** Cap persisted missing samples in the checkpoint (full report still in memory for this run). */
    public int maxCheckpointMissing = 200;
    /**
     * When {@code > 0}, only verify this many trajectories (seeded sample). {@code 0} = full.
     * Prior consecutive {@link Checkpoint#done} from a full scan still counts as covered.
     */
    public int sampleSize = 0;
    /** If {@link #sampleSize} is 0 and this is {@code > 0}, sample size = ceil(n * frac). */
    public double sampleFrac = 0;
    public long sampleSeed = 20261027L;

    public static Options memoryFull() {
      Options o = new Options();
      o.checkExtra = true;
      return o;
    }

    /** Batched missing-key verify with resume; skips extra scan to avoid OOM on large indexes. */
    public static Options checkpointed(Path checkpointFile) {
      Options o = new Options();
      o.checkpointFile = checkpointFile;
      o.checkExtra = false;
      o.resume = true;
      o.batchSize = 2000;
      return o;
    }

    public boolean sampleEnabled() {
      return sampleSize > 0 || sampleFrac > 0;
    }
  }

  /** On-disk resume state (Jackson-friendly fields). */
  public static final class Checkpoint {
    public String phase = "get";
    /** Consecutive full-scan prefix verified (0..done-1). Preserved under sample mode. */
    public int done;
    public int total;
    public int missing_count;
    public int batch_size;
    public boolean check_extra;
    public List<CheckpointFinding> missing_samples = new ArrayList<CheckpointFinding>();
    public String note;
    /** {@code full} or {@code sample}. */
    public String verify_mode = "full";
    public int sample_size;
    public long sample_seed;
    /** How many entries of the remaining sample list (indices &gt;= done) have been checked. */
    public int sample_cursor;
  }

  public static final class CheckpointFinding {
    public String table;
    public long tid;
    public int chunkId;
    public String detail;
  }

  private final IndexBuilders.LayoutParams layout;
  private final IndexBuilders builders;
  private final Chunker chunker;

  public PostingVerifier(IndexBuilders.LayoutParams layout) {
    this.layout = layout;
    this.builders = new IndexBuilders(layout);
    this.chunker = new Chunker(256);
  }

  public Report verify(KvBackend kv, List<Trajectory> trajectories) throws IOException {
    return verify(kv, trajectories, Options.memoryFull());
  }

  public Report verify(KvBackend kv, List<Trajectory> trajectories, Options opt) throws IOException {
    if (opt == null) {
      opt = Options.memoryFull();
    }
    if (opt.checkpointFile != null) {
      return verifyCheckpointed(kv, trajectories, opt);
    }
    return verifyInMemory(kv, trajectories, opt.checkExtra);
  }

  private Report verifyCheckpointed(KvBackend kv, List<Trajectory> trajectories, Options opt)
      throws IOException {
    int n = trajectories.size();
    int batch = Math.max(1, opt.batchSize);
    Checkpoint ck = loadCheckpoint(opt.checkpointFile, opt.resume);
    if (ck.total > 0 && ck.total != n) {
      throw new IllegalStateException("checkpoint total=" + ck.total + " but trajectories=" + n
          + " (" + opt.checkpointFile + ")");
    }
    Report report = new Report();
    restoreMissing(ck, report);
    int priorDone = Math.max(0, Math.min(ck.done, n));
    if (priorDone > 0) {
      System.out.println("VERIFY_RESUME done=" + priorDone + "/" + n
          + " missing=" + report.missing.size()
          + " sample_cursor=" + ck.sample_cursor
          + " checkpoint=" + opt.checkpointFile);
      System.out.flush();
    }
    // Sample or full completion both set phase=done (sample may leave done < n).
    if ("done".equals(ck.phase)) {
      System.out.println("VERIFY_PHASE=already_done missing=" + report.missing.size()
          + " extra=" + report.extra.size()
          + " note=" + ck.note);
      System.out.flush();
      return report;
    }

    if (opt.sampleEnabled()) {
      return verifyCheckpointedSample(kv, trajectories, opt, ck, report, priorDone, batch);
    }

    long t0 = System.currentTimeMillis();
    System.out.println("VERIFY_PHASE=get_expected_keys trajectories=" + n
        + " batch=" + batch + " check_extra=" + opt.checkExtra
        + " mode=checkpoint");
    System.out.flush();

    for (int i = priorDone; i < n; i++) {
      verifyOneTrajectory(kv, trajectories.get(i), report);
      int done = i + 1;
      if (done == 1 || done % 200 == 0 || done == n || done % batch == 0) {
        long elapsed = System.currentTimeMillis() - t0;
        int progressed = done - priorDone;
        double rate = progressed / Math.max(0.001, elapsed / 1000.0);
        long etaSec = (long) ((n - done) / Math.max(0.001, rate));
        System.out.println("VERIFY_PROGRESS phase=get done=" + done + "/" + n
            + " missing=" + report.missing.size()
            + " elapsed_s=" + (elapsed / 1000)
            + " rate_traj_s=" + String.format("%.2f", rate)
            + " eta_s=" + etaSec);
        System.out.flush();
      }
      if (done % batch == 0 || done == n) {
        saveCheckpoint(opt, n, done, done >= n ? "done" : "get", report,
            done >= n
                ? "missing_ok_batches; extra_scan_skipped_to_avoid_OOM"
                : "in_progress",
            "full", 0, 0L, 0);
      }
    }

    if (opt.checkExtra) {
      System.out.println("VERIFY_WARN check_extra=true with checkpoint still skips in-memory extra scan"
          + " (use memoryFull for small fixtures)");
      System.out.flush();
    }
    System.out.println("VERIFY_PHASE=done missing=" + report.missing.size()
        + " extra=" + report.extra.size()
        + " elapsed_s=" + ((System.currentTimeMillis() - t0) / 1000));
    System.out.flush();
    return report;
  }

  private Report verifyCheckpointedSample(
      KvBackend kv, List<Trajectory> trajectories, Options opt, Checkpoint ck, Report report,
      int priorDone, int batch) throws IOException {
    int n = trajectories.size();
    int k = resolveSampleSize(n, opt);
    List<Integer> sample = sampleIndices(n, k, opt.sampleSeed);
    List<Integer> need = new ArrayList<Integer>();
    for (int idx : sample) {
      if (idx >= priorDone) {
        need.add(idx);
      }
    }
    int cursor = Math.max(0, Math.min(ck.sample_cursor, need.size()));
    System.out.println("VERIFY_MODE=sample size=" + k + " seed=" + opt.sampleSeed
        + " prior_full_done=" + priorDone + "/" + n
        + " sample_covered_by_prefix=" + (k - need.size())
        + " remaining=" + (need.size() - cursor)
        + " check_extra=" + opt.checkExtra);
    System.out.flush();

    long t0 = System.currentTimeMillis();
    for (int c = cursor; c < need.size(); c++) {
      int idx = need.get(c);
      verifyOneTrajectory(kv, trajectories.get(idx), report);
      int sampleDone = c + 1;
      if (sampleDone == 1 || sampleDone % 200 == 0 || sampleDone == need.size()
          || sampleDone % batch == 0) {
        long elapsed = System.currentTimeMillis() - t0;
        int progressed = sampleDone - cursor;
        double rate = progressed / Math.max(0.001, elapsed / 1000.0);
        long etaSec = (long) ((need.size() - sampleDone) / Math.max(0.001, rate));
        System.out.println("VERIFY_PROGRESS phase=sample done=" + sampleDone + "/" + need.size()
            + " traj_idx=" + idx
            + " missing=" + report.missing.size()
            + " elapsed_s=" + (elapsed / 1000)
            + " rate_traj_s=" + String.format("%.2f", rate)
            + " eta_s=" + etaSec);
        System.out.flush();
      }
      if (sampleDone % batch == 0 || sampleDone == need.size()) {
        boolean finished = sampleDone >= need.size();
        String note = finished
            ? ("sample_ok; prior_full_prefix=" + priorDone
                + "; sample_size=" + k + "; sample_checked=" + k
                + "; extra_scan_skipped_to_avoid_OOM")
            : "sample_in_progress";
        // Keep priorDone: do not claim a larger consecutive full-scan prefix.
        saveCheckpoint(opt, n, priorDone, finished ? "done" : "sample", report, note,
            "sample", k, opt.sampleSeed, sampleDone);
      }
    }

    if (need.isEmpty()) {
      String note = "sample_ok; prior_full_prefix=" + priorDone
          + "; sample_size=" + k + "; sample_checked=" + k
          + "; all_sample_covered_by_prefix; extra_scan_skipped_to_avoid_OOM";
      saveCheckpoint(opt, n, priorDone, "done", report, note, "sample", k, opt.sampleSeed, 0);
    }

    System.out.println("VERIFY_PHASE=done mode=sample missing=" + report.missing.size()
        + " extra=" + report.extra.size()
        + " elapsed_s=" + ((System.currentTimeMillis() - t0) / 1000));
    System.out.flush();
    return report;
  }

  private void verifyOneTrajectory(KvBackend kv, Trajectory t, Report report) throws IOException {
    List<Chunk> chunks = chunker.chunk(t);
    for (Chunk c : chunks) {
      IndexBuilders.ExpectedPostings exp = builders.expectedForChunk(t, c);
      checkMissing(kv, layout.tableTime, exp.timeKeys, t.tid, c.chunkId, report);
      checkMissing(kv, layout.tableZorder, exp.zorderKeys, t.tid, c.chunkId, report);
      checkMissing(kv, layout.tableHash, exp.hashKeys, t.tid, c.chunkId, report);
      checkMissing(kv, layout.tableXz, exp.xzKeys, t.tid, c.chunkId, report);
      checkMissing(kv, layout.tableTshape, exp.tshapeKeys, t.tid, c.chunkId, report);
      checkMissing(kv, layout.tableXzt, exp.xztKeys, t.tid, c.chunkId, report);
    }
    // Orphan raw rows under reused (shard,tid) survive ensureTables-only rebuilds and
    // poison FullScan/P_T while ADV postings stay clean — fail sample verify on extras.
    checkNoOrphanRawChunks(kv, t.tid, chunks.size(), report);
  }

  private void checkNoOrphanRawChunks(KvBackend kv, long tid, int expectedChunks, Report report)
      throws IOException {
    int shard = RowKeyCodec.shardOf(tid, layout.shardCount) & 0xFF;
    byte[] start = RowKeyCodec.encodeRaw(shard, tid, 0);
    byte[] stop = RowKeyCodec.encodeRaw(shard, tid + 1L, 0);
    List<KvBackend.Row> rows = kv.scan(layout.tableRaw, start, stop, Collections.<String>emptyList());
    int n = rows == null ? 0 : rows.size();
    if (n > expectedChunks) {
      report.extra.add(new Finding(layout.tableRaw, tid, -1,
          "orphan_raw_chunks actual=" + n + " expected=" + expectedChunks));
    } else if (n < expectedChunks) {
      report.missing.add(new Finding(layout.tableRaw, tid, -1,
          "raw_chunks actual=" + n + " expected=" + expectedChunks));
    }
  }

  static int resolveSampleSize(int n, Options opt) {
    if (n <= 0) {
      return 0;
    }
    int k;
    if (opt.sampleSize > 0) {
      k = opt.sampleSize;
    } else {
      k = (int) Math.ceil(n * opt.sampleFrac);
    }
    if (k < 1) {
      k = 1;
    }
    return Math.min(k, n);
  }

  /** Deterministic without-replacement sample of trajectory indices in [0, n). */
  static List<Integer> sampleIndices(int n, int k, long seed) {
    List<Integer> all = new ArrayList<Integer>(n);
    for (int i = 0; i < n; i++) {
      all.add(i);
    }
    Collections.shuffle(all, new Random(seed));
    List<Integer> out = new ArrayList<Integer>(k);
    for (int i = 0; i < k; i++) {
      out.add(all.get(i));
    }
    Collections.sort(out);
    return out;
  }

  private Report verifyInMemory(KvBackend kv, List<Trajectory> trajectories, boolean checkExtra)
      throws IOException {
    Report report = new Report();
    Set<String> expectedTime = new HashSet<String>();
    Set<String> expectedZ = new HashSet<String>();
    Set<String> expectedHash = new HashSet<String>();
    Set<String> expectedXz = new HashSet<String>();
    Set<String> expectedTs = new HashSet<String>();
    Set<String> expectedXzt = new HashSet<String>();

    int n = trajectories.size();
    long t0 = System.currentTimeMillis();
    System.out.println("VERIFY_PHASE=get_expected_keys trajectories=" + n
        + " check_extra=" + checkExtra + " mode=memory");
    System.out.flush();
    int i = 0;
    for (Trajectory t : trajectories) {
      i++;
      List<Chunk> chunks = chunker.chunk(t);
      for (Chunk c : chunks) {
        IndexBuilders.ExpectedPostings exp = builders.expectedForChunk(t, c);
        if (checkExtra) {
          expectedTime.addAll(exp.timeKeys);
          expectedZ.addAll(exp.zorderKeys);
          expectedHash.addAll(exp.hashKeys);
          expectedXz.addAll(exp.xzKeys);
          expectedTs.addAll(exp.tshapeKeys);
          expectedXzt.addAll(exp.xztKeys);
        }
        checkMissing(kv, layout.tableTime, exp.timeKeys, t.tid, c.chunkId, report);
        checkMissing(kv, layout.tableZorder, exp.zorderKeys, t.tid, c.chunkId, report);
        checkMissing(kv, layout.tableHash, exp.hashKeys, t.tid, c.chunkId, report);
        checkMissing(kv, layout.tableXz, exp.xzKeys, t.tid, c.chunkId, report);
        checkMissing(kv, layout.tableTshape, exp.tshapeKeys, t.tid, c.chunkId, report);
        checkMissing(kv, layout.tableXzt, exp.xztKeys, t.tid, c.chunkId, report);
      }
      if (i == 1 || i % 200 == 0 || i == n) {
        long elapsed = System.currentTimeMillis() - t0;
        double rate = i / Math.max(0.001, elapsed / 1000.0);
        long etaSec = (long) ((n - i) / Math.max(0.001, rate));
        System.out.println("VERIFY_PROGRESS phase=get done=" + i + "/" + n
            + " missing=" + report.missing.size()
            + " expected_keys=" + (expectedTime.size() + expectedZ.size() + expectedHash.size()
            + expectedXz.size() + expectedTs.size() + expectedXzt.size())
            + " elapsed_s=" + (elapsed / 1000)
            + " rate_traj_s=" + String.format("%.2f", rate)
            + " eta_s=" + etaSec);
        System.out.flush();
      }
    }

    if (checkExtra) {
      System.out.println("VERIFY_PHASE=scan_extra time_keys=" + expectedTime.size()
          + " z_keys=" + expectedZ.size() + " hash_keys=" + expectedHash.size()
          + " xz_keys=" + expectedXz.size() + " tshape_keys=" + expectedTs.size()
          + " xzt_keys=" + expectedXzt.size());
      System.out.flush();
      scanForExtra(kv, layout.tableTime, expectedTime, report);
      scanForExtra(kv, layout.tableZorder, expectedZ, report);
      scanForExtra(kv, layout.tableHash, expectedHash, report);
      scanForExtra(kv, layout.tableXz, expectedXz, report);
      scanForExtra(kv, layout.tableTshape, expectedTs, report);
      scanForExtra(kv, layout.tableXzt, expectedXzt, report);
    } else {
      System.out.println("VERIFY_PHASE=scan_extra skipped");
      System.out.flush();
    }
    System.out.println("VERIFY_PHASE=done missing=" + report.missing.size()
        + " extra=" + report.extra.size()
        + " elapsed_s=" + ((System.currentTimeMillis() - t0) / 1000));
    System.out.flush();
    return report;
  }

  private Checkpoint loadCheckpoint(Path file, boolean resume) throws IOException {
    if (!resume || file == null || !Files.isRegularFile(file) || Files.size(file) == 0) {
      Checkpoint ck = new Checkpoint();
      ck.phase = "get";
      ck.done = 0;
      return ck;
    }
    Checkpoint ck = MAPPER.readValue(file.toFile(), Checkpoint.class);
    if (ck == null) {
      ck = new Checkpoint();
    }
    if (ck.missing_samples == null) {
      ck.missing_samples = new ArrayList<CheckpointFinding>();
    }
    return ck;
  }

  private void restoreMissing(Checkpoint ck, Report report) {
    if (ck.missing_samples == null) {
      return;
    }
    for (CheckpointFinding f : ck.missing_samples) {
      if (f == null) {
        continue;
      }
      report.missing.add(new Finding(f.table, f.tid, f.chunkId, f.detail));
    }
  }

  private void saveCheckpoint(Options opt, int total, int done, String phase, Report report,
                              String note, String verifyMode, int sampleSize, long sampleSeed,
                              int sampleCursor) throws IOException {
    if (opt.checkpointFile == null) {
      return;
    }
    Path parent = opt.checkpointFile.getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
    Checkpoint ck = new Checkpoint();
    ck.phase = phase;
    ck.done = done;
    ck.total = total;
    ck.missing_count = report.missing.size();
    ck.batch_size = opt.batchSize;
    ck.check_extra = opt.checkExtra;
    ck.note = note;
    ck.verify_mode = verifyMode == null ? "full" : verifyMode;
    ck.sample_size = sampleSize;
    ck.sample_seed = sampleSeed;
    ck.sample_cursor = sampleCursor;
    int lim = Math.max(0, opt.maxCheckpointMissing);
    for (int i = 0; i < report.missing.size() && i < lim; i++) {
      Finding f = report.missing.get(i);
      CheckpointFinding cf = new CheckpointFinding();
      cf.table = f.table;
      cf.tid = f.tid;
      cf.chunkId = f.chunkId;
      cf.detail = f.detail;
      ck.missing_samples.add(cf);
    }
    Path tmp = opt.checkpointFile.resolveSibling(opt.checkpointFile.getFileName() + ".tmp");
    MAPPER.writeValue(tmp.toFile(), ck);
    try {
      Files.move(tmp, opt.checkpointFile,
          java.nio.file.StandardCopyOption.REPLACE_EXISTING,
          java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    } catch (IOException moveEx) {
      Files.move(tmp, opt.checkpointFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }
    System.out.println("VERIFY_CHECKPOINT saved done=" + done + "/" + total
        + " phase=" + phase + " mode=" + ck.verify_mode
        + " sample_cursor=" + sampleCursor + " path=" + opt.checkpointFile);
    System.out.flush();
  }

  private void scanForExtra(KvBackend kv, String table, Set<String> expected, Report report)
      throws IOException {
    final int[] extras = new int[]{0};
    final long[] scanned = new long[]{0};
    final long t0 = System.currentTimeMillis();
    final int maxReport = 100;
    System.out.println("VERIFY_PROGRESS phase=scan_extra table=" + table + " expected=" + expected.size());
    System.out.flush();
    kv.scanConsume(table, null, null, Collections.<String>emptyList(), new KvBackend.RowConsumer() {
      @Override
      public void accept(KvBackend.Row row) {
        scanned[0]++;
        if (scanned[0] % 500000 == 0) {
          long elapsed = System.currentTimeMillis() - t0;
          System.out.println("VERIFY_PROGRESS phase=scan_extra table=" + table
              + " scanned=" + scanned[0] + " extras=" + extras[0]
              + " elapsed_s=" + (elapsed / 1000));
          System.out.flush();
        }
        String hex = IndexBuilders.hex(row.key);
        if (!expected.contains(hex)) {
          extras[0]++;
          if (report.extra.size() < maxReport) {
            long tid = -1;
            int chunk = -1;
            try {
              if (table.equals(layout.tableTime)) {
                RowKeyCodec.TimeKey k = RowKeyCodec.decodeTime(row.key);
                tid = k.tid;
                chunk = (int) k.chunkId;
              } else if (table.equals(layout.tableZorder)) {
                RowKeyCodec.ZorderKey k = RowKeyCodec.decodeZorder(row.key);
                tid = k.tid;
                chunk = (int) k.chunkId;
              } else if (table.equals(layout.tableHash)) {
                RowKeyCodec.HashKey k = RowKeyCodec.decodeHash(row.key);
                tid = k.tid;
                chunk = (int) k.chunkId;
              } else if (table.equals(layout.tableXz) || table.equals(layout.tableXzt)) {
                RowKeyCodec.XzKey k = RowKeyCodec.decodeXz(row.key);
                tid = k.tid;
                chunk = (int) k.chunkId;
              } else if (table.equals(layout.tableTshape)) {
                RowKeyCodec.TShapeKey k = RowKeyCodec.decodeTShape(row.key);
                tid = k.tid;
                chunk = (int) k.chunkId;
              }
            } catch (RuntimeException ignored) {
              // keep -1
            }
            String detail = "extra " + hex;
            if (extras[0] >= maxReport) {
              detail = detail + " (further extras truncated in report)";
            }
            report.extra.add(new Finding(table, tid, chunk, detail));
          }
        }
      }
    });
    System.out.println("VERIFY_PROGRESS phase=scan_extra_done table=" + table
        + " scanned=" + scanned[0] + " extras=" + extras[0]
        + " elapsed_s=" + ((System.currentTimeMillis() - t0) / 1000));
    System.out.flush();
    if (extras[0] > maxReport) {
      report.extra.add(new Finding(table, -1, -1,
          "extra_total=" + extras[0] + " (only first " + maxReport + " listed)"));
    }
  }

  private void checkMissing(KvBackend kv, String table, Set<String> keys, long tid, int chunkId,
                            Report report) throws IOException {
    for (String hex : keys) {
      if (kv.get(table, unhex(hex)).isEmpty()) {
        report.missing.add(new Finding(table, tid, chunkId, "missing " + hex));
      }
    }
  }

  private static byte[] unhex(String hex) {
    int n = hex.length();
    byte[] out = new byte[n / 2];
    for (int i = 0; i < out.length; i++) {
      out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
    }
    return out;
  }
}
