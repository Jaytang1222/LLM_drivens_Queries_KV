package kart.bench;

import kart.ir.BoundIr;
import kart.llm.LlmClient;
import kart.llm.LlmException;
import kart.llm.LlmMessage;
import kart.llm.LlmOptions;
import kart.llm.LlmResponse;
import kart.plan.PlanBuilder;
import kart.plan.PlanEnvelope;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CboLlmProposalArmTest {

  @Test
  void compactChoiceRejectsAmbiguousOrOutOfRangeOutput() {
    List<String> plans = java.util.Arrays.asList("P_T", "P_Z");
    assertEquals("P_Z", CboLlmProposalArm.parseCompactChoice("1", plans).planId);
    assertEquals("keep_cbo", CboLlmProposalArm.parseCompactChoice("K", plans).action);
    assertNull(CboLlmProposalArm.parseCompactChoice("2", plans));
    assertNull(CboLlmProposalArm.parseCompactChoice("0 because cheaper", plans));
    assertNull(CboLlmProposalArm.parseCompactChoice("N", plans));
    assertNull(CboLlmProposalArm.parseCompactChoice("0", Collections.<String>emptyList()));
  }

  @Test
  void parsePlanIdRequiresStrictJson() {
    assertEquals("P_TH_SORT_MERGE",
        CboLlmProposalArm.parsePlanIdJson("{\"plan_id\":\"P_TH_SORT_MERGE\"}"));
    assertNull(CboLlmProposalArm.parsePlanIdJson("choose P_TH_SORT_MERGE please"));
    assertNull(CboLlmProposalArm.parsePlanIdJson("{\"plan_id\":\"FULL\"}"));
    assertNull(CboLlmProposalArm.parsePlanIdJson("not json"));
    assertNull(CboLlmProposalArm.parsePlanIdJson("{\"reason\":\"x\"}"));
    assertNull(CboLlmProposalArm.parsePlanIdJson("{\"plan_id\":\"P_TZ\",\"reason\":\"x\"}"));
    assertNull(CboLlmProposalArm.parsePlanIdJson("{\"plan_id\":12}"));
  }

  @Test
  void novelIdsOmitCboSafeAndFull() {
    List<PlanEnvelope> all = new ArrayList<PlanEnvelope>();
    all.add(env("P_TZ"));
    all.add(env("P_TZ_SORT_MERGE"));
    all.add(env("P_FULL"));
    Set<String> cbo = new LinkedHashSet<String>();
    cbo.add("P_TZ");
    cbo.add("P_FULL");
    List<String> novel = CboLlmProposalArm.novelIds(all, cbo);
    assertEquals(Collections.singletonList("P_TZ_SORT_MERGE"), novel);
  }

  @Test
  void emptyWhitelistMeansZeroLlmCallsContract() {
    List<PlanEnvelope> all = new ArrayList<PlanEnvelope>();
    all.add(env("P_T"));
    all.add(env("P_FULL"));
    Set<String> cbo = new LinkedHashSet<String>();
    cbo.add("P_T");
    cbo.add("P_FULL");
    assertTrue(CboLlmProposalArm.novelIds(all, cbo).isEmpty());
  }

  @Test
  void oneShotOptionsDisableJsonRetry() {
    LlmOptions o = LlmOptions.oneShot(120_000);
    assertFalse(o.allowJsonModeRetry);
    assertFalse(o.jsonObjectFormat);
    assertEquals(120_000, o.timeoutMs);
    assertEquals(Integer.valueOf(96), o.maxTokens);
  }

  @Test
  void speculatePtEnabledByDefault() {
    assertTrue(CboLlmProposalArm.speculatePtEnabled());
  }

  @Test
  void defaultLlmWallBudgetIsNoLongerFifteenHundred() {
    assertTrue(CboLlmProposalArm.LLM_WALL_BUDGET_MS > 1500L);
    assertEquals(120_000L, CboLlmProposalArm.LLM_WALL_BUDGET_MS);
  }

  @Test
  void cacheKeyIgnoresQueryIdAndChangesWithTemporal() {
    BoundIr a = minimalIr("q1", 100L, 200L);
    BoundIr b = minimalIr("q2", 100L, 200L);
    BoundIr c = minimalIr("q1", 100L, 300L);
    String ka = CboLlmProposalCache.keyFor(a, "m", "s", "L", "c");
    String kb = CboLlmProposalCache.keyFor(b, "m", "s", "L", "c");
    String kc = CboLlmProposalCache.keyFor(c, "m", "s", "L", "c");
    assertEquals(ka, kb);
    assertFalse(ka.equals(kc));
  }

  @Test
  void cacheHitAndVersionMiss() {
    CboLlmProposalCache cache = new CboLlmProposalCache();
    BoundIr ir = minimalIr("q", 1L, 2L);
    String k1 = CboLlmProposalCache.keyFor(ir, "m", "s", "L", "c1");
    String k2 = CboLlmProposalCache.keyFor(ir, "m", "s", "L", "c2");
    cache.put(k1, "P_TZ_SORT_MERGE");
    assertEquals("P_TZ_SORT_MERGE", cache.get(k1));
    assertNull(cache.get(k2));
  }

  @Test
  void registryResolvesBothArms() {
    ArmRegistry reg = new ArmRegistry(java.nio.file.Paths.get(".").toAbsolutePath().normalize());
    assertEquals("cbo-llm-proposal", reg.resolve("cbo-llm-proposal").id());
    assertEquals("cbo-llm-proposal-cached", reg.resolve("cbo-llm-proposal-cached").id());
  }

  @Test
  void mockLlmOneShotNoSecondCall() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    LlmClient once = new LlmClient() {
      @Override
      public LlmResponse chat(List<LlmMessage> messages,
                              com.fasterxml.jackson.databind.JsonNode responseSchemaHint,
                              LlmOptions opts) {
        calls.incrementAndGet();
        assertFalse(opts.allowJsonModeRetry);
        LlmResponse r = new LlmResponse();
        r.content = "{\"plan_id\":\"P_TZ_SORT_MERGE\"}";
        r.attempts = 1;
        r.latencyMs = 12L;
        return r;
      }
    };
    // Direct unit of ask path via parse + options; full arm needs HBase engine.
    LlmOptions o = LlmOptions.oneShot(120_000);
    LlmResponse resp = once.chat(Collections.<LlmMessage>emptyList(), null, o);
    assertEquals("P_TZ_SORT_MERGE", CboLlmProposalArm.parsePlanIdJson(resp.content));
    assertEquals(1, calls.get());
  }

  @Test
  void mockHttpFailureSurfaces() {
    LlmClient bad = new LlmClient() {
      @Override
      public LlmResponse chat(List<LlmMessage> messages,
                              com.fasterxml.jackson.databind.JsonNode responseSchemaHint,
                              LlmOptions opts) throws LlmException {
        throw new LlmException("HTTP 500: boom", Integer.valueOf(500));
      }
    };
    try {
      bad.chat(Collections.<LlmMessage>emptyList(), null, LlmOptions.oneShot(100));
      assertTrue(false, "expected throw");
    } catch (LlmException e) {
      assertEquals(Integer.valueOf(500), e.httpStatus);
    }
  }

  @Test
  void armIdsDistinguishCacheMode() {
    assertEquals("cbo-llm-proposal", CboLlmProposalArm.withoutCache().id());
    assertEquals("cbo-llm-proposal-cached", CboLlmProposalArm.withCache().id());
    assertFalse(CboLlmProposalArm.withoutCache().cacheEnabled());
    assertTrue(CboLlmProposalArm.withCache().cacheEnabled());
  }

  @Test
  void mergeVariantsIncludeSortMergeForTz() {
    BoundIr ir = minimalIr("tz", 1202044800000L, 1202045400000L);
    ir.spatial = new BoundIr.Spatial();
    ir.spatial.min_x = 1;
    ir.spatial.min_y = 1;
    ir.spatial.max_x = 2;
    ir.spatial.max_y = 2;
    ir.spatial.relation = "INTERSECTS";
    List<PlanEnvelope> all = PlanBuilder.buildCandidatesWithMergeVariants(ir);
    Set<String> ids = new LinkedHashSet<String>();
    for (PlanEnvelope e : all) {
      ids.add(e.plan_id);
    }
    assertTrue(ids.contains("P_TZ"));
    assertTrue(ids.contains("P_TZ_SORT_MERGE"));
    assertTrue(ids.contains("P_FULL"));
  }

  @Test
  void independentPromptV7NoForcedPrefer() {
    BoundIr ir = minimalIr("q", 0L, CboLlmProposalArm.TRIGGER_TEMPORAL_MS);
    ir.result.mode = "TOP_K";
    ir.result.k = Integer.valueOf(5);
    List<String> wl = java.util.Arrays.asList("P_T", "P_Z");
    String prompt = CboLlmProposalArm.buildPrompt(ir, "P_TZ", wl, "P_T");
    assertTrue(prompt.contains("prompt_version=cbo_llm_independent_v7"));
    assertTrue(prompt.contains("whitelist=P_T,P_Z"));
    assertTrue(prompt.contains("forbidden=P_TZ"));
    assertTrue(prompt.contains("rank_hint=P_T"));
    assertFalse(prompt.contains("prefer=P_T"));
    assertFalse(prompt.contains("estimated_ms="));
    assertFalse(prompt.contains("measured_winner"));
    assertFalse(prompt.contains("query_id="));
  }

  @Test
  void softRetryPreferDisabledInV7() {
    List<String> wl = java.util.Arrays.asList("P_T", "P_Z");
    assertFalse(CboLlmProposalArm.shouldSoftRetryPrefer("P_Z", "P_T", wl));
    assertFalse(CboLlmProposalArm.shouldSoftRetryPrefer("P_T", "P_T", wl));
  }

  @Test
  void parseKeepCboAndProposeActions() {
    CboLlmProposalArm.LlmDecision keep =
        CboLlmProposalArm.parseLlmDecision(
            "{\"action\":\"keep_cbo\",\"plan_id\":null,\"reason_code\":\"insufficient_gain\"}");
    assertEquals("keep_cbo", keep.action);
    assertNull(keep.planId);
    assertEquals("insufficient_gain", keep.reasonCode);

    CboLlmProposalArm.LlmDecision prop =
        CboLlmProposalArm.parseLlmDecision(
            "{\"action\":\"propose\",\"plan_id\":\"P_T\",\"reason_code\":\"lower_fetch_cost\"}");
    assertEquals("propose", prop.action);
    assertEquals("P_T", prop.planId);
    assertEquals("P_T", CboLlmProposalArm.parsePlanIdJson(
        "{\"action\":\"propose\",\"plan_id\":\"P_T\",\"reason_code\":\"other\"}"));
  }

  @Test
  void costBeatTriggerIsGeneral() {
    // Non single-index id + narrow ST → only cost-beat can trigger.
    List<String> wl = java.util.Arrays.asList("P_OTHER");
    BoundIr ir = minimalIr("x", 1L, 2L);
    assertTrue(CboLlmProposalArm.shouldTriggerLlm(
        ir, wl, Double.valueOf(100.0d), 200.0d));
    assertFalse(CboLlmProposalArm.shouldTriggerLlm(
        ir, wl, Double.valueOf(200.0d), 100.0d));
  }

  @Test
  void classifyProposalAcceptsWhitelistRejectsCboEcho() {
    List<String> wl = new ArrayList<String>();
    wl.add("P_T");
    wl.add("P_Z");
    assertNull(CboLlmProposalArm.classifyProposal("P_T", "P_TZ", wl));
    assertEquals("duplicate_cbo_id",
        CboLlmProposalArm.classifyProposal("P_TZ", "P_TZ", wl));
    assertEquals("unknown_plan_id",
        CboLlmProposalArm.classifyProposal("P_FULL", "P_TZ", wl));
    assertEquals("non_json_or_missing_plan_id",
        CboLlmProposalArm.classifyProposal(null, "P_TZ", wl));
  }

  @Test
  void proposalWhitelistDropsMergeWhenNonMergeExists() {
    List<String> novel = new ArrayList<String>();
    novel.add("P_T");
    novel.add("P_TZ_SORT_MERGE");
    assertEquals(Collections.singletonList("P_T"),
        CboLlmProposalArm.proposalWhitelist(novel));
  }

  @Test
  void mergeOnlyNovelSkipsLlm() {
    List<String> novel = Collections.singletonList("P_TZ_SORT_MERGE");
    List<String> wl = CboLlmProposalArm.proposalWhitelist(novel);
    assertTrue(wl.isEmpty());
    assertEquals("skip_merge_only_whitelist",
        CboLlmProposalArm.llmSkipReason(minimalIr("q", 1L, 2L), wl, novel));
  }

  @Test
  void triggerOnSingleIndexMissOrWideOrTopK() {
    BoundIr narrow = minimalIr("n", 1000L, 2000L);
    List<String> onlyBipart = Collections.singletonList("P_TZ_TIME_BIPART");
    assertEquals("skip_no_trigger",
        CboLlmProposalArm.llmSkipReason(narrow, onlyBipart, onlyBipart, null,
            Double.POSITIVE_INFINITY));

    List<String> withPt = Collections.singletonList("P_T");
    assertNull(CboLlmProposalArm.llmSkipReason(narrow, withPt, withPt, null,
        Double.POSITIVE_INFINITY));

    BoundIr wide = minimalIr("w", 0L, CboLlmProposalArm.TRIGGER_TEMPORAL_MS);
    assertNull(CboLlmProposalArm.llmSkipReason(wide, onlyBipart, onlyBipart, null,
        Double.POSITIVE_INFINITY));

    BoundIr topk = minimalIr("k", 1000L, 2000L);
    topk.result.mode = "TOP_K";
    topk.result.k = Integer.valueOf(3);
    assertNull(CboLlmProposalArm.llmSkipReason(topk, onlyBipart, onlyBipart, null,
        Double.POSITIVE_INFINITY));
  }

  private static PlanEnvelope env(String id) {
    PlanEnvelope e = new PlanEnvelope();
    e.plan_id = id;
    return e;
  }

  private static BoundIr minimalIr(String id, long start, long end) {
    BoundIr ir = new BoundIr();
    ir.query_id = id;
    ir.source = new BoundIr.Source();
    ir.source.dataset_id = "tdrive_v1";
    ir.source.entity = "trajectory";
    ir.temporal = new BoundIr.Temporal();
    ir.temporal.start_ms = start;
    ir.temporal.end_ms = end;
    ir.semantics = new BoundIr.Semantics();
    ir.semantics.mode = "OBSERVED_POINT";
    ir.result = new BoundIr.Result();
    ir.result.mode = "TRAJECTORY_IDS";
    ir.snapshot = new BoundIr.Snapshot();
    ir.snapshot.manifest_id = "tdrive_v1_ready";
    ir.snapshot.semantics_version = "point_dtw_v1";
    return ir;
  }
}
