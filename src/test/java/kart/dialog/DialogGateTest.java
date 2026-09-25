package kart.dialog;

import kart.ir.BoundIr;
import kart.ir.ClarificationDetector;
import kart.ir.DraftIr;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DialogGateTest {

  @Test
  void earlyRejectDerivedSpeed() {
    String r = Dialog.earlyUnsupportedReason(
        "Trajectories of taxi 8857 with average speed under 10 km/h between 21:00 and 22:00");
    assertNotNull(r);
    assertTrue(r.toLowerCase().contains("speed") || r.toLowerCase().contains("derived"));
  }

  @Test
  void plainVehicleTimeNotRejectedAsSpeed() {
    assertNull(Dialog.earlyUnsupportedReason(
        "Find trajectories of taxi 8857 between 2008-02-03T21:00:00+08:00 and 2008-02-03T22:00:00+08:00"));
  }

  @Test
  void earlyRejectCount() {
    assertNotNull(Dialog.earlyUnsupportedReason("COUNT how many trajectories"));
  }

  @Test
  void earlyRejectContinuousPath() {
    String r = Dialog.earlyUnsupportedReason(
        "Find trajectories whose continuous path segment crosses the ring road");
    assertNotNull(r);
    assertTrue(r.toLowerCase().contains("continuous"));
  }

  @Test
  void earlyRejectContinuousFrechet() {
    String r = Dialog.earlyUnsupportedReason(
        "Use continuous Fréchet distance to find neighbors of 8857-8857_14");
    assertNotNull(r);
    assertTrue(r.toLowerCase().contains("fr"));
  }

  @Test
  void discreteFrechetNotRejected() {
    assertNull(Dialog.earlyUnsupportedReason(
        "Use FRECHET distance to find neighbors of 8857-8857_14"));
  }

  @Test
  void semanticSummaryIncludesPredicates() {
    BoundIr b = new BoundIr();
    b.source = new BoundIr.Source();
    b.source.dataset_id = "tdrive_v1";
    b.result = new BoundIr.Result();
    b.result.mode = "TRAJECTORY_IDS";
    BoundIr.Predicate p = new BoundIr.Predicate();
    p.field = "vehicle_id";
    p.op = "EQ";
    p.value = "8857";
    b.predicates.add(p);
    b.temporal = new BoundIr.Temporal();
    b.temporal.start_ms = 1202044800000L;
    b.temporal.end_ms = 1202045400000L;
    String s = Dialog.semanticSummary(b);
    assertTrue(s.contains("vehicle_id"));
    assertTrue(s.contains("8857"));
    assertTrue(s.contains("2008-02-03"));
  }

  @Test
  void clarifyAnswerBindsOnlyAskedField() {
    Map<String, String> ctx = new LinkedHashMap<String, String>();
    ClarificationDetector.Question q =
        new ClarificationDetector.Question("similarity.metric", "metric?");
    Dialog.applyClarificationAnswer(ctx, Collections.singletonList(q), "DTW");
    assertEquals("DTW", ctx.get("similarity.metric"));
    assertFalse(ctx.containsKey("temporal"));
    assertFalse(ctx.containsKey("spatial"));
  }

  @Test
  void utteranceGroundingStripsInventedMetric() {
    ClarificationDetector c = new ClarificationDetector();
    DraftIr d = new DraftIr();
    d.result = new DraftIr.Result();
    d.result.mode = "TOP_K";
    d.result.k = 3;
    d.similarity = new DraftIr.Similarity();
    d.similarity.metric = "DTW";
    d.similarity.reference_trajectory_id = "8857-8857_14";
    d.temporal = new DraftIr.Temporal();
    d.temporal.start = "2008-02-03T20:50:00+08:00";
    d.temporal.end = "2008-02-03T21:20:00+08:00";
    String utt = "Find k=3 trajectories most similar to 8857-8857_14 between "
        + "2008-02-03T20:50:00+08:00 and 2008-02-03T21:20:00+08:00";
    c.applyUtteranceGrounding(d, utt, new LinkedHashMap<String, String>());
    c.enrichMissing(d);
    assertNull(d.similarity.metric);
    List<ClarificationDetector.Question> qs =
        c.detect(d, Collections.<String>emptySet());
    boolean metricQ = false;
    boolean spatialQ = false;
    for (ClarificationDetector.Question q : qs) {
      if (q.field.contains("metric")) {
        metricQ = true;
      }
      if (q.field.contains("spatial")) {
        spatialQ = true;
      }
    }
    assertTrue(metricQ);
    assertTrue(spatialQ);
  }

  @Test
  void utteranceGroundingStripsInventedRegion() {
    ClarificationDetector c = new ClarificationDetector();
    DraftIr d = new DraftIr();
    d.result = new DraftIr.Result();
    d.result.mode = "TRAJECTORY_IDS";
    d.spatial = new DraftIr.Spatial();
    d.spatial.region_name = "beijing_core";
    c.applyUtteranceGrounding(d, "Trajectories through 火星广场 yesterday",
        new LinkedHashMap<String, String>());
    assertTrue(d.spatial == null || d.spatial.region_name == null);
    assertTrue(d.missing.contains("spatial"));
  }

  @Test
  void earlyRejectUnsupportedMetric() {
    String r = Dialog.earlyUnsupportedReason(
        "Top-3 trajectories most similar to 8857-8857_14 using EDIT_DISTANCE");
    assertNotNull(r);
    assertTrue(r.contains("EDIT_DISTANCE"));
  }

  @Test
  void earlyRejectPairProximity() {
    String r = Dialog.earlyUnsupportedReason(
        "Find pairs of taxis that were within 50m of each other in beijing_core tonight");
    assertNotNull(r);
    assertTrue(r.toLowerCase().contains("pair") || r.toLowerCase().contains("proximity"));
  }

  @Test
  void topKStripsVehiclePredicateFromReferenceTid() {
    ClarificationDetector c = new ClarificationDetector();
    DraftIr d = new DraftIr();
    d.result = new DraftIr.Result();
    d.result.mode = "TOP_K";
    d.result.k = 3;
    d.similarity = new DraftIr.Similarity();
    d.similarity.metric = "DTW";
    d.similarity.reference_trajectory_id = "8857-8857_14";
    DraftIr.Predicate p = new DraftIr.Predicate();
    p.field = "vehicle_id";
    p.op = "EQ";
    p.value = "8857";
    d.predicates.add(p);
    String utt = "Top-3 DTW neighbors of 8857-8857_14 intersecting tdrive_smoke_anchor "
        + "between 2008-02-03T21:20:00+08:00 and 2008-02-03T21:50:00+08:00";
    c.applyUtteranceGrounding(d, utt, new LinkedHashMap<String, String>());
    assertTrue(d.predicates == null || d.predicates.isEmpty());
  }

  @Test
  void topKKeepsExplicitVehicleCandidateFilter() {
    ClarificationDetector c = new ClarificationDetector();
    DraftIr d = new DraftIr();
    d.result = new DraftIr.Result();
    d.result.mode = "TOP_K";
    d.result.k = 3;
    d.similarity = new DraftIr.Similarity();
    d.similarity.metric = "DTW";
    d.similarity.reference_trajectory_id = "8857-8857_14";
    DraftIr.Predicate p = new DraftIr.Predicate();
    p.field = "vehicle_id";
    p.op = "EQ";
    p.value = "8857";
    d.predicates.add(p);
    String utt = "Among taxi 8857 trajectories, find 3 DTW neighbors of 8857-8857_14 "
        + "intersecting tdrive_smoke_anchor";
    c.applyUtteranceGrounding(d, utt, new LinkedHashMap<String, String>());
    assertEquals(1, d.predicates.size());
    assertEquals("8857", d.predicates.get(0).value);
  }
}