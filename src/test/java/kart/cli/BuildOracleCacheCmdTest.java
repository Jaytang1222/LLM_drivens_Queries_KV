package kart.cli;

import kart.catalog.Manifest;
import kart.bench.SuiteSpec;
import kart.data.CanonicalPoint;
import kart.data.Trajectory;
import kart.geo.Rect;
import kart.ir.BoundIr;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

public final class BuildOracleCacheCmdTest {

  @Test
  void advancedDiagnosticWorkloadIsDiverseAndUsesDistinctSeededAnchors() {
    Rect domain = new Rect(0.0d, 0.0d, 100_000.0d, 100_000.0d);
    List<Trajectory> trajectories = new ArrayList<Trajectory>();
    for (int i = 0; i < 16; i++) {
      List<CanonicalPoint> points = new ArrayList<CanonicalPoint>();
      for (int j = 0; j < 8; j++) {
        points.add(new CanonicalPoint("vehicle-" + i, "trajectory-" + i, i + 1L, j,
            1_202_000_000_000L + i * 600_000L + j * 60_000L,
            1_000.0d + i, 1_000.0d + i));
      }
      trajectories.add(new Trajectory("vehicle-" + i, "trajectory-" + i, i + 1L, points));
    }

    List<BuildOracleCacheCmd.Anchor> first = BuildOracleCacheCmd.pickDiverseAnchors(
        trajectories, domain, 600_000L, 20261014L, 12);
    List<BuildOracleCacheCmd.Anchor> repeated = BuildOracleCacheCmd.pickDiverseAnchors(
        trajectories, domain, 600_000L, 20261014L, 12);
    assertEquals(12, first.size());
    assertEquals(anchorTids(first), anchorTids(repeated));
    assertEquals(12, new HashSet<String>(anchorVehicles(first)).size());

    Manifest manifest = new Manifest();
    manifest.manifest_id = "tdrive_v1_ready";
    manifest.semantics_version = "point_dtw_v1";
    List<BoundIr> queries = BuildOracleCacheCmd.buildAdvancedComplexDiagnosticWorkload(
        first, domain, 600_000L, 1_201_910_400_000L, manifest);
    assertEquals(12, queries.size());
    assertEquals(12, new HashSet<String>(queryIds(queries)).size());
    assertEquals(3, countMode(queries, "TOP_K"));
    assertEquals(9, countWithSpatial(queries));
    assertEquals(9, countWithTemporal(queries));
    assertEquals(3, countWithVehiclePredicate(queries));
    Set<String> metrics = new HashSet<String>();
    for (BoundIr ir : queries) {
      if (ir.similarity != null) {
        metrics.add(ir.similarity.metric);
      }
    }
    assertEquals(new HashSet<String>(Arrays.asList("DTW", "FRECHET", "HAUSDORFF")), metrics);
  }

  @Test
  void advancedDiagnosticSuiteLoadsWithNativeComparisonArms() throws Exception {
    SuiteSpec suite = SuiteSpec.load(
        Paths.get("experiments/suites/e2e-adv-complex-diagnostic.yaml"));
    assertEquals("e2e-adv-complex-diagnostic", suite.id);
    assertEquals(1, suite.trials);
    assertEquals(Arrays.asList("fullscan", "rbo", "cbo", "kart"), suite.arms);
  }

  private static List<Long> anchorTids(List<BuildOracleCacheCmd.Anchor> anchors) {
    List<Long> out = new ArrayList<Long>();
    for (BuildOracleCacheCmd.Anchor anchor : anchors) {
      out.add(Long.valueOf(anchor.tid));
    }
    return out;
  }

  private static List<String> anchorVehicles(List<BuildOracleCacheCmd.Anchor> anchors) {
    List<String> out = new ArrayList<String>();
    for (BuildOracleCacheCmd.Anchor anchor : anchors) {
      out.add(anchor.vehicleId);
    }
    return out;
  }

  private static List<String> queryIds(List<BoundIr> queries) {
    List<String> out = new ArrayList<String>();
    for (BoundIr query : queries) {
      out.add(query.query_id);
    }
    return out;
  }

  private static int countMode(List<BoundIr> queries, String mode) {
    int n = 0;
    for (BoundIr query : queries) {
      if (query.result != null && mode.equals(query.result.mode)) {
        n++;
      }
    }
    return n;
  }

  private static int countWithSpatial(List<BoundIr> queries) {
    int n = 0;
    for (BoundIr query : queries) {
      if (query.spatial != null) {
        n++;
      }
    }
    return n;
  }

  private static int countWithTemporal(List<BoundIr> queries) {
    int n = 0;
    for (BoundIr query : queries) {
      if (query.temporal != null) {
        n++;
      }
    }
    return n;
  }

  private static int countWithVehiclePredicate(List<BoundIr> queries) {
    int n = 0;
    for (BoundIr query : queries) {
      if (query.predicates != null && !query.predicates.isEmpty()
          && "vehicle_id".equals(query.predicates.get(0).field)) {
        n++;
      }
    }
    return n;
  }
}
