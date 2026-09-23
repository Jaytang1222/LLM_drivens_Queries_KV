package kart.cost;

import kart.compile.LayoutContext;
import kart.exec.ExecutionTrace;
import kart.exec.MemoryBackend;
import kart.exec.QueryResult;
import kart.ir.BoundIr;
import kart.query.QueryEngine;
import kart.query.QueryIrFixtureTest;
import kart.snapshot.FixtureBuilder;
import kart.snapshot.SnapshotBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * T5.4 ExecutionTrace fields; T5.5 explain physical-bytes isolation (smoke via engine artifacts).
 */
public class ExecutionTraceP5Test {

  @TempDir
  Path tmp;

  @Test
  void t54_traceHasSection16FieldsAndNullables() throws Exception {
    BoundIr ir = QueryIrFixtureTest.fixtureQuery();
    MemoryBackend kv = SnapshotBuilder.buildFixtureInMemory();
    try {
      LayoutContext layout = LayoutContext.from(FixtureBuilder.fixtureManifest());
      QueryEngine engine = new QueryEngine(kv, layout);
      QueryEngine.RunResult rr = engine.run(ir, tmp.resolve("runs"));
      assertEquals("OK", rr.result.status, rr.result.error);
      ExecutionTrace t = rr.result.trace;
      assertNotNull(t);
      assertNotNull(t.plan_id);
      assertNotNull(t.run_id);
      assertNotNull(t.operator_metrics);
      assertFalse(t.operator_metrics.isEmpty());
      // unavailable metrics must be null (not 0)
      assertEquals(null, t.llm_calls);
      assertEquals(null, t.llm_tokens);
      assertEquals(null, t.rpc_count);
      assertNotNull(t.dtw_cells);
      assertTrue(t.dtw_cells.longValue() > 0);

      String json = t.toJson();
      assertTrue(json.contains("\"llm_calls\" : null") || json.contains("\"llm_calls\": null"),
          json);
      assertTrue(json.contains("operator_metrics"));
      assertTrue(Files.isRegularFile(rr.runDir.resolve("trace.json")));
      assertTrue(Files.isRegularFile(rr.runDir.resolve("cost_card.json"))
          || Files.isRegularFile(rr.runDir.resolve("cost_cards.json")));
    } finally {
      kv.close();
    }
  }

  @Test
  void t55_boundIrAndPlanJsonHaveNoHexRowKeys() throws Exception {
    BoundIr ir = QueryIrFixtureTest.fixtureQuery();
    MemoryBackend kv = SnapshotBuilder.buildFixtureInMemory();
    try {
      LayoutContext layout = LayoutContext.from(FixtureBuilder.fixtureManifest());
      QueryEngine.RunResult rr = new QueryEngine(kv, layout).run(ir, tmp.resolve("runs"));
      String irJson = new String(Files.readAllBytes(rr.runDir.resolve("bound_ir.json")), "UTF-8");
      String planJson = new String(Files.readAllBytes(rr.runDir.resolve("plan.json")), "UTF-8");
      assertFalse(irJson.contains("startHex") || irJson.contains("start_row"), irJson);
      assertFalse(planJson.contains("startHex") || planJson.contains("stopHex"), planJson);
      String phys = new String(Files.readAllBytes(rr.runDir.resolve("physical.json")), "UTF-8");
      assertTrue(phys.contains("startHex") || phys.contains("scanTasks"), phys);
    } finally {
      kv.close();
    }
  }
}
