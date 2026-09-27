package kart.bench;

import kart.ir.BoundIr;
import kart.llm.LlmResponse;
import kart.llm.LlmUsageAccumulator;
import kart.plan.PlanBuilder;
import kart.plan.PlanEnvelope;
import kart.query.QueryEngine;
import kart.search.SearchLog;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Fairness/honesty helpers for the comparative harness. */
public final class ComparativeHarnessTest {

  @Test
  void warmProtocolUsesTwoUntimedPassesByDefault() {
    CacheProtocol warm = CacheProtocol.from("warm");
    assertEquals("warm", warm.mode);
    assertEquals(2, warm.warmupPasses);
    assertFalse(warm.cacheEnforced());
    assertTrue(warm.protocolLabel().contains("warmup_2"));
  }

  @Test
  void fatJarManifestIsNotTreatedAsHBaseClientVersion() {
    String[] shaded = HBaseEnvProbe.resolveClientVersion(
        "/home/jaytang/projects/llm-kv/target/kart.jar",
        null, "2.2", "2.2.3", "2.2.3", true);
    assertEquals("2.2.3", shaded[0]);
    assertEquals("2.2", shaded[1]);
    assertEquals("classpath_pom_plus_lib_hbase_client_manifest_spec", shaded[2]);
    assertFalse(HBaseEnvProbe.isDedicatedHBaseClientJar("kart.jar"));
    assertTrue(HBaseEnvProbe.isDedicatedHBaseClientJar("hbase-client-2.2.3.jar"));

    String[] dedicated = HBaseEnvProbe.resolveClientVersion(
        "hbase-client-2.2.3.jar", "2.2.3", "2.2", null, null);
    assertEquals("2.2.3", dedicated[0]);
    assertEquals("2.2", dedicated[1]);
    assertEquals("hbase_client_jar_manifest", dedicated[2]);

    String[] missing = HBaseEnvProbe.resolveClientVersion("kart.jar", null, null, null, null);
    assertEquals(null, missing[0]);
    assertEquals("unresolved", missing[2]);
  }

  @Test
  void regionServerCountFallsBackToClusterStatus() {
    StringBuilder errors = new StringBuilder();
    assertEquals(Integer.valueOf(1),
        HBaseEnvProbe.regionServerCount(null, new FakeStatus(1), errors));
    StringBuilder miss = new StringBuilder();
    assertEquals(null, HBaseEnvProbe.regionServerCount(null, null, miss));
  }

  @Test
  void coldEnforcedOnlyWhenEveryFlushSucceeds() {
    CacheProtocol cold = CacheProtocol.from("cold");
    cold.hbaseFlushAttempts = 2;
    cold.hbaseFlushOk = 2;
    cold.osFlushAttempts = 2;
    cold.osFlushOk = 1;
    assertFalse(cold.cacheEnforced());
    cold.osFlushOk = 2;
    assertTrue(cold.cacheEnforced());
    cold.hbaseFlushOk = 1;
    assertFalse(cold.cacheEnforced());
  }

  @Test
  void coldProtocolDoesNotClaimEnforcedFlushWithoutOsDrop() {
    CacheProtocol cold = CacheProtocol.from("cold");
    assertEquals(0, cold.warmupPasses);
    assertFalse(cold.cacheEnforced());
    assertNotNull(cold.formalWarning(1));
    assertTrue(cold.formalWarning(3).contains("mixed_cache"));
  }

  @Test
  void rboPlanClockCoversRejectedTemplates() {
    TrialResult tr = new TrialResult();
    tr.t_exec_ms = Long.valueOf(30L);
    long plan = RboFixedArm.sumAttemptPlanMs(40L, 10L);
    RboFixedArm.applyPlanClock(tr, 1_000L, plan);
    assertEquals(50L, tr.t_plan_ms.longValue());
    assertEquals(80L, tr.t_e2e_ms.longValue());
    assertEquals(1_000L, ((Long) tr.extras.get("plan_start_ms")).longValue());
    assertEquals(1_050L, ((Long) tr.extras.get("plan_end_ms")).longValue());

    TrialResult planOnly = new TrialResult();
    RboFixedArm.applyPlanClock(planOnly, 5L, RboFixedArm.sumAttemptPlanMs(7L, 3L));
    assertEquals(planOnly.t_plan_ms, planOnly.t_e2e_ms);
    assertEquals(10L, planOnly.t_plan_ms.longValue());
  }

  @Test
  void parseAndPlanArmsRotateByTrialAndQuery() {
    assertEquals(0, SuiteRunner.armShift(1, 0, 3));
    assertEquals(1, SuiteRunner.armIndex(0, SuiteRunner.armShift(1, 1, 3), 3));
    assertEquals(2, SuiteRunner.armShift(2, 1, 3));
    assertEquals(0, SuiteRunner.armIndex(1, 2, 3));
    // Every position appears once per query/trial.
    boolean[] seen = new boolean[4];
    int shift = SuiteRunner.armShift(3, 2, 4);
    for (int pos = 0; pos < 4; pos++) {
      seen[SuiteRunner.armIndex(pos, shift, 4)] = true;
    }
    for (boolean s : seen) {
      assertTrue(s);
    }
  }

  @Test
  void rboRuleIsTzThenTThenZThenHThenFull() {
    assertArrayEquals(new boolean[] {true, true, false}, RboFixedArm.accessFlags(true, true, true));
    assertArrayEquals(new boolean[] {true, false, false}, RboFixedArm.accessFlags(true, false, true));
    assertArrayEquals(new boolean[] {false, true, false}, RboFixedArm.accessFlags(false, true, true));
    assertArrayEquals(new boolean[] {false, false, true}, RboFixedArm.accessFlags(false, false, true));
    assertArrayEquals(new boolean[] {false, false, false}, RboFixedArm.accessFlags(false, false, false));
    List<boolean[]> withTz = RboFixedArm.templatePriority(true, true, true);
    assertEquals(5, withTz.size());
    assertArrayEquals(new boolean[] {true, true, false}, withTz.get(0));
    assertArrayEquals(new boolean[] {true, false, false}, withTz.get(1));
    assertArrayEquals(new boolean[] {false, true, false}, withTz.get(2));
    assertArrayEquals(new boolean[] {false, false, true}, withTz.get(3));
    assertArrayEquals(new boolean[] {false, false, false}, withTz.get(4));
    BoundIr ir = new BoundIr();
    ir.query_id = "x";
    ir.temporal = new BoundIr.Temporal();
    ir.spatial = new BoundIr.Spatial();
    ir.spatial.min_x = 0;
    ir.spatial.min_y = 0;
    ir.spatial.max_x = 1;
    ir.spatial.max_y = 1;
    ir.snapshot = new BoundIr.Snapshot();
    ir.snapshot.manifest_id = "fixture";
    ir.predicates.add(predVehicle());
    boolean[] f = RboFixedArm.accessFlags(true, true, true);
    PlanEnvelope tz = PlanBuilder.buildForAccess(ir, f[0], f[1], f[2]);
    assertEquals("P_TZ", tz.plan_id);
    assertFalse(tz.nodes.stream().anyMatch(n -> n.params != null
            && Boolean.TRUE.equals(n.params.get("provisional_merge"))),
        "finished RBO templates must not be provisional");
  }

  @Test
  void jsonModeFallbackCountsAsTwoCalls() {
    LlmUsageAccumulator acc = new LlmUsageAccumulator();
    LlmResponse r = new LlmResponse("{\"ok\":true}");
    r.attempts = 2;
    r.jsonModeFallback = true;
    r.promptTokens = Integer.valueOf(10);
    r.completionTokens = Integer.valueOf(4);
    r.latencyMs = 50L;
    acc.record(r);
    assertEquals(2, acc.calls());
    assertEquals(1, acc.successfulRecordings());
    assertEquals(0, acc.failedAttempts());
    assertEquals(1, acc.jsonModeFallbacks());
    assertEquals(Long.valueOf(10L), acc.promptTokensOrNull());
    assertEquals(Long.valueOf(4L), acc.completionTokensOrNull());
    assertEquals(50L, acc.latencyMs());
  }

  @Test
  void httpFailureIsNotZeroLlmCalls() {
    LlmUsageAccumulator acc = new LlmUsageAccumulator();
    acc.recordHttpFailure(Integer.valueOf(402), 12L);
    assertEquals(1, acc.calls());
    assertEquals(1, acc.failedAttempts());
    assertEquals(0, acc.successfulRecordings());
    assertTrue(acc.anyHttpFailure());
    assertEquals(Integer.valueOf(402), acc.lastHttpStatus());
  }

  @Test
  void parseScoreIsLabelBlindUntilScoring() {
    ParseTrialResult out = new ParseTrialResult();
    out.reject_actual = Boolean.TRUE;
    out.clarify_actual = Boolean.FALSE;
    NlItem reject = new NlItem();
    reject.reject_expected = true;
    ParseScore.scoreAgainstGold(out, reject);
    assertTrue(Boolean.TRUE.equals(out.ok_ex));

    ParseTrialResult early = ParseFairness.rejectedTrial(reject, "count");
    assertTrue(Boolean.TRUE.equals(early.extras.get("early_reject")));
    assertEquals("shared_early_gate", early.extras.get("reject_source"));
    assertTrue(Boolean.TRUE.equals(early.ok_ex));

    ParseTrialResult infra = new ParseTrialResult();
    infra.extras.put("infrastructure_failure", Boolean.TRUE);
    NlItem supported = new NlItem();
    supported.reject_expected = false;
    ParseScore.scoreAgainstGold(infra, supported);
    assertFalse(Boolean.TRUE.equals(infra.ok_ex));
  }

  @Test
  void entirelyRuleFallbackDetectsOnlyWhenNoLlmOkEvent() {
    SearchLog ok = new SearchLog();
    SearchLog.Step s = new SearchLog.Step();
    s.event = "ok";
    ok.steps().add(s);
    assertFalse(QueryEngine.entirelyRuleFallback(ok));

    SearchLog fb = new SearchLog();
    SearchLog.Step a = new SearchLog.Step();
    a.event = "illegal_action";
    SearchLog.Step b = new SearchLog.Step();
    b.event = "rule_fallback";
    fb.steps().add(a);
    fb.steps().add(b);
    assertTrue(QueryEngine.entirelyRuleFallback(fb));
  }

  @Test
  void wrapperPlanClockExcludesExecFromPlan() {
    TrialResult tr = new TrialResult();
    tr.t_exec_ms = Long.valueOf(40L);
    tr.applyWrapperPlanClock(1000L, 90L);
    assertEquals(Long.valueOf(50L), tr.t_plan_ms);
    assertEquals(Long.valueOf(90L), tr.t_e2e_ms);
    assertEquals(Long.valueOf(1000L), tr.extras.get("plan_start_ms"));
    assertEquals(Long.valueOf(1050L), tr.extras.get("plan_end_ms"));
  }

  @Test
  void failClassMapsTimeoutAndExhaustion() {
    TrialResult t = TrialResult.fail("TIMEOUT after 30s");
    Map<String, Object> row = new LinkedHashMap<String, Object>();
    assertEquals("timeout", SuiteRunner.failClass(t, row));
    t = TrialResult.fail("RESOURCE_EXHAUSTED: dtw cells");
    assertEquals("resource_exhausted", SuiteRunner.failClass(t, row));
    row.put("ok_oracle", Boolean.TRUE);
    assertEquals("ok", SuiteRunner.failClass(new TrialResult(), row));
  }

  @Test
  void thirdPartyArmMapsToUpstreamKey() {
    assertEquals("din_sql", ThirdPartyAudit.upstreamKey("din-spider"));
    assertEquals("din_sql", ThirdPartyAudit.upstreamKey("din-sql-spider"));
    assertEquals("text_to_nosql", ThirdPartyAudit.upstreamKey("sag-mql"));
    assertEquals("bao", ThirdPartyAudit.upstreamKey("bao"));
    assertEquals(null, ThirdPartyAudit.upstreamKey("fullscan"));
    assertEquals(null, ThirdPartyAudit.upstreamKey("kart-conditional-llm"));
  }

  @Test
  void anyIllegalActionIsLlmFallback() {
    SearchLog mixed = new SearchLog();
    SearchLog.Step ok = new SearchLog.Step();
    ok.event = "ok";
    SearchLog.Step bad = new SearchLog.Step();
    bad.event = "illegal_action";
    mixed.steps().add(ok);
    mixed.steps().add(bad);
    assertFalse(QueryEngine.entirelyRuleFallback(mixed));
  }

  @Test
  void llmFallbackArmsStaySplitFromPureArms() throws Exception {
    assertTrue(SuiteRunner.splitsOnLlmFallback("kart-conditional-llm"));
    assertTrue(SuiteRunner.splitsOnLlmFallback("pool-llm"));
    assertTrue(SuiteRunner.splitsOnLlmFallback("pool-conditional-llm"));
    assertEquals("kart-rule-fallback", SuiteRunner.fallbackArmName("kart", "kart", false));
    assertEquals("pool-llm-rule-fallback",
        SuiteRunner.fallbackArmName("pool-llm", "pool-llm", false));
    assertEquals("kart-conditional-llm-rule-fallback",
        SuiteRunner.fallbackArmName("kart-conditional-llm", "kart-conditional-llm", false));

    Map<String, Object> pure = new LinkedHashMap<String, Object>();
    pure.put("stage", "plan");
    pure.put("arm", "pool-llm");
    pure.put("plan_ok", Boolean.TRUE);
    pure.put("t_plan_ms", Long.valueOf(10L));
    pure.put("llm_fallback", Boolean.FALSE);
    Map<String, Object> fell = new LinkedHashMap<String, Object>();
    fell.put("stage", "plan");
    fell.put("arm", "pool-llm");
    fell.put("plan_ok", Boolean.TRUE);
    fell.put("t_plan_ms", Long.valueOf(12L));
    fell.put("llm_fallback", Boolean.TRUE);
    assertEquals("pool-llm", SummaryMd.summaryArmKey(pure));
    assertEquals("pool-llm-rule-fallback", SummaryMd.summaryArmKey(fell));

    Path dir = Files.createTempDirectory("fallback-split");
    SuiteSpec suite = new SuiteSpec();
    suite.id = "fallback-split";
    suite.kind = "compare";
    suite.trials = 1;
    List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
    rows.add(pure);
    rows.add(fell);
    SummaryMd.write(dir, "fallback-split", rows, suite);
    String md = new String(Files.readAllBytes(dir.resolve("summary.md")), StandardCharsets.UTF_8);
    assertTrue(md.contains("| pool-llm | 1 |"));
    assertTrue(md.contains("| pool-llm-rule-fallback | 1 |"));
  }

  @Test
  void e2eTimingExcludesArtifactWall() {
    QueryEngine.RunResult rr = new QueryEngine.RunResult();
    rr.t_plan_ms = Long.valueOf(10L);
    rr.t_exec_ms = Long.valueOf(40L);
    TrialResult tr = TrialResult.fromRun(rr, 80L);
    assertEquals(Long.valueOf(50L), tr.t_e2e_ms);
    assertEquals(Long.valueOf(30L), tr.extras.get("t_artifact_ms"));
  }

  /** Stand-in for HBase 2.1 ClusterStatus, which exposes getServersSize(). */
  public static final class FakeStatus {
    private final int servers;
    FakeStatus(int servers) {
      this.servers = servers;
    }
    public int getServersSize() {
      return servers;
    }
  }

  private static BoundIr.Predicate predVehicle() {
    BoundIr.Predicate p = new BoundIr.Predicate();
    p.field = "vehicle_id";
    p.op = "EQ";
    p.value = "8857";
    return p;
  }
}
