package kart.data;

import kart.geo.Projection;
import kart.geo.Rect;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Cleaning pipeline (design.md §3.2): project → sort by t → dedup exact → same-t keep first → seq → domain.
 */
public final class Cleaner {

  public static final class Stats {
    public long projected;
    public long dedupRemoved;
    public long sameTDropped;
    public long outOfDomainTrajectories;
    public long emptyAfterClean;
  }

  public static final class Result {
    public final List<Trajectory> trajectories = new ArrayList<Trajectory>();
    public final List<TDriveParser.Rejected> rejected = new ArrayList<TDriveParser.Rejected>();
    public final Stats stats = new Stats();
  }

  private final Projection projection;
  private final Rect domain; // null = skip domain check

  public Cleaner(Projection projection, Rect domain) {
    this.projection = projection;
    this.domain = domain;
  }

  /**
   * Clean one raw trajectory. tid is assigned later; use 0 here.
   */
  public Trajectory cleanOne(RawTrajectory raw, long tidPlaceholder) {
    List<CanonicalPoint> pts = new ArrayList<CanonicalPoint>(raw.points.size());
    for (RawTrajectory.RawPoint rp : raw.points) {
      Projection.Meters m = projection.toUtm(rp.lon, rp.lat);
      pts.add(new CanonicalPoint(raw.vehicleId, raw.trajectoryId, tidPlaceholder, 0,
          rp.timestampMs, m.x, m.y));
    }
    Collections.sort(pts, new Comparator<CanonicalPoint>() {
      @Override
      public int compare(CanonicalPoint a, CanonicalPoint b) {
        int c = Long.compare(a.timestampMs, b.timestampMs);
        if (c != 0) {
          return c;
        }
        // stable by original order via identity — already insertion order for equal t before sort
        return 0;
      }
    });

    List<CanonicalPoint> deduped = new ArrayList<CanonicalPoint>();
    CanonicalPoint prev = null;
    long dedup = 0;
    long sameT = 0;
    for (CanonicalPoint p : pts) {
      if (prev != null
          && prev.timestampMs == p.timestampMs
          && prev.xM == p.xM
          && prev.yM == p.yM) {
        dedup++;
        continue;
      }
      if (prev != null && prev.timestampMs == p.timestampMs) {
        // same t different position: keep first
        sameT++;
        continue;
      }
      deduped.add(p);
      prev = p;
    }

    if (domain != null) {
      for (CanonicalPoint p : deduped) {
        if (!domain.contains(p.xM, p.yM)) {
          throw new OutOfDomainException("point out of domain: (" + p.xM + "," + p.yM + ")");
        }
      }
    }

    if (deduped.isEmpty()) {
      throw new EmptyTrajectoryException("empty after clean");
    }

    List<CanonicalPoint> withSeq = new ArrayList<CanonicalPoint>(deduped.size());
    for (int i = 0; i < deduped.size(); i++) {
      CanonicalPoint p = deduped.get(i);
      withSeq.add(new CanonicalPoint(p.vehicleId, p.trajectoryId, tidPlaceholder, i,
          p.timestampMs, p.xM, p.yM));
    }
    Trajectory t = new Trajectory(raw.vehicleId, raw.trajectoryId, tidPlaceholder, withSeq);
    t.dedupRemoved = dedup;
    t.sameTDropped = sameT;
    return t;
  }

  public Result cleanAll(List<RawTrajectory> raws) {
    Result r = new Result();
    for (RawTrajectory raw : raws) {
      try {
        Trajectory t = cleanOne(raw, 0L);
        r.stats.projected += t.size();
        r.stats.dedupRemoved += t.dedupRemoved;
        r.stats.sameTDropped += t.sameTDropped;
        r.trajectories.add(t);
      } catch (OutOfDomainException e) {
        r.stats.outOfDomainTrajectories++;
        r.rejected.add(new TDriveParser.Rejected(raw.trajectoryId, TDriveParser.RejectReason.OUT_OF_DOMAIN,
            e.getMessage()));
      } catch (EmptyTrajectoryException e) {
        r.stats.emptyAfterClean++;
        r.rejected.add(new TDriveParser.Rejected(raw.trajectoryId, TDriveParser.RejectReason.EMPTY,
            e.getMessage()));
      }
    }
    return r;
  }

  public static final class OutOfDomainException extends RuntimeException {
    public OutOfDomainException(String msg) {
      super(msg);
    }
  }

  public static final class EmptyTrajectoryException extends RuntimeException {
    public EmptyTrajectoryException(String msg) {
      super(msg);
    }
  }
}
