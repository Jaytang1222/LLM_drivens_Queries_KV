package kart.validation;

import kart.compile.LayoutContext;
import kart.compile.PhysicalPlan;
import kart.compile.QueryCompiler;
import kart.compile.ScanTask;
import kart.ir.BoundIr;
import kart.plan.Op;
import kart.plan.PlanBuilder;
import kart.plan.PlanEnvelope;
import kart.plan.PlanNode;
import kart.snapshot.FixtureBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * design.md §9 negatives + fixture P_TZ positive.
 */
class PlanValidatorTest {

  private LayoutContext layout;
  private PlanValidator validator;
  private QueryCompiler compiler;

  @BeforeEach
  void setUp() {
    layout = LayoutContext.from(FixtureBuilder.fixtureManifest());
    validator = new PlanValidator(layout);
    compiler = new QueryCompiler(layout);
  }

  // ---------------------------------------------------------------- structure

  @Test
  void structureRejectsIntersectWrongInputType() {
    BoundIr ir = stIdsQuery();
    PlanEnvelope env = new PlanEnvelope();
    env.plan_id = "bad_intersect_type";
    env.query_id = ir.query_id;
    env.manifest_id = FixtureBuilder.MANIFEST_ID;
    // FULL_SCAN → CHUNK_BATCH; TIME → CHUNK_REF_SET; INTERSECT expects CHUNK_REF_SET
    env.nodes.add(leaf("n01", Op.FULL_SCAN_CHUNKS));
    env.nodes.add(leaf("n02", Op.TIME_RANGE_SCAN, "/temporal"));
    env.nodes.add(node("n03", Op.INTERSECT, Arrays.asList("n01", "n02"), null));
    env.root = "n03";

    ValidationReport report = new ValidationReport();
    assertFalse(validator.structureCheck(env, report));
    assertTrue(hasFail(report, "StructureCheck"));
  }

  @Test
  void structureRejectsCycle() {
    PlanEnvelope env = new PlanEnvelope();
    env.plan_id = "bad_cycle";
    env.query_id = "q";
    env.manifest_id = FixtureBuilder.MANIFEST_ID;
    env.nodes.add(leaf("n01", Op.TIME_RANGE_SCAN, "/temporal"));
    env.nodes.add(node("n02", Op.DEDUPLICATE, Arrays.asList("n03"), null));
    env.nodes.add(node("n03", Op.DEDUPLICATE, Arrays.asList("n02"), null));
    // attach access so graph is non-trivial; cycle is n02↔n03
    env.nodes.add(node("n04", Op.DEDUPLICATE, Arrays.asList("n01"), null));
    env.root = "n02";

    ValidationReport report = new ValidationReport();
    assertFalse(validator.structureCheck(env, report));
    assertTrue(findingContains(report, "cycle"));
  }

  @Test
  void structureRejectsMultiRoot() {
    PlanEnvelope env = new PlanEnvelope();
    env.plan_id = "bad_multi_root";
    env.query_id = "q";
    env.manifest_id = FixtureBuilder.MANIFEST_ID;
    env.nodes.add(leaf("n01", Op.TIME_RANGE_SCAN, "/temporal"));
    env.nodes.add(leaf("n02", Op.ZORDER_RANGE_SCAN, "/spatial"));
    env.root = "n01";

    ValidationReport report = new ValidationReport();
    assertFalse(validator.structureCheck(env, report));
    assertTrue(findingContains(report, "single root"));
  }

  // ----------------------------------------------------------------- semantic

  @Test
  void semanticRejectsTopKMissingSimilarity() {
    BoundIr ir = stTopKQuery();
    // Valid types for structure are irrelevant: exercise SemanticCheck directly.
    PlanEnvelope env = new PlanEnvelope();
    env.plan_id = "bad_topk_no_sim";
    env.query_id = ir.query_id;
    env.manifest_id = FixtureBuilder.MANIFEST_ID;
    env.nodes.add(leaf("n01", Op.TIME_RANGE_SCAN, "/temporal"));
    env.nodes.add(node("n02", Op.DEDUPLICATE, Arrays.asList("n01"), null));
    env.nodes.add(node("n03", Op.FETCH_TRAJECTORY_CHUNK, Arrays.asList("n02"), null));
    env.nodes.add(filter("n04", Arrays.asList("n03"), Arrays.asList("/temporal", "/spatial")));
    env.nodes.add(node("n05", Op.PROJECT_TRAJECTORY_IDS, Arrays.asList("n04"), null));
    env.nodes.add(node("n06", Op.BATCH_GET_TRAJECTORY, Arrays.asList("n05"), null));
    // TOP_K ← BATCH_GET (skips SIMILARITY)
    env.nodes.add(node("n07", Op.TOP_K, Arrays.asList("n06"), params("k", Integer.valueOf(2))));
    env.root = "n07";

    ValidationReport report = new ValidationReport();
    assertFalse(validator.semanticCheck(env, ir, report));
    assertTrue(findingContains(report, "SIMILARITY"));
  }

  @Test
  void semanticRejectsTrajectoryIdsWithSimilarity() {
    BoundIr ir = stIdsQuery();
    PlanEnvelope env = findPlan(ir, "P_T");
    // Graft a SIMILARITY node (semantic forbids it in TRAJECTORY_IDS mode).
    env.nodes.add(node("n_sim", Op.SIMILARITY, Arrays.asList(env.root), null));
    // semanticCheck only — avoid multi-root / type StructureCheck noise.
    ValidationReport report = new ValidationReport();
    assertFalse(validator.semanticCheck(env, ir, report));
    assertTrue(findingContains(report, "TRAJECTORY_IDS mode forbids"));
  }

  @Test
  void semanticRejectsExactStFilterMissingPredicateRef() {
    BoundIr ir = stIdsQuery();
    PlanEnvelope env = findPlan(ir, "P_TZ");
    PlanNode filter = null;
    for (PlanNode n : env.nodes) {
      if (n.op == Op.EXACT_ST_FILTER) {
        filter = n;
        break;
      }
    }
    assertTrue(filter != null);
    // Drop /spatial — IR still has spatial
    filter.params.put("predicate_refs", new ArrayList<String>(Collections.singletonList("/temporal")));

    PhysicalPlan phys = compiler.compile(env, ir);
    ValidationReport report = new ValidationReport();
    Optional<SafePlanHandle> h = validator.validate(env, ir, phys, report);
    assertFalse(h.isPresent());
    assertTrue(findingContains(report, "predicate_refs") || findingContains(report, "missing"));
  }

  // ---------------------------------------------------------- physical / coverage

  @Test
  void physicalSafetyRejectsStartGeStop() {
    BoundIr ir = stIdsQuery();
    PlanEnvelope env = findPlan(ir, "P_T");
    PhysicalPlan phys = compiler.compile(env, ir);
    assertFalse(phys.scanTasks.isEmpty());
    // Keep valid covering tasks (CoverageCheck runs first); append a bad range.
    ScanTask bad = new ScanTask();
    bad.table = layout.tableTime;
    bad.sourceNodeId = phys.scanTasks.get(0).sourceNodeId;
    bad.shard = 0;
    bad.startHex = "00ff";
    bad.stopHex = "00ff"; // start == stop
    bad.coverageRef = "bad";
    phys.scanTasks.add(bad);

    ValidationReport report = new ValidationReport();
    Optional<SafePlanHandle> h = validator.validate(env, ir, phys, report);
    assertFalse(h.isPresent());
    assertTrue(hasFail(report, "PhysicalSafetyCheck"), report.toString());
    assertTrue(findingContains(report, "start >= stop"), report.toString());
  }

  @Test
  void coverageRejectsWrongTimeBuckets() {
    BoundIr ir = stIdsQuery();
    PlanEnvelope env = findPlan(ir, "P_T");
    PhysicalPlan phys = compiler.compile(env, ir);
    // Drop all time tasks → buckets no longer covered
    List<ScanTask> kept = new ArrayList<ScanTask>();
    for (ScanTask t : phys.scanTasks) {
      if (!layout.tableTime.equals(t.table)) {
        kept.add(t);
      }
    }
    // Leave one deliberately wrong bucket (far future) so access node is non-empty
    // but does not cover the IR interval.
    byte[] start = kart.codec.RowKeyCodec.encodeTime(0, 9999L, 0, 0);
    byte[] stop = kart.codec.RowKeyCodec.encodeTime(0, 10000L, 0, 0);
    String timeNode = null;
    for (PlanNode n : env.nodes) {
      if (n.op == Op.TIME_RANGE_SCAN) {
        timeNode = n.id;
        break;
      }
    }
    kept.add(new ScanTask(layout.tableTime, start, stop, timeNode, 0, "bucket:9999"));
    phys.scanTasks = kept;

    ValidationReport report = new ValidationReport();
    Optional<SafePlanHandle> h = validator.validate(env, ir, phys, report);
    assertFalse(h.isPresent());
    assertTrue(hasFail(report, "CoverageCheck"));
  }

  @Test
  void externalJsonSafeTrueIgnored() throws Exception {
    BoundIr ir = stIdsQuery();
    PlanEnvelope good = findPlan(ir, "P_T");
    // Inject unknown "safe": true — Jackson ignores it; safety is only SafePlanHandle.
    String json = good.toJson().replaceFirst("\\{", "{\"safe\":true,");
    PlanEnvelope loaded = PlanEnvelope.fromJson(json);
    // Corrupt filter so validation must fail despite safe flag in JSON.
    for (PlanNode n : loaded.nodes) {
      if (n.op == Op.EXACT_ST_FILTER) {
        n.params.put("predicate_refs", new ArrayList<String>());
        break;
      }
    }
    PhysicalPlan phys = compiler.compile(loaded, ir);
    ValidationReport report = new ValidationReport();
    Optional<SafePlanHandle> h = validator.validate(loaded, ir, phys, report);
    assertFalse(h.isPresent(), "external safe:true must not bypass PlanValidator");
  }

  // ----------------------------------------------------------------- positive

  @Test
  void fixturePTzValidatesOk() {
    BoundIr ir = stTopKQuery();
    PlanEnvelope env = findPlan(ir, "P_TZ");
    PhysicalPlan phys = compiler.compile(env, ir);
    ValidationReport report = new ValidationReport();
    Optional<SafePlanHandle> h = validator.validate(env, ir, phys, report);
    assertTrue(h.isPresent(), report.toString());
    assertTrue(report.ok());
  }

  // ------------------------------------------------------------------ helpers

  private static PlanEnvelope findPlan(BoundIr ir, String planId) {
    for (PlanEnvelope e : PlanBuilder.buildCandidates(ir)) {
      if (planId.equals(e.plan_id)) {
        return e;
      }
    }
    throw new IllegalStateException("plan not found: " + planId);
  }

  private static BoundIr stIdsQuery() {
    BoundIr ir = baseSt();
    ir.result = new BoundIr.Result();
    ir.result.mode = "TRAJECTORY_IDS";
    return ir;
  }

  private static BoundIr stTopKQuery() {
    BoundIr ir = baseSt();
    ir.similarity = new BoundIr.Similarity();
    ir.similarity.metric = "DTW";
    ir.similarity.reference_tid = 4L;
    ir.similarity.exclude_reference = true;
    ir.result = new BoundIr.Result();
    ir.result.mode = "TOP_K";
    ir.result.k = 2;
    return ir;
  }

  private static BoundIr baseSt() {
    BoundIr ir = new BoundIr();
    ir.ir_version = "1.0";
    ir.query_id = "q_validator";
    ir.source = new BoundIr.Source();
    ir.source.dataset_id = "fixture_v1";
    ir.source.entity = "trajectory";
    ir.temporal = new BoundIr.Temporal();
    ir.temporal.start_ms = FixtureBuilder.T0;
    ir.temporal.end_ms = FixtureBuilder.T0 + 10 * 60 * 1000L;
    ir.spatial = new BoundIr.Spatial();
    ir.spatial.min_x = 4;
    ir.spatial.min_y = 4;
    ir.spatial.max_x = 8;
    ir.spatial.max_y = 8;
    ir.snapshot = new BoundIr.Snapshot();
    ir.snapshot.manifest_id = FixtureBuilder.MANIFEST_ID;
    ir.snapshot.semantics_version = "point_dtw_v1";
    return ir;
  }

  private static PlanNode leaf(String id, Op op) {
    return node(id, op, new ArrayList<String>(), null);
  }

  private static PlanNode leaf(String id, Op op, String predRef) {
    return node(id, op, new ArrayList<String>(), params("predicate_ref", predRef));
  }

  private static PlanNode filter(String id, List<String> inputs, List<String> refs) {
    Map<String, Object> p = new HashMap<String, Object>();
    p.put("predicate_refs", new ArrayList<String>(refs));
    return node(id, Op.EXACT_ST_FILTER, inputs, p);
  }

  private static PlanNode node(String id, Op op, List<String> inputs, Map<String, Object> params) {
    return new PlanNode(id, op, inputs, params);
  }

  private static Map<String, Object> params(String k, Object v) {
    Map<String, Object> m = new HashMap<String, Object>();
    m.put(k, v);
    return m;
  }

  private static boolean hasFail(ValidationReport report, String check) {
    for (ValidationReport.Finding f : report.findings()) {
      if (!f.ok && check.equals(f.check)) {
        return true;
      }
    }
    return false;
  }

  private static boolean findingContains(ValidationReport report, String needle) {
    for (ValidationReport.Finding f : report.findings()) {
      if (f.message != null && f.message.contains(needle)) {
        return true;
      }
    }
    return false;
  }
}
