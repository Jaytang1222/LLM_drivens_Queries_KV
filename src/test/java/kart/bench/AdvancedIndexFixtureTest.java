package kart.bench;

import kart.compile.LayoutContext;
import kart.exec.MemoryBackend;
import kart.ir.BoundIr;
import kart.oracle.FullScanOracle;
import kart.plan.PlanBuilder;
import kart.plan.PlanEnvelope;
import kart.query.QueryEngine;
import kart.search.IndexId;
import kart.search.LegalActionGenerator;
import kart.search.SearchState;
import kart.snapshot.FixtureBuilder;
import kart.snapshot.FixtureQueries;
import kart.snapshot.SnapshotBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** V2b/V3: advanced indexes on fixture — runFixed envelopes + mutual exclusion. */
public class AdvancedIndexFixtureTest {

  @TempDir
  Path tmp;

  @Test
  void forcedAdvancedPlansMatchOracle() throws Exception {
    BoundIr ir = FixtureQueries.fixtureTopK();
    FullScanOracle.Answer oracle = FullScanOracle.forFixture().evaluate(ir);
    List<List<String>> specs = Arrays.asList(
        Arrays.asList(IndexId.XZ),
        Arrays.asList(IndexId.TSHAPE),
        Arrays.asList(IndexId.XZT),
        Arrays.asList(IndexId.XZT, IndexId.XZ));
    try (MemoryBackend kv = SnapshotBuilder.buildFixtureInMemory()) {
      LayoutContext layout = LayoutContext.from(FixtureBuilder.fixtureManifest());
      QueryEngine engine = new QueryEngine(kv, layout);
      for (List<String> idx : specs) {
        String merge = idx.size() >= 2 ? "HASH_SET" : null;
        PlanEnvelope plan = PlanBuilder.buildForIndexes(ir, idx, merge, null);
        QueryEngine.RunResult rr = engine.runFixed(ir, tmp.resolve(plan.plan_id), false, plan);
        String detail = plan.plan_id + " status="
            + (rr.result == null ? "?" : rr.result.status)
            + " err=" + (rr.result == null ? "?" : rr.result.error)
            + " rej=" + rr.rejectionReports.size();
        if (!rr.rejectionReports.isEmpty() && rr.rejectionReports.get(0) != null) {
          for (kart.validation.ValidationReport.Finding f : rr.rejectionReports.get(0).findings()) {
            if (!f.ok) {
              detail += " FAIL=" + f.message;
              break;
            }
          }
        }
        assertNotNull(rr.selected, detail);
        assertEquals(plan.plan_id, rr.selected.plan().plan_id, detail);
        assertEquals("OK", rr.result.status, detail);
        assertEquals(oracle.trajectoryIds, rr.result.trajectoryIds, detail);
      }
    }
  }

  @Test
  void sameDimIntersectRejected() {
    BoundIr ir = FixtureQueries.fixtureTopK();
    SearchState withTime = SearchState.initial(ir)
        .apply(LegalActionGenerator.start(IndexId.TIME));
    LegalActionGenerator gen = new LegalActionGenerator();
    List<kart.search.LegalAction> fromTime = gen.generate(withTime);
    assertFalse(fromTime.stream().anyMatch(a ->
            a.kind == kart.search.ActionKind.INTERSECT && IndexId.XZT.equals(a.indexId)),
        "INTERSECT XZT after TIME must be illegal");
    SearchState withZ = SearchState.initial(ir)
        .apply(LegalActionGenerator.start(IndexId.ZORDER));
    List<kart.search.LegalAction> fromZ = gen.generate(withZ);
    assertFalse(fromZ.stream().anyMatch(a ->
            a.kind == kart.search.ActionKind.INTERSECT
                && (IndexId.XZ.equals(a.indexId) || IndexId.TSHAPE.equals(a.indexId))),
        "INTERSECT XZ/TSHAPE after ZORDER must be illegal");
    assertTrue(fromTime.stream().anyMatch(a ->
        a.kind == kart.search.ActionKind.START || a.kind == kart.search.ActionKind.REPLACE));
  }

  @Test
  void rboTemplatePlansAppendAdvanced() {
    BoundIr ir = FixtureQueries.fixtureTopK();
    List<PlanEnvelope> plans = RboFixedArm.templatePlans(ir);
    Set<String> ids = new HashSet<String>();
    for (PlanEnvelope p : plans) {
      ids.add(p.plan_id);
    }
    assertTrue(ids.contains("P_TZ"));
    assertTrue(ids.contains("P_XZ"));
    assertTrue(ids.contains("P_TS"));
    assertTrue(ids.contains("P_XZT"));
    assertTrue(ids.contains("P_XZTXZ"));
    assertEquals("P_FULL", plans.get(plans.size() - 1).plan_id);
    assertEquals("P_TZ", plans.get(0).plan_id);
  }
}
