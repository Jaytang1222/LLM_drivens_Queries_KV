package kart.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One successfully parsed T-Drive trajectory line (WGS84 lon/lat, UTC ms), before projection.
 */
public final class RawTrajectory {
  public final String vehicleId;
  public final String trajectoryId;
  public final List<RawPoint> points;

  public RawTrajectory(String vehicleId, String trajectoryId, List<RawPoint> points) {
    this.vehicleId = vehicleId;
    this.trajectoryId = trajectoryId;
    this.points = Collections.unmodifiableList(new ArrayList<RawPoint>(points));
  }

  public static final class RawPoint {
    public final double lon;
    public final double lat;
    public final long timestampMs;

    public RawPoint(double lon, double lat, long timestampMs) {
      this.lon = lon;
      this.lat = lat;
      this.timestampMs = timestampMs;
    }
  }
}
