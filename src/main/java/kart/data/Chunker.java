package kart.data;

import java.util.ArrayList;
import java.util.List;

/**
 * Split trajectory into chunks of at most maxPoints (design.md §3.4).
 */
public final class Chunker {

  private final int maxPoints;

  public Chunker(int maxPoints) {
    if (maxPoints <= 0) {
      throw new IllegalArgumentException("maxPoints must be > 0");
    }
    this.maxPoints = maxPoints;
  }

  public List<Chunk> chunk(Trajectory t) {
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

  /** Concatenate chunk points in order; must equal original trajectory points. */
  public static List<CanonicalPoint> concatenate(List<Chunk> chunks) {
    List<CanonicalPoint> out = new ArrayList<CanonicalPoint>();
    for (Chunk c : chunks) {
      out.addAll(c.points);
    }
    return out;
  }
}
