package kart.exec;

/**
 * Reference to a single trajectory chunk (tid, chunkId).
 */
public final class ChunkRef implements Comparable<ChunkRef> {

  public final long tid;
  public final long chunkId;

  public ChunkRef(long tid, long chunkId) {
    this.tid = tid;
    this.chunkId = chunkId;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof ChunkRef)) {
      return false;
    }
    ChunkRef other = (ChunkRef) o;
    return tid == other.tid && chunkId == other.chunkId;
  }

  @Override
  public int hashCode() {
    int h = (int) (tid ^ (tid >>> 32));
    h = 31 * h + (int) (chunkId ^ (chunkId >>> 32));
    return h;
  }

  @Override
  public int compareTo(ChunkRef o) {
    int c = Long.compare(tid, o.tid);
    if (c != 0) {
      return c;
    }
    return Long.compare(chunkId, o.chunkId);
  }

  @Override
  public String toString() {
    return "ChunkRef{tid=" + tid + ",chunk=" + chunkId + "}";
  }
}
