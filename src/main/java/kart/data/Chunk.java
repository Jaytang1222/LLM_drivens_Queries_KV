package kart.data;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Chunk of up to N points with MBR and time range.
 */
public final class Chunk {
  public final long tid;
  public final int chunkId;
  public final int seqFirst;
  public final int seqLast;
  public final int pointCount;
  public final long tMinMs;
  public final long tMaxMs;
  public final double minX;
  public final double minY;
  public final double maxX;
  public final double maxY;
  public final List<CanonicalPoint> points;

  public Chunk(long tid, int chunkId, List<CanonicalPoint> points) {
    if (points == null || points.isEmpty()) {
      throw new IllegalArgumentException("chunk must have points");
    }
    this.tid = tid;
    this.chunkId = chunkId;
    this.points = CollectionsUnmodifiable.copy(points);
    this.pointCount = points.size();
    this.seqFirst = points.get(0).seq;
    this.seqLast = points.get(points.size() - 1).seq;
    long t0 = Long.MAX_VALUE;
    long t1 = Long.MIN_VALUE;
    double minx = Double.POSITIVE_INFINITY;
    double miny = Double.POSITIVE_INFINITY;
    double maxx = Double.NEGATIVE_INFINITY;
    double maxy = Double.NEGATIVE_INFINITY;
    for (CanonicalPoint p : points) {
      t0 = Math.min(t0, p.timestampMs);
      t1 = Math.max(t1, p.timestampMs);
      minx = Math.min(minx, p.xM);
      miny = Math.min(miny, p.yM);
      maxx = Math.max(maxx, p.xM);
      maxy = Math.max(maxy, p.yM);
    }
    this.tMinMs = t0;
    this.tMaxMs = t1;
    this.minX = minx;
    this.minY = miny;
    this.maxX = maxx;
    this.maxY = maxy;
  }

  /** Payload: U32 count || (int64 t, double x, double y) * count, big-endian. */
  public byte[] encodePayload() {
    ByteBuffer bb = ByteBuffer.allocate(4 + pointCount * (8 + 8 + 8));
    bb.putInt(pointCount);
    for (CanonicalPoint p : points) {
      bb.putLong(p.timestampMs);
      bb.putDouble(p.xM);
      bb.putDouble(p.yM);
    }
    return bb.array();
  }

  public static List<DecodedPoint> decodePayload(byte[] payload) {
    ByteBuffer bb = ByteBuffer.wrap(payload);
    int n = bb.getInt();
    List<DecodedPoint> out = new ArrayList<DecodedPoint>(n);
    for (int i = 0; i < n; i++) {
      out.add(new DecodedPoint(bb.getLong(), bb.getDouble(), bb.getDouble()));
    }
    return out;
  }

  /** Metadata column encoding (simple big-endian struct). */
  public byte[] encodeMeta() {
    ByteBuffer bb = ByteBuffer.allocate(4 + 4 + 4 + 8 + 8 + 8 * 4);
    bb.putInt(seqFirst);
    bb.putInt(seqLast);
    bb.putInt(pointCount);
    bb.putLong(tMinMs);
    bb.putLong(tMaxMs);
    bb.putDouble(minX);
    bb.putDouble(minY);
    bb.putDouble(maxX);
    bb.putDouble(maxY);
    return bb.array();
  }

  public static final class DecodedPoint {
    public final long t;
    public final double x;
    public final double y;

    public DecodedPoint(long t, double x, double y) {
      this.t = t;
      this.x = x;
      this.y = y;
    }
  }

  private static final class CollectionsUnmodifiable {
    static List<CanonicalPoint> copy(List<CanonicalPoint> in) {
      return java.util.Collections.unmodifiableList(new ArrayList<CanonicalPoint>(in));
    }
  }
}
