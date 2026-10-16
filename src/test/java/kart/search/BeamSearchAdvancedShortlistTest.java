package kart.search;

import kart.cost.FastCost;
import kart.ir.BoundIr;
import kart.plan.PlanBuilder;
import kart.plan.PlanEnvelope;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

public final class BeamSearchAdvancedShortlistTest {

  @Test
  void shortlistKeepsFullClassicHashAndOnlyFewAdvancedPlans() {
    BoundIr ir = new BoundIr();
    ir.temporal = new BoundIr.Temporal();
    ir.temporal.start_ms = 1_200_000_000_000L;
    ir.temporal.end_ms = ir.temporal.start_ms + 48L * 3600L * 1000L;
    ir.spatial = new BoundIr.Spatial();
    ir.spatial.min_x = 0.0d;
    ir.spatial.min_y = 0.0d;
    ir.spatial.max_x = 1_000.0d;
    ir.spatial.max_y = 1_000.0d;
    BoundIr.Predicate vehicle = new BoundIr.Predicate();
    vehicle.field = "vehicle_id";
    vehicle.op = "EQ";
    vehicle.value = "vehicle-7";
    ir.predicates.add(vehicle);

    List<PlanEnvelope> candidates = new ArrayList<PlanEnvelope>();
    candidates.add(PlanBuilder.buildForIndexes(ir, Collections.<String>emptyList(), null, null));
    candidates.add(PlanBuilder.buildForIndexes(ir, Collections.singleton(IndexId.HASH), null, null));
    candidates.add(PlanBuilder.buildForAccess(ir, true, true, true));
    candidates.add(PlanBuilder.buildForAccess(ir, true, true, false));
    candidates.add(PlanBuilder.buildForIndexes(ir, Collections.singleton(IndexId.XZ), null, null));
    candidates.add(PlanBuilder.buildForIndexes(ir, Collections.singleton(IndexId.TSHAPE), null, null));
    candidates.add(PlanBuilder.buildForIndexes(ir, Collections.singleton(IndexId.XZT), null, null));
    candidates.add(PlanBuilder.buildForIndexes(ir,
        Arrays.asList(IndexId.XZT, IndexId.XZ), "HASH_SET", null));

    List<PlanEnvelope> shortlisted = BeamSearch.advancedValidationShortlist(
        ir, candidates, new FastCost(null), 5);
    List<String> ids = new ArrayList<String>();
    for (PlanEnvelope p : shortlisted) {
      ids.add(p.plan_id);
    }

    assertTrue(shortlisted.size() <= 5, "ADV validation candidates must remain bounded");
    assertTrue(ids.contains("P_FULL"), "retain the safe full-scan fallback");
    assertTrue(ids.contains("P_H"), "retain vehicle-equality hash access");
    assertTrue(ids.stream().anyMatch(id -> id.startsWith("P_XZ") || id.startsWith("P_TS")
        || id.startsWith("P_XZT")), "retain an advanced-index alternative");
  }
}
