package kart.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Assign tid by trajectory_id lexicographic order starting at 1; rewrite points with final tid.
 */
public final class TidAssigner {

  private TidAssigner() {}

  public static List<Trajectory> assign(List<Trajectory> input) {
    List<Trajectory> sorted = new ArrayList<Trajectory>(input);
    Collections.sort(sorted, new Comparator<Trajectory>() {
      @Override
      public int compare(Trajectory a, Trajectory b) {
        return a.trajectoryId.compareTo(b.trajectoryId);
      }
    });
    List<Trajectory> out = new ArrayList<Trajectory>(sorted.size());
    long tid = 1;
    for (Trajectory t : sorted) {
      List<CanonicalPoint> pts = new ArrayList<CanonicalPoint>(t.size());
      for (CanonicalPoint p : t.points()) {
        pts.add(new CanonicalPoint(p.vehicleId, p.trajectoryId, tid, p.seq,
            p.timestampMs, p.xM, p.yM));
      }
      Trajectory nt = new Trajectory(t.vehicleId, t.trajectoryId, tid, pts);
      nt.dedupRemoved = t.dedupRemoved;
      nt.sameTDropped = t.sameTDropped;
      out.add(nt);
      tid++;
    }
    return out;
  }
}
