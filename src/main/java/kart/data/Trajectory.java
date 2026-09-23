package kart.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Full trajectory as ordered canonical points.
 */
public final class Trajectory {
  public final String vehicleId;
  public final String trajectoryId;
  public final long tid;
  private final List<CanonicalPoint> points;
  /** filled by Cleaner */
  public long dedupRemoved;
  public long sameTDropped;

  public Trajectory(String vehicleId, String trajectoryId, long tid, List<CanonicalPoint> points) {
    this.vehicleId = vehicleId;
    this.trajectoryId = trajectoryId;
    this.tid = tid;
    this.points = Collections.unmodifiableList(new ArrayList<CanonicalPoint>(points));
  }

  public List<CanonicalPoint> points() {
    return points;
  }

  public int size() {
    return points.size();
  }
}
