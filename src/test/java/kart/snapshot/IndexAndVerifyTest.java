package kart.snapshot;

import kart.codec.RowKeyCodec;
import kart.data.Chunk;
import kart.data.Chunker;
import kart.data.Trajectory;
import kart.exec.MemoryBackend;
import kart.geo.Rect;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IndexAndVerifyTest {

  @Test
  void fixturePostingsMatchExpectedAndVerifyOk() throws Exception {
    MemoryBackend kv = SnapshotBuilder.buildFixtureInMemory();
    List<Trajectory> trajs = FixtureBuilder.trajectories();
    IndexBuilders.LayoutParams layout = new IndexBuilders.LayoutParams();
    layout.epochMs = FixtureBuilder.T0;
    layout.domain = new Rect(0, 0, 100, 100);
    IndexBuilders builders = new IndexBuilders(layout);
    Chunker chunker = new Chunker(256);

    for (Trajectory t : trajs) {
      for (Chunk c : chunker.chunk(t)) {
        IndexBuilders.ExpectedPostings exp = builders.expectedForChunk(t, c);
        for (String hex : exp.timeKeys) {
          assertFalse(kv.get(layout.tableTime, unhex(hex)).isEmpty(), "time " + hex);
        }
        for (String hex : exp.zorderKeys) {
          assertFalse(kv.get(layout.tableZorder, unhex(hex)).isEmpty(), "z " + hex);
        }
        for (String hex : exp.hashKeys) {
          assertFalse(kv.get(layout.tableHash, unhex(hex)).isEmpty(), "hash " + hex);
        }
      }
      // meta round-trip
      int shard = RowKeyCodec.shardOf(t.tid, 4) & 0xFF;
      Map<String, byte[]> meta = kv.get(layout.tableMeta, RowKeyCodec.encodeMeta(shard, t.tid));
      assertEquals(t.trajectoryId, new String(meta.get("d:ext"), StandardCharsets.UTF_8));
    }

    PostingVerifier.Report report = new PostingVerifier(layout).verify(kv, trajs);
    assertTrue(report.ok(), "missing=" + report.missing + " extra=" + report.extra);
    kv.close();
  }

  @Test
  void verifyDetectsMissingAndExtra() throws Exception {
    MemoryBackend kv = SnapshotBuilder.buildFixtureInMemory();
    List<Trajectory> trajs = FixtureBuilder.trajectories();
    IndexBuilders.LayoutParams layout = new IndexBuilders.LayoutParams();
    layout.epochMs = FixtureBuilder.T0;
    layout.domain = new Rect(0, 0, 100, 100);

    // delete one time posting
    Trajectory a = trajs.get(0);
    Chunk c0 = new Chunker(256).chunk(a).get(0);
    IndexBuilders.ExpectedPostings exp = new IndexBuilders(layout).expectedForChunk(a, c0);
    String victim = exp.timeKeys.iterator().next();
    // MemoryBackend has no delete — overwrite table by scanning and rebuilding without victim
    MemoryBackend kv2 = new MemoryBackend();
    copyAllExcept(kv, kv2, layout.tableTime, victim);
    copyAll(kv, kv2, layout.tableZorder);
    copyAll(kv, kv2, layout.tableHash);
    copyAll(kv, kv2, layout.tableRaw);
    copyAll(kv, kv2, layout.tableMeta);

    PostingVerifier.Report missing = new PostingVerifier(layout).verify(kv2, trajs);
    assertFalse(missing.ok());
    assertFalse(missing.missing.isEmpty());

    // inject extra
    MemoryBackend kv3 = SnapshotBuilder.buildFixtureInMemory();
    byte[] extraKey = RowKeyCodec.encodeTime(0, 999999L, 1L, 0);
    Map<String, byte[]> cols = new HashMap<String, byte[]>();
    cols.put("d:r", new byte[]{1});
    kv3.put(layout.tableTime, extraKey, cols);
    PostingVerifier.Report extra = new PostingVerifier(layout).verify(kv3, trajs);
    assertFalse(extra.extra.isEmpty());

    kv.close();
    kv2.close();
    kv3.close();
  }

  @Test
  void statsSampleRateReasonable() {
    List<Trajectory> trajs = FixtureBuilder.trajectories();
    IndexBuilders.LayoutParams layout = new IndexBuilders.LayoutParams();
    layout.epochMs = FixtureBuilder.T0;
    layout.domain = new Rect(0, 0, 100, 100);
    // expand with many synthetic chunks via repeated fixture-sized trajs is small;
    // just ensure builder runs
    kart.catalog.StatsSnapshot s = new StatsBuilder(layout).build("fixture_v1_ready", trajs);
    assertEquals("fixture_v1_ready", s.manifest_id);
    assertTrue(s.time_bucket_posting_counts.size() >= 1);
  }

  private static void copyAll(MemoryBackend from, MemoryBackend to, String table) throws Exception {
    for (kart.exec.KvBackend.Row r : from.scan(table, null, null, null)) {
      to.put(table, r.key, r.columns);
    }
  }

  private static void copyAllExcept(MemoryBackend from, MemoryBackend to, String table, String skipHex)
      throws Exception {
    for (kart.exec.KvBackend.Row r : from.scan(table, null, null, null)) {
      if (IndexBuilders.hex(r.key).equals(skipHex)) {
        continue;
      }
      to.put(table, r.key, r.columns);
    }
  }

  private static byte[] unhex(String hex) {
    byte[] out = new byte[hex.length() / 2];
    for (int i = 0; i < out.length; i++) {
      out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
    }
    return out;
  }
}
