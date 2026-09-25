package kart.oracle;

import kart.exec.Dtw;
import kart.ir.BoundIr;
import kart.snapshot.FixtureBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class FullScanOracleAndFixtureTest {

  @TempDir
  Path tmp;

  @Test
  void fixtureTop2IsABExcludingCR() {
    BoundIr ir = fixtureQuery();
    FullScanOracle.Answer ans = FullScanOracle.forFixture().evaluate(ir);
    assertEquals(Arrays.asList("A", "B"), ans.trajectoryIds);
    assertFalse(ans.trajectoryIds.contains("C"));
    assertFalse(ans.trajectoryIds.contains("R"));
  }

  @Test
  void dtwHandCalculationMatchesOracle() {
    // A vs R and B vs R — compute via shared Dtw and compare ordering
    Dtw.Point[] r = new Dtw.Point[]{
        new Dtw.Point(1, 1), new Dtw.Point(2, 2), new Dtw.Point(3, 3)
    };
    Dtw.Point[] a = new Dtw.Point[]{
        new Dtw.Point(1, 1), new Dtw.Point(5, 5), new Dtw.Point(9, 9)
    };
    Dtw.Point[] b = new Dtw.Point[]{
        new Dtw.Point(20, 20), new Dtw.Point(6, 6), new Dtw.Point(20, 20)
    };
    double da = Dtw.distance(a, r).distance;
    double db = Dtw.distance(b, r).distance;
    // A shares first point with R → typically closer than B
    // Just assert both finite and oracle order matches distance order
    BoundIr ir = fixtureQuery();
    FullScanOracle.Answer ans = FullScanOracle.forFixture().evaluate(ir);
    assertEquals(2, ans.topK.size());
    assertEquals(da, ans.topK.get(0).distance, 1e-9);
    assertEquals(db, ans.topK.get(1).distance, 1e-9);
    assertEquals("A", ans.topK.get(0).trajectoryId);
    assertEquals("B", ans.topK.get(1).trajectoryId);
  }

  @Test
  void buildFixtureDeterministic() throws Exception {
    Path out1 = tmp.resolve("f1");
    Path out2 = tmp.resolve("f2");
    FixtureBuilder.build(out1);
    FixtureBuilder.build(out2);
    String c1 = new String(Files.readAllBytes(out1.resolve("checksum.sha256")), "UTF-8").trim();
    String c2 = new String(Files.readAllBytes(out2.resolve("checksum.sha256")), "UTF-8").trim();
    assertEquals(c1, c2);
    assertEquals(64, c1.length());
  }

  public static BoundIr fixtureQuery() {
    BoundIr ir = new BoundIr();
    ir.ir_version = "1.0";
    ir.query_id = "q_fixture_001";
    ir.source = new BoundIr.Source();
    ir.source.dataset_id = "fixture_v1";
    ir.source.entity = "trajectory";
    ir.temporal = new BoundIr.Temporal();
    ir.temporal.start_ms = FixtureBuilder.T0;
    ir.temporal.end_ms = FixtureBuilder.T0 + 10 * 60 * 1000L; // 08:10 exclusive → points at 08:00,08:05
    ir.spatial = new BoundIr.Spatial();
    ir.spatial.min_x = 4;
    ir.spatial.min_y = 4;
    ir.spatial.max_x = 8;
    ir.spatial.max_y = 8;
    ir.similarity = new BoundIr.Similarity();
    ir.similarity.metric = "DTW";
    ir.similarity.reference_tid = 4; // R
    ir.similarity.exclude_reference = true;
    ir.result = new BoundIr.Result();
    ir.result.mode = "TOP_K";
    ir.result.k = 2;
    ir.snapshot = new BoundIr.Snapshot();
    ir.snapshot.manifest_id = FixtureBuilder.MANIFEST_ID;
    ir.snapshot.semantics_version = "point_dtw_v1";
    return ir;
  }
}
