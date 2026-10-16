package kart.exec;

import kart.data.Chunk;
import kart.ir.BoundIr;

import java.util.List;

/**
 * Exact spatio-temporal SAME_POINT filter over decoded chunk points.
 * Time is half-open [start_ms, end_ms); space is closed (boundary included).
 * Multiple {@code temporal_parts}/{@code spatial_parts} are OR'd within each dimension.
 */
public final class ExactSTFilter {

  private ExactSTFilter() {}

  /**
   * True if the chunk has at least one point satisfying every active predicate
   * (temporal + spatial coupled per point) and the trajectory-level attribute
   * predicates (vehicle_id EQ) hold.
   *
   * @param vehicleId trajectory vehicle id, or null if unknown/not needed
   */
  public static boolean matches(BoundIr ir, List<Chunk.DecodedPoint> points, String vehicleId) {
    if (ir.predicates != null) {
      for (BoundIr.Predicate p : ir.predicates) {
        if (p == null) {
          continue;
        }
        if ("vehicle_id".equals(p.field) && "EQ".equals(p.op)) {
          if (vehicleId == null || !vehicleId.equals(p.value)) {
            return false;
          }
        } else {
          throw new ExecException(ExecException.Code.FAILED,
              "unsupported predicate: " + p.field + " " + p.op);
        }
      }
    }
    List<BoundIr.Temporal> temps = BoundIr.temporalsOf(ir);
    List<BoundIr.Spatial> spaces = BoundIr.spatialsOf(ir);
    boolean needT = !temps.isEmpty();
    boolean needS = !spaces.isEmpty();
    if (!needT && !needS) {
      return true;
    }
    for (Chunk.DecodedPoint pt : points) {
      boolean okT = !needT || inAnyTemporal(temps, pt.t);
      boolean okS = !needS || inAnySpatial(spaces, pt.x, pt.y);
      if (okT && okS) {
        return true;
      }
    }
    return false;
  }

  private static boolean inAnyTemporal(List<BoundIr.Temporal> temps, long t) {
    for (BoundIr.Temporal w : temps) {
      if (w != null && t >= w.start_ms && t < w.end_ms) {
        return true;
      }
    }
    return false;
  }

  private static boolean inAnySpatial(List<BoundIr.Spatial> spaces, double x, double y) {
    for (BoundIr.Spatial s : spaces) {
      if (s != null
          && x >= s.min_x && x <= s.max_x
          && y >= s.min_y && y <= s.max_y) {
        return true;
      }
    }
    return false;
  }
}
