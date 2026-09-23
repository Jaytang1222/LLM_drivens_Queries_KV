package kart.data;

import kart.geo.Projection;
import kart.geo.Rect;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CleanerChunkerTest {

  @Test
  void dedupAndSameT() {
    RawTrajectory raw = new RawTrajectory("v", "v-1", Arrays.asList(
        new RawTrajectory.RawPoint(116.4, 39.9, 1000),
        new RawTrajectory.RawPoint(116.4, 39.9, 1000), // exact dup
        new RawTrajectory.RawPoint(116.41, 39.91, 1000), // same t different pos → drop
        new RawTrajectory.RawPoint(116.42, 39.92, 2000)
    ));
    Cleaner cleaner = new Cleaner(new Projection(), null);
    Trajectory t = cleaner.cleanOne(raw, 1L);
    assertEquals(2, t.size());
    assertEquals(1, t.dedupRemoved);
    assertEquals(1, t.sameTDropped);
    assertEquals(0, t.points().get(0).seq);
    assertEquals(1, t.points().get(1).seq);
  }

  @Test
  void outOfDomainRejected() {
    RawTrajectory raw = new RawTrajectory("v", "v-1", Collections.singletonList(
        new RawTrajectory.RawPoint(116.4, 39.9, 1000)
    ));
    Rect tiny = new Rect(0, 0, 1, 1); // meters — projected point won't be here
    Cleaner.Result r = new Cleaner(new Projection(), tiny).cleanAll(Collections.singletonList(raw));
    assertEquals(0, r.trajectories.size());
    assertEquals(1, r.stats.outOfDomainTrajectories);
  }

  @Test
  void chunk256And257() {
    List<CanonicalPoint> pts = new java.util.ArrayList<CanonicalPoint>();
    for (int i = 0; i < 256; i++) {
      pts.add(new CanonicalPoint("v", "v-1", 1, i, i * 1000L, i, i));
    }
    Trajectory t256 = new Trajectory("v", "v-1", 1, pts);
    Chunker chunker = new Chunker(256);
    assertEquals(1, chunker.chunk(t256).size());

    pts.add(new CanonicalPoint("v", "v-1", 1, 256, 256000L, 256, 256));
    Trajectory t257 = new Trajectory("v", "v-1", 1, pts);
    List<Chunk> c257 = chunker.chunk(t257);
    assertEquals(2, c257.size());
    assertEquals(256, c257.get(0).pointCount);
    assertEquals(1, c257.get(1).pointCount);

    List<CanonicalPoint> back = Chunker.concatenate(c257);
    assertEquals(257, back.size());
    for (int i = 0; i < 257; i++) {
      assertEquals(i, back.get(i).seq);
    }
  }

  @Test
  void exactly256OneChunk() {
    List<CanonicalPoint> pts = new java.util.ArrayList<CanonicalPoint>();
    for (int i = 0; i < 256; i++) {
      pts.add(new CanonicalPoint("v", "t", 1, i, i, 0, 0));
    }
    assertEquals(1, new Chunker(256).chunk(new Trajectory("v", "t", 1, pts)).size());
  }
}
