package kart.query;

import kart.compile.LayoutContext;
import kart.exec.MemoryBackend;
import kart.exec.QueryResult;
import kart.ir.BoundIr;
import kart.oracle.FullScanOracle;
import kart.snapshot.FixtureBuilder;
import kart.snapshot.SnapshotBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end P2: MemoryBackend fixture Top-K returns [A,B] matching Oracle.
 */
public class QueryIrFixtureTest {

  @TempDir
  Path tmp;

  @Test
  void fixtureTopKReturnsAB() throws Exception {
    BoundIr ir = fixtureQuery();
    MemoryBackend kv = SnapshotBuilder.buildFixtureInMemory();
    try {
      LayoutContext layout = LayoutContext.from(FixtureBuilder.fixtureManifest());
      QueryEngine engine = new QueryEngine(kv, layout);
      QueryEngine.RunResult rr = engine.run(ir, tmp.resolve("runs"));

      assertNotNull(rr.selected, "expected a safe plan");
      assertEquals("OK", rr.result.status, rr.result.error);
      assertEquals(Arrays.asList("A", "B"), rr.result.trajectoryIds);

      FullScanOracle.Answer oracle = FullScanOracle.forFixture().evaluate(ir);
      assertEquals(oracle.trajectoryIds, rr.result.trajectoryIds);

      QueryResult.Scored a = rr.result.topK.get(0);
      QueryResult.Scored b = rr.result.topK.get(1);
      assertEquals("A", a.trajectoryId);
      assertEquals("B", b.trajectoryId);
      assertTrue(a.distance <= b.distance);
    } finally {
      kv.close();
    }
  }

  public static BoundIr fixtureQuery() {
    return kart.snapshot.FixtureQueries.fixtureTopK();
  }
}
