package kart.bench;

import kart.config.AppConfig;
import kart.cost.CostCard;
import kart.cost.PlanSelector;
import kart.ir.BoundIr;
import kart.plan.Op;
import kart.plan.PlanBuilder;
import kart.plan.PlanEnvelope;
import kart.plan.PlanNode;
import kart.query.QueryEngine;
import kart.query.QueryIrFixtureTest;
import kart.search.LegalActionGenerator;
import kart.search.PlannerMode;
import kart.search.SearchOptions;
import kart.search.SearchState;
import kart.snapshot.FixtureBuilder;
import kart.snapshot.SnapshotBuilder;
import kart.compile.LayoutContext;
import kart.exec.ExecLimits;
import kart.exec.MemoryBackend;
import kart.validation.PlanValidator;
import kart.validation.ValidationReport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ablation leave-one-out knobs; defaults must not change comparative behavior. */
public final class AblationHarnessTest {

  @TempDir
  Path tmp;

  @Test
  void suiteYamlIsFullKartLeaveOneOut() throws Exception {
    Path root = Paths.get(".").toAbsolutePath().normalize();
    SuiteSpec s = SuiteSpec.load(root.resolve("experiments/suites/ablation.yaml"));
    assertEquals("ablation", s.kind);
    assertEquals("kart", s.base_arm);
    assertEquals("experiments/workloads/bound_ir_v1.json", s.workload);
    List<String> ids = s.enabledFactorIds();
    assertTrue(ids.contains("no_llm_rule"));
    assertTrue(ids.contains("no_llm_best_first"));
    assertTrue(ids.contains("llm_direct"));
    assertTrue(ids.contains("no_fast_cost"));
    assertTrue(ids.contains("no_final_cost"));
    assertTrue(ids.contains("single_index"));
    assertTrue(ids.contains("uncalibrated"));
    assertTrue(ids.contains("no_coverage"));
    assertFalse(ids.contains("narrow_beam"));
    boolean coverageUnsafe = false;
    for (SuiteSpec.Factor f : s.factors) {
      if ("no_coverage".equals(f.id)) {
        coverageUnsafe = f.unsafe;
        assertTrue(Boolean.TRUE.equals(f.overrides.get("skip_coverage_check"))
            || "true".equals(String.valueOf(f.overrides.get("skip_coverage_check"))));
      }
    }
    assertTrue(coverageUnsafe);
  }

  @Test
  void overlayDefaultsLeaveComparativePlannerUnchanged() {
    AppConfig.PlannerConfig base = AppConfig.PlannerConfig.defaults();
    AppConfig.PlannerConfig out = PlannerOverlay.apply(base, new LinkedHashMap<String, Object>());
    assertTrue(out.use_fast_cost);
    assertTrue(out.allow_intersect);
    assertFalse(out.skip_coverage_check);
    assertEquals(AppConfig.PlannerConfig.FINAL_SELECT_ESTIMATED_MS, out.final_select);
  }

  @Test
  void overlayUncalibratedLoadsFrozenPriors() {
    Path root = Paths.get(".").toAbsolutePath().normalize();
    Map<String, Object> ov = new LinkedHashMap<String, Object>();
    ov.put("uncalibrated", Boolean.TRUE);
    AppConfig.PlannerConfig fitted = AppConfig.PlannerConfig.defaults();
    fitted.cost.calibrated = true;
    fitted.cost.alpha_rpc = 99.0;
    AppConfig.PlannerConfig out = PlannerOverlay.apply(fitted, ov, root);
    assertFalse(out.cost.calibrated);
    assertEquals(1.0, out.cost.alpha_rpc, 1e-12);
    assertEquals(99.0, fitted.cost.alpha_rpc, 1e-12);
  }

  @Test
  void overlaySingleIndexAndNoFinalCostAndNoFastCost() {
    Map<String, Object> ov = new LinkedHashMap<String, Object>();
    ov.put("allow_intersect", Boolean.FALSE);
    ov.put("final_select", "plan_id");
    ov.put("use_fast_cost", Boolean.FALSE);
    AppConfig.PlannerConfig out = PlannerOverlay.apply(AppConfig.PlannerConfig.defaults(), ov);
    assertFalse(out.allow_intersect);
    assertEquals("plan_id", out.final_select);
    assertFalse(out.use_fast_cost);
    assertTrue(SearchOptions.from(out).selectByPlanId());
  }

  @Test
  void singleIndexLegalActionsHaveNoIntersect() {
    BoundIr ir = QueryIrFixtureTest.fixtureQuery();
    SearchState empty = SearchState.initial(ir);
    SearchState withTime = empty.apply(kart.search.LegalActionGenerator.start(kart.search.IndexId.TIME));
    List<kart.search.LegalAction> legal = new LegalActionGenerator(false).generate(withTime);
    for (kart.search.LegalAction a : legal) {
      assertFalse(a.kind == kart.search.ActionKind.INTERSECT, a.actionId);
    }
  }

  @Test
  void planBuilderSingleIndexOmitsIntersectNodes() {
    BoundIr ir = QueryIrFixtureTest.fixtureQuery();
    for (PlanEnvelope env : PlanBuilder.buildCandidates(ir, false)) {
      for (PlanNode n : env.nodes) {
        assertFalse(n.op == Op.INTERSECT, env.plan_id);
      }
    }
    boolean sawJoint = false;
    for (PlanEnvelope env : PlanBuilder.buildCandidates(ir, true)) {
      for (PlanNode n : env.nodes) {
        if (n.op == Op.INTERSECT) {
          sawJoint = true;
        }
      }
    }
    assertTrue(sawJoint);
  }

  @Test
  void finalCostOffPicksLexicographicPlanId() {
    List<PlanSelector.Scored> scored = new ArrayList<PlanSelector.Scored>();
    scored.add(costed("P_Z", 1.0));
    scored.add(costed("P_T", 100.0));
    scored.add(costed("P_FULL", 50.0));
    assertEquals("P_Z", PlanSelector.selectScored(scored, false).handle.plan().plan_id);
    assertEquals("P_FULL", PlanSelector.selectScored(scored, true).handle.plan().plan_id);
  }

  @Test
  void skipCoverageDoesNotFailWrongBuckets() {
    LayoutContext layout = LayoutContext.from(FixtureBuilder.fixtureManifest());
    BoundIr ir = QueryIrFixtureTest.fixtureQuery();
    PlanEnvelope env = PlanBuilder.buildForAccess(ir, true, false, false);
    kart.compile.PhysicalPlan phys = new kart.compile.QueryCompiler(layout).compile(env, ir);
    List<kart.compile.ScanTask> kept = new ArrayList<kart.compile.ScanTask>();
    for (kart.compile.ScanTask t : phys.scanTasks) {
      if (!layout.tableTime.equals(t.table)) {
        kept.add(t);
      }
    }
    byte[] start = kart.codec.RowKeyCodec.encodeTime(0, 9999L, 0, 0);
    byte[] stop = kart.codec.RowKeyCodec.encodeTime(0, 10000L, 0, 0);
    String timeNode = null;
    for (PlanNode n : env.nodes) {
      if (n.op == Op.TIME_RANGE_SCAN) {
        timeNode = n.id;
        break;
      }
    }
    kept.add(new kart.compile.ScanTask(layout.tableTime, start, stop, timeNode, 0, "bucket:9999"));
    phys.scanTasks = kept;

    ValidationReport fail = new ValidationReport();
    assertFalse(new PlanValidator(layout, false).validate(env, ir, phys, fail).isPresent());

    ValidationReport skip = new ValidationReport();
    assertTrue(new PlanValidator(layout, true).validate(env, ir, phys, skip).isPresent(),
        skip.toString());
    assertTrue(skip.toString().contains("SKIPPED_UNSAFE"));
  }

  @Test
  void ruleEngineHonorsAllowIntersectFalse() throws Exception {
    MemoryBackend kv = SnapshotBuilder.buildFixtureInMemory();
    try {
      LayoutContext layout = LayoutContext.from(FixtureBuilder.fixtureManifest());
      AppConfig.PlannerConfig p = AppConfig.PlannerConfig.defaults();
      p.allow_intersect = false;
      QueryEngine engine = new QueryEngine(kv, layout, ExecLimits.defaults(),
          null, p, null, PlannerMode.RULE);
      QueryEngine.RunResult rr = engine.run(QueryIrFixtureTest.fixtureQuery(), tmp.resolve("si"), true);
      assertNotNull(rr.selected);
      boolean indexed = false;
      for (PlanEnvelope env : rr.candidates) {
        for (PlanNode n : env.nodes) {
          assertFalse(n.op == Op.INTERSECT, env.plan_id);
        }
        if (env.plan_id != null && !env.plan_id.startsWith("P_FULL")) {
          indexed = true;
        }
      }
      assertTrue(indexed, "single-index search must still emit an indexed plan");
    } finally {
      kv.close();
    }
  }

  @Test
  void factorFilterPairsFullForSafeArm() throws Exception {
    Path root = Paths.get(".").toAbsolutePath().normalize();
    SuiteSpec s = SuiteSpec.load(root.resolve("experiments/suites/ablation.yaml"));
    SuiteRunner.Options opt = newOptions(root);
    opt.factorFilter.add("no_llm_rule");
    List<String> labels = cellLabels(s, opt);
    assertEquals(2, labels.size(), labels.toString());
    assertEquals("full", labels.get(0));
    assertEquals("no_llm_rule", labels.get(1));
  }

  @Test
  void riskOnlyFactorDoesNotPairFull() throws Exception {
    Path root = Paths.get(".").toAbsolutePath().normalize();
    SuiteSpec s = SuiteSpec.load(root.resolve("experiments/suites/ablation.yaml"));
    SuiteRunner.Options opt = newOptions(root);
    opt.factorFilter.add("no_coverage");
    opt.allowUnsafe = true;
    List<String> labels = cellLabels(s, opt);
    assertEquals(1, labels.size(), labels.toString());
    assertEquals("no_coverage", labels.get(0));
    assertFalse(SuiteRunner.shouldPairFull(s, opt));
    assertFalse(SuiteRunner.cellsIncludeFull(
        SuiteRunner.expandCells(s, new ArmRegistry(root), opt, false)));
  }

  @Test
  void pairFullFlagFollowsExpandedCells() throws Exception {
    Path root = Paths.get(".").toAbsolutePath().normalize();
    SuiteSpec s = SuiteSpec.load(root.resolve("experiments/suites/ablation.yaml"));
    ArmRegistry registry = new ArmRegistry(root);

    SuiteRunner.Options main = newOptions(root);
    assertTrue(SuiteRunner.cellsIncludeFull(SuiteRunner.expandCells(s, registry, main, false)));
    assertFalse(SuiteRunner.shouldPairFull(s, main),
        "shouldPairFull is only for --factor auto-include; default main already has full");

    SuiteRunner.Options safe = newOptions(root);
    safe.factorFilter.add("no_llm_rule");
    assertTrue(SuiteRunner.shouldPairFull(s, safe));
    assertTrue(SuiteRunner.cellsIncludeFull(SuiteRunner.expandCells(s, registry, safe, false)));

    SuiteRunner.Options solo = newOptions(root);
    solo.factorFilter.add("no_fast_cost");
    solo.noPairFull = true;
    assertFalse(SuiteRunner.cellsIncludeFull(SuiteRunner.expandCells(s, registry, solo, false)));
  }

  @Test
  void noPairFullKeepsSingleSafeFactor() throws Exception {
    Path root = Paths.get(".").toAbsolutePath().normalize();
    SuiteSpec s = SuiteSpec.load(root.resolve("experiments/suites/ablation.yaml"));
    SuiteRunner.Options opt = newOptions(root);
    opt.factorFilter.add("no_fast_cost");
    opt.noPairFull = true;
    List<String> labels = cellLabels(s, opt);
    assertEquals(1, labels.size(), labels.toString());
    assertEquals("no_fast_cost", labels.get(0));
  }

  @Test
  void defaultAblationSkipsUnsafeAndKeepsFull() throws Exception {
    Path root = Paths.get(".").toAbsolutePath().normalize();
    SuiteSpec s = SuiteSpec.load(root.resolve("experiments/suites/ablation.yaml"));
    List<String> labels = cellLabels(s, newOptions(root));
    assertTrue(labels.contains("full"));
    assertTrue(labels.contains("uncalibrated"));
    assertFalse(labels.contains("no_coverage"));
    assertEquals(8, labels.size(), labels.toString());
  }

  @Test
  void factorAuditMarksUncalibratedFalse() throws Exception {
    Path root = Paths.get(".").toAbsolutePath().normalize();
    SuiteSpec s = SuiteSpec.load(root.resolve("experiments/suites/ablation.yaml"));
    SuiteRunner.Options opt = newOptions(root);
    List<SuiteRunner.RunCell> cells =
        SuiteRunner.expandCells(s, new ArmRegistry(root), opt, false);
    AppConfig.PlannerConfig base = AppConfig.PlannerConfig.defaults();
    base.cost.calibrated = true;
    List<Map<String, Object>> audit = SuiteRunner.factorAudit(root, base, cells);
    Map<String, Object> full = findAudit(audit, "full");
    Map<String, Object> uncal = findAudit(audit, "uncalibrated");
    assertEquals(Boolean.TRUE, full.get("cost_calibrated"));
    assertEquals("config/planner.yaml", full.get("cost_coeffs_path"));
    assertEquals(Boolean.FALSE, uncal.get("cost_calibrated"));
    assertEquals(PlannerOverlay.UNCALIBRATED_COEFFS, uncal.get("cost_coeffs_path"));
    assertNotNull(uncal.get("cost_coeffs_sha256"));
    assertEquals(uncal.get("cost_coeffs_sha256"),
        ThirdPartyAudit.sha256(root.resolve(PlannerOverlay.UNCALIBRATED_COEFFS)));
  }

  @Test
  void jsonlAlwaysEmitsNullLlmKeys() throws Exception {
    Map<String, Object> row = new LinkedHashMap<String, Object>();
    row.put("factor", "no_llm_rule");
    SuiteRunner.ensureLlmSchema(row);
    assertTrue(row.containsKey("llm_calls"));
    assertTrue(row.containsKey("tokens_in"));
    assertTrue(row.containsKey("tokens_out"));
    assertNull(row.get("llm_calls"));
    Path jsonl = tmp.resolve("ablation.jsonl");
    TrialWriter w = new TrialWriter(jsonl, false);
    w.write(row);
    w.close();
    String line = new String(Files.readAllBytes(jsonl), StandardCharsets.UTF_8);
    assertTrue(line.contains("\"llm_calls\":null") || line.contains("\"llm_calls\" : null"), line);
    assertTrue(line.contains("\"tokens_in\":null") || line.contains("\"tokens_in\" : null"), line);
    assertTrue(line.contains("\"tokens_out\":null") || line.contains("\"tokens_out\" : null"), line);
  }

  @Test
  void warmMetaRecordsWarmupRotation() {
    CacheProtocol warm = CacheProtocol.from("warm");
    warm.armOrderPolicy = "rotate_by_query_and_trial";
    warm.warmupArmOrderPolicy = "rotate_by_query_and_warmup_pass";
    Map<String, Object> meta = warm.toMeta(5);
    assertEquals("rotate_by_query_and_warmup_pass", meta.get("warmup_arm_order_policy"));
    assertEquals("rotate_by_query_and_trial", meta.get("arm_order_policy"));
    assertEquals(Boolean.TRUE, meta.get("sequential_arm_cache_carryover"));
  }

  @Test
  void summaryDisclosesIncompleteWorkloadAndStrata() throws Exception {
    SuiteSpec s = new SuiteSpec();
    s.kind = "ablation";
    s.id = "ablation";
    List<Map<String, Object>> trials = new ArrayList<Map<String, Object>>();
    Map<String, Object> row = new LinkedHashMap<String, Object>();
    row.put("stage", "e2e");
    row.put("arm", "full");
    row.put("factor", "full");
    row.put("query_id", "t_small_1");
    row.put("family", "T");
    row.put("selectivity", "small");
    row.put("metric", "none");
    row.put("k", "n/a");
    row.put("empty_boundary", "interior");
    row.put("ok_oracle", Boolean.TRUE);
    row.put("t_e2e_ms", Long.valueOf(10L));
    trials.add(row);
    Path dir = tmp.resolve("sum");
    Files.createDirectories(dir);
    SummaryMd.write(dir, "audit-sum", trials, s, null, 1);
    String md = new String(Files.readAllBytes(dir.resolve("summary.md")), StandardCharsets.UTF_8);
    assertTrue(md.contains("incomplete BoundIR"), md);
    assertTrue(md.contains("by family"), md);
    assertTrue(md.contains("by selectivity"), md);
    assertTrue(md.contains("by metric"), md);
  }

  private static SuiteRunner.Options newOptions(Path root) {
    SuiteRunner.Options opt = new SuiteRunner.Options();
    opt.root = root;
    return opt;
  }

  private static List<String> cellLabels(SuiteSpec suite, SuiteRunner.Options opt) {
    List<String> labels = new ArrayList<String>();
    for (SuiteRunner.RunCell c : SuiteRunner.expandCells(suite, new ArmRegistry(opt.root), opt, false)) {
      labels.add(c.label);
    }
    return labels;
  }

  private static Map<String, Object> findAudit(List<Map<String, Object>> audit, String id) {
    for (Map<String, Object> m : audit) {
      if (id.equals(m.get("id"))) {
        return m;
      }
    }
    throw new AssertionError("missing factor " + id);
  }

  private static PlanSelector.Scored costed(String planId, double ms) {
    PlanEnvelope env = new PlanEnvelope();
    env.plan_id = planId;
    kart.compile.PhysicalPlan phys = new kart.compile.PhysicalPlan();
    kart.validation.SafePlanHandle handle;
    try {
      java.lang.reflect.Constructor<kart.validation.SafePlanHandle> c =
          kart.validation.SafePlanHandle.class.getDeclaredConstructor(
              PlanEnvelope.class, kart.compile.PhysicalPlan.class, ValidationReport.class);
      c.setAccessible(true);
      handle = c.newInstance(env, phys, new ValidationReport());
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
    CostCard card = new CostCard();
    card.plan_id = planId;
    card.estimated_ms = ms;
    return new PlanSelector.Scored(handle, card);
  }
}
