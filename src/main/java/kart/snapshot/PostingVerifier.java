package kart.snapshot;

import kart.codec.RowKeyCodec;
import kart.data.CanonicalPoint;
import kart.data.Chunk;
import kart.data.Chunker;
import kart.data.Trajectory;
import kart.exec.KvBackend;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Full posting verification: recompute expected keys per chunk and scan indexes for extras.
 */
public final class PostingVerifier {

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

  private final IndexBuilders.LayoutParams layout;
  private final IndexBuilders builders;
  private final Chunker chunker;

  public PostingVerifier(IndexBuilders.LayoutParams layout) {
    this.layout = layout;
    this.builders = new IndexBuilders(layout);
    this.chunker = new Chunker(256);
  }

  public Report verify(KvBackend kv, List<Trajectory> trajectories) throws IOException {
    Report report = new Report();
    Set<String> expectedTime = new HashSet<String>();
    Set<String> expectedZ = new HashSet<String>();
    Set<String> expectedHash = new HashSet<String>();

    int n = trajectories.size();
    long t0 = System.currentTimeMillis();
    System.out.println("VERIFY_PHASE=get_expected_keys trajectories=" + n);
    System.out.flush();
    int i = 0;
    for (Trajectory t : trajectories) {
      i++;
      List<Chunk> chunks = chunker.chunk(t);
      for (Chunk c : chunks) {
        IndexBuilders.ExpectedPostings exp = builders.expectedForChunk(t, c);
        expectedTime.addAll(exp.timeKeys);
        expectedZ.addAll(exp.zorderKeys);
        expectedHash.addAll(exp.hashKeys);
        for (String hex : exp.timeKeys) {
          if (kv.get(layout.tableTime, unhex(hex)).isEmpty()) {
            report.missing.add(new Finding(layout.tableTime, t.tid, c.chunkId, "missing " + hex));
          }
        }
        for (String hex : exp.zorderKeys) {
          if (kv.get(layout.tableZorder, unhex(hex)).isEmpty()) {
            report.missing.add(new Finding(layout.tableZorder, t.tid, c.chunkId, "missing " + hex));
          }
        }
        for (String hex : exp.hashKeys) {
          if (kv.get(layout.tableHash, unhex(hex)).isEmpty()) {
            report.missing.add(new Finding(layout.tableHash, t.tid, c.chunkId, "missing " + hex));
          }
        }
      }
      if (i == 1 || i % 200 == 0 || i == n) {
        long elapsed = System.currentTimeMillis() - t0;
        double rate = i / Math.max(0.001, elapsed / 1000.0);
        long etaSec = (long) ((n - i) / Math.max(0.001, rate));
        System.out.println("VERIFY_PROGRESS phase=get done=" + i + "/" + n
            + " missing=" + report.missing.size()
            + " expected_keys=" + (expectedTime.size() + expectedZ.size() + expectedHash.size())
            + " elapsed_s=" + (elapsed / 1000)
            + " rate_traj_s=" + String.format("%.2f", rate)
            + " eta_s=" + etaSec);
        System.out.flush();
      }
    }

    System.out.println("VERIFY_PHASE=scan_extra time_keys=" + expectedTime.size()
        + " z_keys=" + expectedZ.size() + " hash_keys=" + expectedHash.size());
    System.out.flush();
    scanForExtra(kv, layout.tableTime, expectedTime, report);
    scanForExtra(kv, layout.tableZorder, expectedZ, report);
    scanForExtra(kv, layout.tableHash, expectedHash, report);
    System.out.println("VERIFY_PHASE=done missing=" + report.missing.size()
        + " extra=" + report.extra.size()
        + " elapsed_s=" + ((System.currentTimeMillis() - t0) / 1000));
    System.out.flush();
    return report;
  }

  private void scanForExtra(KvBackend kv, String table, Set<String> expected, Report report) throws IOException {
    // empty columns list → keys-only streaming scan (do not materialize whole table)
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
    // If truncated, leave a marker finding with count
    if (extras[0] > maxReport) {
      report.extra.add(new Finding(table, -1, -1,
          "extra_total=" + extras[0] + " (only first " + maxReport + " listed)"));
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
