package kart.cost;

import kart.ir.BoundIr;
import kart.search.IndexId;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public final class AdvQueryRouterTest {

  @Test
  void fatTemporalRoutesToSpatialXz() {
    BoundIr ir = st(20L * 3600L * 1000L, true);
    assertEquals(AdvQueryRouter.Route.SPATIAL_XZ, AdvQueryRouter.route(ir));
    List<List<String>> specs = AdvQueryRouter.injectSpecs(ir);
    assertTrue(specs.size() <= 2);
    // Default fixture area is wide-scale → TS primary.
    assertEquals(IndexId.TSHAPE, specs.get(0).get(0));
  }

  @Test
  void shortTemporalForcesClassicNoInject() {
    BoundIr ir = st(2L * 3600L * 1000L, true);
    assertEquals(AdvQueryRouter.Route.CLASSIC_ONLY, AdvQueryRouter.route(ir));
    assertTrue(AdvQueryRouter.forceClassic(ir));
    assertTrue(AdvQueryRouter.injectSpecs(ir).isEmpty());
  }

  @Test
  void vehicleEqForcesClassic() {
    BoundIr ir = st(20L * 3600L * 1000L, true);
    BoundIr.Predicate p = new BoundIr.Predicate();
    p.field = "vehicle_id";
    p.op = "EQ";
    p.value = "v1";
    ir.predicates.add(p);
    assertTrue(AdvQueryRouter.forceClassic(ir));
    assertTrue(AdvQueryRouter.injectSpecs(ir).isEmpty());
  }

  @Test
  void preferencePrefersXzThenTsOnFatSpatialRoute() {
    assertEquals(0, AdvQueryRouter.preferenceRank("P_XZ", AdvQueryRouter.Route.SPATIAL_XZ));
    assertEquals(1, AdvQueryRouter.preferenceRank("P_TS", AdvQueryRouter.Route.SPATIAL_XZ));
    assertTrue(AdvQueryRouter.preferenceRank("P_XZ", AdvQueryRouter.Route.SPATIAL_XZ)
        < AdvQueryRouter.preferenceRank("P_XZTZ", AdvQueryRouter.Route.SPATIAL_XZ));
  }

  @Test
  void fatWideInjectsTsOnly() {
    BoundIr ir = st(20L * 3600L * 1000L, true);
    List<List<String>> specs = AdvQueryRouter.injectSpecs(ir);
    assertEquals(1, specs.size());
    assertEquals(IndexId.TSHAPE, specs.get(0).get(0));
  }

  @Test
  void fatMegaInjectsXztXz() {
    BoundIr ir = st(20L * 3600L * 1000L, true);
    ir.spatial.max_x = 30_000;
    ir.spatial.max_y = 30_000; // area=9e8 mega
    List<List<String>> specs = AdvQueryRouter.injectSpecs(ir);
    assertEquals(1, specs.size());
    assertTrue(specs.get(0).contains(IndexId.XZT));
    assertTrue(specs.get(0).contains(IndexId.XZ));
  }

  @Test
  void fatLargeWideInjectsXz() {
    BoundIr ir = st(20L * 3600L * 1000L, true);
    ir.spatial.max_x = 13_000;
    ir.spatial.max_y = 12_000; // area=1.56e8 ≥ xz-wide
    List<List<String>> specs = AdvQueryRouter.injectSpecs(ir);
    assertEquals(1, specs.size());
    assertEquals(IndexId.XZ, specs.get(0).get(0));
  }

  @Test
  void hybridInjectsSingleXztXz() {
    BoundIr ir = st(8L * 3600L * 1000L, true);
    assertEquals(AdvQueryRouter.Route.HYBRID, AdvQueryRouter.route(ir));
    List<List<String>> specs = AdvQueryRouter.injectSpecs(ir);
    assertEquals(1, specs.size());
    assertTrue(specs.get(0).contains(IndexId.XZT));
    assertTrue(specs.get(0).contains(IndexId.XZ));
  }

  @Test
  void multiAoiForcesClassicNoInject() {
    BoundIr ir = st(20L * 3600L * 1000L, true);
    BoundIr.Spatial a = new BoundIr.Spatial();
    a.min_x = 0; a.min_y = 0; a.max_x = 1; a.max_y = 1;
    BoundIr.Spatial b = new BoundIr.Spatial();
    b.min_x = 10; b.min_y = 10; b.max_x = 11; b.max_y = 11;
    ir.spatial = null;
    ir.spatial_parts = java.util.Arrays.asList(a, b);
    assertTrue(AdvQueryRouter.forceClassic(ir));
    assertTrue(AdvQueryRouter.injectSpecs(ir).isEmpty());
  }

  private static BoundIr st(long spanMs, boolean spatial) {
    BoundIr ir = new BoundIr();
    ir.temporal = new BoundIr.Temporal();
    ir.temporal.start_ms = 1_200_000_000_000L;
    ir.temporal.end_ms = ir.temporal.start_ms + spanMs;
    if (spatial) {
      ir.spatial = new BoundIr.Spatial();
      ir.spatial.min_x = 0;
      ir.spatial.min_y = 0;
      ir.spatial.max_x = 1_000;
      ir.spatial.max_y = 1_000;
    }
    return ir;
  }
}
