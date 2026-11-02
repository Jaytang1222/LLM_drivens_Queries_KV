package kart.snapshot;

import kart.codec.RowKeyCodec;
import kart.data.Chunk;
import kart.data.Chunker;
import kart.data.Trajectory;
import kart.exec.MemoryBackend;
import kart.geo.Rect;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IndexAndVerifyTest {

  @Test
  void checkpointedVerifyResumesWithoutExtraScan() throws Exception {
    MemoryBackend kv = SnapshotBuilder.buildFixtureInMemory();
    List<Trajectory> trajs = FixtureBuilder.trajectories();
    IndexBuilders.LayoutParams layout = FixtureBuilder.layoutParams();
    Path ckpt = java.nio.file.Files.createTempFile("kart-verify", ".ckpt.json");
    try {
      PostingVerifier.Options opt = PostingVerifier.Options.checkpointed(ckpt);
      opt.batchSize = 1;
      PostingVerifier.Report first = new PostingVerifier(layout).verify(kv, trajs, opt);
      assertTrue(first.ok(), "missing=" + first.missing);
      assertTrue(java.nio.file.Files.isRegularFile(ckpt));
      PostingVerifier.Report second = new PostingVerifier(layout).verify(kv, trajs, opt);
      assertTrue(second.ok());
    } finally {
      java.nio.file.Files.deleteIfExists(ckpt);
      kv.close();
    }
  }

  @Test
  void sampleVerifyKeepsPriorFullPrefixAndCompletes() throws Exception {
    MemoryBackend kv = SnapshotBuilder.buildFixtureInMemory();
    List<Trajectory> trajs = FixtureBuilder.trajectories();
    IndexBuilders.LayoutParams layout = FixtureBuilder.layoutParams();
    Path ckpt = java.nio.file.Files.createTempFile("kart-verify-sample", ".ckpt.json");
    try {
      // Simulate a paused full verify that already covered a prefix.
      PostingVerifier.Checkpoint prior = new PostingVerifier.Checkpoint();
      prior.phase = "get";
      prior.done = Math.max(1, trajs.size() / 2);
      prior.total = trajs.size();
      prior.batch_size = 1;
      prior.check_extra = false;
      prior.note = "in_progress";
      new com.fasterxml.jackson.databind.ObjectMapper()
          .writerWithDefaultPrettyPrinter()
          .writeValue(ckpt.toFile(), prior);

      PostingVerifier.Options opt = PostingVerifier.Options.checkpointed(ckpt);
      opt.batchSize = 1;
      opt.sampleSize = Math.min(3, trajs.size());
      opt.sampleSeed = 42L;
      PostingVerifier.Report report = new PostingVerifier(layout).verify(kv, trajs, opt);
      assertTrue(report.ok(), "missing=" + report.missing);

      PostingVerifier.Checkpoint after =
          new com.fasterxml.jackson.databind.ObjectMapper()
              .readValue(ckpt.toFile(), PostingVerifier.Checkpoint.class);
      assertEquals("done", after.phase);
      assertEquals(prior.done, after.done, "must not clobber prior full-scan prefix");
      assertEquals("sample", after.verify_mode);
      assertTrue(after.note != null && after.note.contains("sample_ok"));

      // Resume after sample done must short-circuit.
      PostingVerifier.Report again = new PostingVerifier(layout).verify(kv, trajs, opt);
      assertTrue(again.ok());
    } finally {
      java.nio.file.Files.deleteIfExists(ckpt);
      kv.close();
    }
  }

  @Test
  void checkpointedWriteResumesFromDone() throws Exception {
    List<Trajectory> trajs = FixtureBuilder.trajectories();
    IndexBuilders.LayoutParams layout = FixtureBuilder.layoutParams();
    IndexBuilders builders = new IndexBuilders(layout);
    Chunker chunker = new Chunker(256);
    Path ckpt = java.nio.file.Files.createTempFile("kart-write", ".ckpt.json");
    MemoryBackend kv = new MemoryBackend();
    try {
      SnapshotBuilder.Options opt = new SnapshotBuilder.Options();
      opt.writeCheckpointFile = ckpt;
      opt.writeBatchSize = 2;
      opt.resume = true;
      long points = SnapshotBuilder.writeTrajectories(kv, null, trajs, builders, chunker, ckpt, opt);
      assertTrue(points > 0);
      assertTrue(java.nio.file.Files.isRegularFile(ckpt));
      SnapshotBuilder.WriteCheckpoint done =
          new com.fasterxml.jackson.databind.ObjectMapper()
              .readValue(ckpt.toFile(), SnapshotBuilder.WriteCheckpoint.class);
      assertEquals(trajs.size(), done.done);
      assertEquals("done", done.phase);

      // already_done short-circuit: empty kv must not receive puts
      MemoryBackend empty = new MemoryBackend();
      long skipped = SnapshotBuilder.writeTrajectories(
          empty, null, trajs, builders, chunker, ckpt, opt);
      assertEquals(points, skipped);
      empty.close();

      // Mid-resume: first 2 already in kv2, ckpt says done=2, finish the rest
      MemoryBackend kv2 = new MemoryBackend();
      SnapshotBuilder.writeTrajectories(
          kv2, null, trajs.subList(0, 2), builders, chunker, null, opt);
      SnapshotBuilder.WriteCheckpoint mid = new SnapshotBuilder.WriteCheckpoint();
      mid.phase = "write";
      mid.done = 2;
      mid.total = trajs.size();
      mid.batch_size = 2;
      mid.points = trajs.get(0).size() + trajs.get(1).size();
      new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter()
          .writeValue(ckpt.toFile(), mid);
      long allPoints = SnapshotBuilder.writeTrajectories(
          kv2, null, trajs, builders, chunker, ckpt, opt);
      assertEquals(points, allPoints);
      PostingVerifier.Report report = new PostingVerifier(layout).verify(kv2, trajs);
      assertTrue(report.ok(), "missing=" + report.missing);
      kv2.close();
    } finally {
      java.nio.file.Files.deleteIfExists(ckpt);
      kv.close();
    }
  }

  @Test
  void fixturePostingsMatchExpectedAndVerifyOk() throws Exception {
    MemoryBackend kv = SnapshotBuilder.buildFixtureInMemory();
    List<Trajectory> trajs = FixtureBuilder.trajectories();
    IndexBuilders.LayoutParams layout = FixtureBuilder.layoutParams();
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
    assertTrue(s.xz_code_posting_counts.size() >= 1);
    assertTrue(s.tshape_code_posting_counts.size() >= 1);
    assertTrue(s.xzt_code_posting_counts.size() >= 1);
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
