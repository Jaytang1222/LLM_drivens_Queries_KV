package kart.snapshot;

import kart.catalog.Manifest;
import kart.data.CanonicalPoint;
import kart.data.Chunk;
import kart.data.Trajectory;
import kart.exec.KvBackend;
import kart.exec.MemoryBackend;
import kart.codec.RowKeyCodec;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.Calendar;

/**
 * Deterministic fixture R/A/B/C from IMPLEMENTATION_PLAN.md §18.
 * Coordinates are meters directly; time starts 2008-02-02 08:00 +08:00.
 */
public final class FixtureBuilder {

  public static final String MANIFEST_ID = "fixture_v1_ready";
  /**
   * Dedicated HBase table names so fixture never overwrites T-Drive ({@code traj_*_v1}).
   * MemoryBackend uses the same names for layout parity.
   */
  public static final String TABLE_RAW = "fixture_traj_raw_v1";
  public static final String TABLE_META = "fixture_traj_meta_v1";
  public static final String TABLE_TIME = "fixture_idx_time_v1";
  public static final String TABLE_ZORDER = "fixture_idx_zorder_v1";
  public static final String TABLE_HASH = "fixture_idx_hash_v1";
  public static final int SHARD_COUNT = 4;

  /** 2008-02-02 08:00:00 Asia/Shanghai as epoch millis. */
  public static final long T0 = shanghai(2008, 2, 2, 8, 0, 0);
  public static final long FIVE_MIN = 5 * 60 * 1000L;

  private FixtureBuilder() {}

  public static long shanghai(int year, int month, int day, int hour, int minute, int second) {
    Calendar c = Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai"));
    c.clear();
    c.set(year, month - 1, day, hour, minute, second);
    return c.getTimeInMillis();
  }

  public static List<Trajectory> trajectories() {
    List<Trajectory> list = new ArrayList<Trajectory>();
    // tid assigned by trajectory_id lexicographic order: A, B, C, R → 1,2,3,4
    list.add(traj("A", "A", 1L, pts(
        p("A", "A", 1, 0, T0, 1, 1),
        p("A", "A", 1, 1, T0 + FIVE_MIN, 5, 5),
        p("A", "A", 1, 2, T0 + 2 * FIVE_MIN, 9, 9)
    )));
    list.add(traj("B", "B", 2L, pts(
        p("B", "B", 2, 0, T0, 20, 20),
        p("B", "B", 2, 1, T0 + FIVE_MIN, 6, 6),
        p("B", "B", 2, 2, T0 + 2 * FIVE_MIN, 20, 20)
    )));
    list.add(traj("C", "C", 3L, pts(
        p("C", "C", 3, 0, T0, 30, 30),
        p("C", "C", 3, 1, T0 + FIVE_MIN, 31, 31)
    )));
    list.add(traj("R", "R", 4L, pts(
        p("R", "R", 4, 0, T0, 1, 1),
        p("R", "R", 4, 1, T0 + FIVE_MIN, 2, 2),
        p("R", "R", 4, 2, T0 + 2 * FIVE_MIN, 3, 3)
    )));
    return list;
  }

  public static void build(Path outDir) throws Exception {
    Files.createDirectories(outDir);
    List<Trajectory> trajs = trajectories();

    StringBuilder pointsTxt = new StringBuilder();
    pointsTxt.append("# fixture-v1 points: trajectory_id tid seq t_ms x y\n");
    for (Trajectory t : trajs) {
      for (CanonicalPoint p : t.points()) {
        pointsTxt.append(p.trajectoryId).append(' ').append(p.tid).append(' ')
            .append(p.seq).append(' ').append(p.timestampMs).append(' ')
            .append(fmt(p.xM)).append(' ').append(fmt(p.yM)).append('\n');
      }
    }
    Path pointsPath = outDir.resolve("points.txt");
    Files.write(pointsPath, pointsTxt.toString().getBytes(StandardCharsets.UTF_8));

    MemoryBackend kv = new MemoryBackend();
    loadInto(kv, trajs);

    Manifest m = fixtureManifest();
    m.writeTo(outDir.resolve("manifest.json"));

    // checksum of points.txt for determinism check
    String checksum = sha256(Files.readAllBytes(pointsPath));
    Files.write(outDir.resolve("checksum.sha256"),
        (checksum + "\n").getBytes(StandardCharsets.UTF_8));

    // dump raw keys for debugging
    StringBuilder dump = new StringBuilder();
    for (KvBackend.Row row : kv.scan(TABLE_RAW, null, null, null)) {
      RowKeyCodec.RawKey rk = RowKeyCodec.decodeRaw(row.key);
      dump.append("raw shard=").append(rk.shard).append(" tid=").append(rk.tid)
          .append(" chunk=").append(rk.chunkId).append('\n');
    }
    Files.write(outDir.resolve("raw-keys.txt"), dump.toString().getBytes(StandardCharsets.UTF_8));
    kv.close();
  }

  public static Manifest fixtureManifest() {
    Manifest m = new Manifest();
    m.manifest_id = MANIFEST_ID;
    m.status = Manifest.Status.READY;
    m.semantics_version = "point_dtw_v1";
    m.stats_version = "stats_v1";
    m.tid_map_location = TABLE_META;
    m.dataset = new Manifest.Dataset();
    m.dataset.source = "testdata/fixture-v1";
    m.dataset.checksum = "deterministic";
    m.dataset.assumptions = new String[]{"A1", "A2", "A3"};
    m.layout = new Manifest.Layout();
    m.layout.shard_count = SHARD_COUNT;
    m.layout.chunk_max_points = 256;
    m.layout.bucket_ms = 600_000L;
    m.layout.epoch_ms = T0;
    m.layout.zorder_level = 8;
    m.layout.crs = "EPSG:32650";
    m.layout.hash_version = "murmur3_x64_128";
    m.layout.domain = new Manifest.Domain();
    m.layout.domain.xmin = 0;
    m.layout.domain.xmax = 100;
    m.layout.domain.ymin = 0;
    m.layout.domain.ymax = 100;
    m.layout.tables.put("raw", TABLE_RAW);
    m.layout.tables.put("meta", TABLE_META);
    m.layout.tables.put("time", TABLE_TIME);
    m.layout.tables.put("zorder", TABLE_ZORDER);
    m.layout.tables.put("hash", TABLE_HASH);
    m.build_report = new Manifest.BuildReport();
    m.build_report.trajectories = 4;
    m.build_report.points = 11;
    return m;
  }

  /** Layout for IndexBuilders / HBase ensureTables (isolated from T-Drive). */
  public static IndexBuilders.LayoutParams layoutParams() {
    IndexBuilders.LayoutParams lp = new IndexBuilders.LayoutParams();
    lp.shardCount = SHARD_COUNT;
    lp.bucketMs = 600_000L;
    lp.epochMs = T0;
    lp.zorderLevel = 8;
    lp.domain = new kart.geo.Rect(0, 0, 100, 100);
    lp.tableRaw = TABLE_RAW;
    lp.tableMeta = TABLE_META;
    lp.tableTime = TABLE_TIME;
    lp.tableZorder = TABLE_ZORDER;
    lp.tableHash = TABLE_HASH;
    return lp;
  }

  public static java.util.Map<String, String> tableNameMap() {
    java.util.Map<String, String> tables = new java.util.HashMap<String, String>();
    tables.put("raw", TABLE_RAW);
    tables.put("meta", TABLE_META);
    tables.put("time", TABLE_TIME);
    tables.put("zorder", TABLE_ZORDER);
    tables.put("hash", TABLE_HASH);
    return tables;
  }

  public static void loadInto(MemoryBackend kv, List<Trajectory> trajs) throws IOException {
    for (Trajectory t : trajs) {
      List<Chunk> chunks = chunkify(t, 256);
      int shard = RowKeyCodec.shardOf(t.tid, SHARD_COUNT) & 0xFF;
      Map<String, byte[]> metaCols = new HashMap<String, byte[]>();
      metaCols.put("d:ext", t.trajectoryId.getBytes(StandardCharsets.UTF_8));
      metaCols.put("d:veh", t.vehicleId.getBytes(StandardCharsets.UTF_8));
      metaCols.put("d:cc", intBytes(chunks.size()));
      metaCols.put("d:pc", intBytes(t.size()));
      kv.put(TABLE_META, RowKeyCodec.encodeMeta(shard, t.tid), metaCols);
      for (Chunk c : chunks) {
        Map<String, byte[]> cols = new HashMap<String, byte[]>();
        cols.put("d:p", c.encodePayload());
        cols.put("d:m", c.encodeMeta());
        kv.put(TABLE_RAW, RowKeyCodec.encodeRaw(shard, t.tid, c.chunkId), cols);
      }
    }
  }

  public static List<Chunk> chunkify(Trajectory t, int maxPoints) {
    List<Chunk> out = new ArrayList<Chunk>();
    List<CanonicalPoint> buf = new ArrayList<CanonicalPoint>();
    int chunkId = 0;
    for (CanonicalPoint p : t.points()) {
      buf.add(p);
      if (buf.size() >= maxPoints) {
        out.add(new Chunk(t.tid, chunkId++, new ArrayList<CanonicalPoint>(buf)));
        buf.clear();
      }
    }
    if (!buf.isEmpty()) {
      out.add(new Chunk(t.tid, chunkId, buf));
    }
    return out;
  }

  private static CanonicalPoint p(String veh, String traj, long tid, int seq, long t, double x, double y) {
    return new CanonicalPoint(veh, traj, tid, seq, t, x, y);
  }

  private static List<CanonicalPoint> pts(CanonicalPoint... ps) {
    return Arrays.asList(ps);
  }

  private static Trajectory traj(String veh, String id, long tid, List<CanonicalPoint> pts) {
    return new Trajectory(veh, id, tid, pts);
  }

  private static String fmt(double d) {
    if (d == Math.rint(d)) {
      return Integer.toString((int) d);
    }
    return Double.toString(d);
  }

  private static byte[] intBytes(int v) {
    return java.nio.ByteBuffer.allocate(4).putInt(v).array();
  }

  private static String sha256(byte[] data) throws Exception {
    MessageDigest md = MessageDigest.getInstance("SHA-256");
    byte[] dig = md.digest(data);
    StringBuilder sb = new StringBuilder();
    for (byte b : dig) {
      sb.append(String.format("%02x", b));
    }
    return sb.toString();
  }
}
