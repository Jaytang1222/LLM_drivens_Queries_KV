package kart.data;

/**
 * Canonical trajectory point after projection and cleaning.
 */
public final class CanonicalPoint {
  public final String vehicleId;
  public final String trajectoryId;
  public final long tid;
  public final int seq;
  public final long timestampMs;
  public final double xM;
  public final double yM;

  public CanonicalPoint(String vehicleId, String trajectoryId, long tid, int seq,
                        long timestampMs, double xM, double yM) {
    this.vehicleId = vehicleId;
    this.trajectoryId = trajectoryId;
    this.tid = tid;
    this.seq = seq;
    this.timestampMs = timestampMs;
    this.xM = xM;
    this.yM = yM;
  }
}
