package kart.exec;

import kart.data.Chunk;
import kart.ir.BoundIr;

import java.util.List;

/**
 * Exact spatio-temporal SAME_POINT filter over decoded chunk points.
 * Time is half-open [start_ms, end_ms); space is closed (boundary included).
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
    // attribute predicates (trajectory-level)
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
    boolean needT = ir.temporal != null;
    boolean needS = ir.spatial != null;
    if (!needT && !needS) {
      return true;
    }
    for (Chunk.DecodedPoint pt : points) {
      boolean okT = !needT
          || (pt.t >= ir.temporal.start_ms && pt.t < ir.temporal.end_ms);
      boolean okS = !needS
          || (pt.x >= ir.spatial.min_x && pt.x <= ir.spatial.max_x
          && pt.y >= ir.spatial.min_y && pt.y <= ir.spatial.max_y);
      if (okT && okS) {
        return true;
      }
    }
    return false;
  }
}
