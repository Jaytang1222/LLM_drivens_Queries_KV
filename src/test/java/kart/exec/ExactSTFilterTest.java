package kart.exec;

import kart.data.Chunk;
import kart.ir.BoundIr;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T2.5 unit coverage for ExactSTFilter: half-open time, closed space, SAME_POINT, vehicle_id.
 */
class ExactSTFilterTest {

  @Test
  void temporalHalfOpenIncludesStartExcludesEnd() {
    BoundIr ir = temporal(100L, 200L);
    assertTrue(ExactSTFilter.matches(ir, pts(p(100, 0, 0)), null));
    assertTrue(ExactSTFilter.matches(ir, pts(p(199, 0, 0)), null));
    assertFalse(ExactSTFilter.matches(ir, pts(p(200, 0, 0)), null));
    assertFalse(ExactSTFilter.matches(ir, pts(p(99, 0, 0)), null));
  }

  @Test
  void spatialClosedIncludesBoundaries() {
    BoundIr ir = spatial(0, 0, 10, 10);
    assertTrue(ExactSTFilter.matches(ir, pts(p(0, 0, 0)), null));
    assertTrue(ExactSTFilter.matches(ir, pts(p(0, 10, 10)), null));
    assertFalse(ExactSTFilter.matches(ir, pts(p(0, 10.1, 5)), null));
    assertFalse(ExactSTFilter.matches(ir, pts(p(0, 5, -0.1)), null));
  }

  @Test
  void samePointRequiresOnePointSatisfyingBoth() {
    BoundIr ir = new BoundIr();
    ir.temporal = new BoundIr.Temporal();
    ir.temporal.start_ms = 100;
    ir.temporal.end_ms = 200;
    ir.spatial = new BoundIr.Spatial();
    ir.spatial.min_x = 0;
    ir.spatial.min_y = 0;
    ir.spatial.max_x = 10;
    ir.spatial.max_y = 10;
    // time ok on first point, space ok on second — not SAME_POINT
    assertFalse(ExactSTFilter.matches(ir, pts(p(150, 100, 100), p(50, 5, 5)), null));
    assertTrue(ExactSTFilter.matches(ir, pts(p(150, 5, 5)), null));
  }

  @Test
  void vehicleIdEqualityIsTrajectoryLevel() {
    BoundIr ir = temporal(0, 1000);
    BoundIr.Predicate pred = new BoundIr.Predicate();
    pred.field = "vehicle_id";
    pred.op = "EQ";
    pred.value = "3644";
    ir.predicates = Collections.singletonList(pred);
    assertTrue(ExactSTFilter.matches(ir, pts(p(10, 0, 0)), "3644"));
    assertFalse(ExactSTFilter.matches(ir, pts(p(10, 0, 0)), "9999"));
    assertFalse(ExactSTFilter.matches(ir, pts(p(10, 0, 0)), null));
  }

  @Test
  void noTemporalOrSpatialAcceptsAnyPointsWhenAttrsPass() {
    BoundIr ir = new BoundIr();
    assertTrue(ExactSTFilter.matches(ir, pts(p(1, 2, 3)), null));
    assertTrue(ExactSTFilter.matches(ir, Collections.<Chunk.DecodedPoint>emptyList(), null));
  }

  @Test
  void unsupportedPredicateFails() {
    BoundIr ir = new BoundIr();
    BoundIr.Predicate pred = new BoundIr.Predicate();
    pred.field = "speed";
    pred.op = "EQ";
    pred.value = "1";
    ir.predicates = Collections.singletonList(pred);
    assertThrows(ExecException.class,
        () -> ExactSTFilter.matches(ir, pts(p(1, 0, 0)), null));
  }

  private static BoundIr temporal(long start, long end) {
    BoundIr ir = new BoundIr();
    ir.temporal = new BoundIr.Temporal();
    ir.temporal.start_ms = start;
    ir.temporal.end_ms = end;
    return ir;
  }

  private static BoundIr spatial(double minX, double minY, double maxX, double maxY) {
    BoundIr ir = new BoundIr();
    ir.spatial = new BoundIr.Spatial();
    ir.spatial.min_x = minX;
    ir.spatial.min_y = minY;
    ir.spatial.max_x = maxX;
    ir.spatial.max_y = maxY;
    return ir;
  }

  private static Chunk.DecodedPoint p(long t, double x, double y) {
    return new Chunk.DecodedPoint(t, x, y);
  }

  private static List<Chunk.DecodedPoint> pts(Chunk.DecodedPoint... points) {
    return Arrays.asList(points);
  }
}
