package kart.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kart.compile.LayoutContext;
import kart.compile.PhysicalPlan;
import kart.compile.QueryCompiler;
import kart.compile.ScanTask;
import kart.ir.BoundIr;
import kart.ir.IrSchemaValidator;
import kart.plan.Op;
import kart.plan.PlanBuilder;
import kart.plan.PlanEnvelope;
import kart.plan.PlanNode;
import kart.snapshot.FixtureBuilder;
import kart.validation.PlanValidator;
import kart.validation.ValidationReport;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Local safety checks for the negative fixtures. Does not execute HBase or call an LLM.
 */
final class OpportunityVerifyFixtureTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void invalidPlanVerifyHasFourCases() throws Exception {
    Path p = resolve("experiments/workloads/invalid_plan_verify_v1.json");
    JsonNode root = MAPPER.readTree(Files.readAllBytes(p));
    assertEquals("invalid_plan_verify_v1", root.get("workload_id").asText());
    assertEquals(4, root.get("cases").size());
    for (JsonNode c : root.get("cases")) {
      assertTrue(c.has("case_id"));
      assertTrue(c.has("expected_reject_stage"));
      assertTrue(c.get("expected_reason_contains").isArray());
      assertTrue(c.get("expected_reason_contains").size() >= 1);
      assertRejectedByRealValidator(c);
    }
  }

  private static void assertRejectedByRealValidator(JsonNode fixture) throws Exception {
    String mutation = fixture.get("mutation").asText();
    boolean topK = "topk_without_similarity".equals(mutation);
    BoundIr ir = fixtureQuery(topK);
    LayoutContext layout = LayoutContext.from(FixtureBuilder.fixtureManifest());
    PlanValidator validator = new PlanValidator(layout);
    QueryCompiler compiler = new QueryCompiler(layout);
    PlanEnvelope plan = findPlan(ir, topK ? "P_TZ" : "P_T");
    ValidationReport report = new ValidationReport();
    boolean rejected;
    if ("omit_temporal_from_exact_filter".equals(mutation)) {
      for (PlanNode n : plan.nodes) {
        if (n.op == Op.EXACT_ST_FILTER) {
          n.params.put("predicate_refs", new ArrayList<String>(Arrays.asList("/spatial")));
        }
      }
      rejected = !validator.validate(plan, ir, compiler.compile(plan, ir), report).isPresent();
    } else if ("remove_exact_filter_node".equals(mutation)
        || "topk_without_similarity".equals(mutation)) {
      Op removedOp = topK ? Op.SIMILARITY : Op.EXACT_ST_FILTER;
      PlanNode removed = null;
      for (PlanNode n : plan.nodes) {
        if (n.op == removedOp) {
          removed = n;
          break;
        }
      }
      assertTrue(removed != null, mutation);
      for (PlanNode n : plan.nodes) {
        for (int i = 0; i < n.inputs.size(); i++) {
          if (removed.id.equals(n.inputs.get(i))) {
            n.inputs.set(i, removed.inputs.get(0));
          }
        }
      }
      plan.nodes.remove(removed);
      rejected = topK ? !validator.semanticCheck(plan, ir, report)
          : !validator.structureCheck(plan, report);
    } else if ("invalid_physical_scan_range".equals(mutation)) {
      PhysicalPlan phys = compiler.compile(plan, ir);
      assertFalse(phys.scanTasks.isEmpty());
      ScanTask bad = phys.scanTasks.get(0);
      bad.stopHex = bad.startHex;
      // Coverage may reject this range first in validate(). Exercise the
      // physical guard directly as well, so this fixture proves its own target.
      rejected = !validator.physicalSafetyCheck(plan, phys, report);
      ValidationReport fullReport = new ValidationReport();
      assertFalse(validator.validate(plan, ir, phys, fullReport).isPresent());
    } else {
      throw new AssertionError("Unknown mutation: " + mutation);
    }
    assertTrue(rejected, fixture.get("case_id").asText() + ": " + report);
    assertFalse(report.ok(), fixture.get("case_id").asText());
    boolean expectedReason = false;
    for (ValidationReport.Finding finding : report.findings()) {
      if (finding.ok) {
        continue;
      }
      String actual = (finding.check + " " + finding.message).toLowerCase();
      for (JsonNode needle : fixture.get("expected_reason_contains")) {
        if (actual.contains(needle.asText().toLowerCase())) {
          expectedReason = true;
        }
      }
    }
    assertTrue(expectedReason, fixture.get("case_id").asText() + ": " + report);
  }

  @Test
  void nlNegativeVerifySeparatesE1FromE2E3() throws Exception {
    Path p = resolve("experiments/workloads/nl_negative_verify_v1.json");
    JsonNode root = MAPPER.readTree(Files.readAllBytes(p));
    assertEquals(4, root.get("items").size());
    assertTrue(root.get("invalid_bound_ir_samples").size() >= 2);
    for (JsonNode it : root.get("items")) {
      assertTrue(it.has("expected_status"));
      assertTrue(it.has("utterance"));
    }
    IrSchemaValidator schemas = new IrSchemaValidator(resolve("schemas"));
    for (JsonNode sample : root.get("invalid_bound_ir_samples")) {
      assertEquals("INPUT_VALIDATION_ERROR", sample.get("expected_status").asText());
      assertFalse(schemas.validateBoundIr(sample.get("bound_ir").toString()).isEmpty(),
          sample.get("case_id").asText());
    }
  }

  private static PlanEnvelope findPlan(BoundIr ir, String id) {
    for (PlanEnvelope p : PlanBuilder.buildCandidates(ir)) {
      if (id.equals(p.plan_id)) {
        return p;
      }
    }
    throw new AssertionError("missing plan " + id);
  }

  private static BoundIr fixtureQuery(boolean topK) {
    BoundIr ir = new BoundIr();
    ir.ir_version = "1.0";
    ir.query_id = "negative_fixture";
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
    ir.result = new BoundIr.Result();
    ir.result.mode = topK ? "TOP_K" : "TRAJECTORY_IDS";
    if (topK) {
      ir.result.k = 2;
      ir.similarity = new BoundIr.Similarity();
      ir.similarity.metric = "DTW";
      ir.similarity.reference_tid = 4L;
      ir.similarity.exclude_reference = true;
    }
    return ir;
  }

  @Test
  void verifyWorkloadOracleAlignedWhenPresent() throws Exception {
    Path wl = resolve("experiments/workloads/bound_ir_cbo_opportunity_verify_v1.json");
    Path ora = resolve("experiments/workloads/bound_ir_cbo_opportunity_verify_v1.oracle.json");
    if (!Files.isRegularFile(wl) || !Files.isRegularFile(ora)) {
      return; // generator not run yet in this environment
    }
    JsonNode w = MAPPER.readTree(Files.readAllBytes(wl));
    JsonNode o = MAPPER.readTree(Files.readAllBytes(ora));
    java.util.Set<String> qids = new java.util.LinkedHashSet<String>();
    for (JsonNode q : w.get("queries")) {
      qids.add(q.get("query_id").asText());
    }
    java.util.Set<String> oids = new java.util.LinkedHashSet<String>();
    for (JsonNode a : o.get("answers")) {
      oids.add(a.get("query_id").asText());
    }
    assertEquals(qids, oids);
  }

  private static Path resolve(String rel) {
    String root = System.getProperty("kart.root");
    if (root != null && !root.isEmpty()) {
      return Paths.get(root).resolve(rel);
    }
    Path cwd = Paths.get(".").toAbsolutePath().normalize();
    Path p = cwd.resolve(rel);
    if (Files.isRegularFile(p)) {
      return p;
    }
    // surefire may run from module root
    return cwd.resolve(rel);
  }
}
