package kart.snapshot;

import kart.catalog.Manifest;
import kart.data.TidAssigner;
import kart.data.Trajectory;
import kart.exec.MemoryBackend;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StreamingSnapshotBuildTest {

  @TempDir
  Path tmp;

  @Test
  void spillRoundTripPreservesPointsAndTidOrder() throws Exception {
    List<Trajectory> trajs = FixtureBuilder.trajectories();
    Path dir = tmp.resolve("spill");
    TrajectorySpill spill = TrajectorySpill.create(dir);
    try {
      for (Trajectory t : trajs) {
        // store with placeholder tid like the clean→spill path
        spill.append(TidAssigner.withTid(t, 0L));
      }
      spill.finish();
      assertEquals(trajs.size(), spill.size());
      // lex order of ids: A,B,C,R → tid 1..4
      Trajectory first = TidAssigner.withTid(spill.readByTid0(0), 1L);
      assertEquals("A", first.trajectoryId);
      assertEquals(1L, first.tid);
      assertEquals(trajs.get(0).size(), first.size());
      Trajectory last = TidAssigner.withTid(spill.readByTid0(3), 4L);
      assertEquals("R", last.trajectoryId);
    } finally {
      spill.close();
      TrajectorySpill.deleteQuietly(dir);
    }
  }

  @Test
  void streamingBuildToMemoryReachesReady() throws Exception {
    Path data = tmp.resolve("data");
    Files.createDirectories(data);
    long t0 = 1_201_930_859_000L;
    Files.write(data.resolve("part-00000"), Arrays.asList(
        "b-1-MULTIPOINT Z((116.37497 39.85789 " + t0 + "), (116.37542 39.85764 " + (t0 + 1000) + "))",
        "a-1-MULTIPOINT Z((116.37000 39.85000 " + t0 + "), (116.37100 39.85100 " + (t0 + 1000) + "))"
    ), StandardCharsets.UTF_8);

    SnapshotBuilder.Options opt = new SnapshotBuilder.Options();
    opt.useMemory = true;
    opt.streamSpill = true;
    opt.dataDir = data;
    opt.catalogDir = tmp.resolve("catalog");
    opt.manifestId = "stream_test_ready";
    opt.format = "tdrive";
    opt.writeBatchSize = 1;
    opt.verifySampleSize = 0;

    SnapshotBuilder.BuildResult r = new SnapshotBuilder().build(opt);
    assertNull(r.failureReason, () -> r.failureReason);
    assertEquals(2, r.trajectories);
    assertTrue(r.points >= 4);
    assertEquals(Manifest.Status.READY, r.manifest.status);
    assertTrue(r.verify != null && r.verify.ok());
    assertFalse(Files.exists(opt.catalogDir.resolve("stream_test_ready.spill")),
        "spill must be deleted after READY");
  }

  @Test
  void writeFromSpillMatchesInMemoryVerify() throws Exception {
    List<Trajectory> trajs = TidAssigner.assign(FixtureBuilder.trajectories());
    Path spillDir = tmp.resolve("wspill");
    TrajectorySpill spill = TrajectorySpill.create(spillDir);
    try {
      for (Trajectory t : trajs) {
        spill.append(TidAssigner.withTid(t, 0L));
      }
      spill.finish();

      IndexBuilders.LayoutParams layout = FixtureBuilder.layoutParams();
      IndexBuilders builders = new IndexBuilders(layout);
      kart.data.Chunker chunker = new kart.data.Chunker(256);
      SnapshotBuilder.Options opt = new SnapshotBuilder.Options();
      opt.writeBatchSize = 2;

      MemoryBackend kv = new MemoryBackend();
      try {
        long points = SnapshotBuilder.writeTrajectoriesFromSpill(
            kv, null, spill, builders, chunker, null, opt);
        assertTrue(points > 0);
        List<Trajectory> loaded = SnapshotBuilder.loadTrajectoriesForVerify(
            spill, spill.size(), PostingVerifier.Options.memoryFull());
        PostingVerifier.Report report = new PostingVerifier(layout).verify(kv, loaded);
        assertTrue(report.ok(), "missing=" + report.missing);
      } finally {
        kv.close();
      }
    } finally {
      spill.close();
      TrajectorySpill.deleteQuietly(spillDir);
    }
  }
}
