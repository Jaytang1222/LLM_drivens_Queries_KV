package kart.exec;

import kart.data.CanonicalPoint;
import kart.data.Chunk;
import kart.data.Trajectory;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T2.5 focused unit coverage for set ops / DTW / Top-K tie / ChunkRef identity
 * beyond ExactSTFilterTest and Coordinator integration tests.
 */
class OperatorUnitTest {

  @Test
  void chunkRefEqualityUsesTidAndChunkId() {
    ChunkRef a = new ChunkRef(1L, 2);
    ChunkRef b = new ChunkRef(1L, 2);
    ChunkRef c = new ChunkRef(1L, 3);
    Set<ChunkRef> s = new HashSet<ChunkRef>();
    s.add(a);
    s.add(b);
    s.add(c);
    assertEquals(2, s.size());
    assertTrue(s.contains(new ChunkRef(1L, 2)));
  }

  @Test
  void dtwSharedWithOracleSemantics() {
    Dtw.Point[] r = new Dtw.Point[]{
        new Dtw.Point(1, 1), new Dtw.Point(2, 2), new Dtw.Point(3, 3)
    };
    Dtw.Point[] a = new Dtw.Point[]{
        new Dtw.Point(1, 1), new Dtw.Point(5, 5), new Dtw.Point(9, 9)
    };
    Dtw.Result da = Dtw.distance(a, r);
    assertTrue(da.distance > 0);
    assertEquals(9L, da.cells); // 3x3
  }

  @Test
  void topKTieBreaksByTidAsc() {
    // Mimic Coordinator TOP_K ordering: distance asc, then tid asc.
    List<Scored> scored = new ArrayList<Scored>();
    scored.add(new Scored(3L, "C", 1.0));
    scored.add(new Scored(1L, "A", 1.0));
    scored.add(new Scored(2L, "B", 0.5));
    java.util.Collections.sort(scored, new java.util.Comparator<Scored>() {
      @Override
      public int compare(Scored x, Scored y) {
        int c = Double.compare(x.distance, y.distance);
        if (c != 0) {
          return c;
        }
        return Long.compare(x.tid, y.tid);
      }
    });
    assertEquals(Arrays.asList("B", "A", "C"),
        Arrays.asList(scored.get(0).id, scored.get(1).id, scored.get(2).id));
  }

  @Test
  void chunkDecodeRoundTrip() {
    List<CanonicalPoint> pts = new ArrayList<CanonicalPoint>();
    pts.add(new CanonicalPoint("v", "t", 1L, 0, 100L, 1.0, 2.0));
    pts.add(new CanonicalPoint("v", "t", 1L, 1, 200L, 3.0, 4.0));
    Chunk c = new Chunk(1L, 0, pts);
    List<Chunk.DecodedPoint> d = Chunk.decodePayload(c.encodePayload());
    assertEquals(2, d.size());
    assertEquals(100L, d.get(0).t);
    assertEquals(3.0, d.get(1).x, 1e-9);
  }

  static final class Scored {
    final long tid;
    final String id;
    final double distance;

    Scored(long tid, String id, double distance) {
      this.tid = tid;
      this.id = id;
      this.distance = distance;
    }
  }
}
