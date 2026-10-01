package kart.bench;

import kart.ir.BoundIr;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class OpportunityCensusTest {

  @Test
  void fingerprintIgnoresQueryId() {
    BoundIr a = ir("q1", 100L, 200L);
    BoundIr b = ir("q2", 100L, 200L);
    BoundIr c = ir("q1", 100L, 300L);
    assertEquals(OpportunityCensus.fingerprint(a), OpportunityCensus.fingerprint(b));
    assertFalse(OpportunityCensus.fingerprint(a).equals(OpportunityCensus.fingerprint(c)));
  }

  @Test
  void labelsNoFasterWhenNoPositiveSaving() {
    List<String> tags = OpportunityCensusLabels.labelQuery(
        false, false, false,
        Collections.<Long>emptyList(),
        Collections.singletonList(Long.valueOf(-10L)),
        900L);
    assertTrue(tags.contains("no_faster_safe_plan"));
    assertFalse(tags.contains("search_miss_with_exec_gain"));
  }

  @Test
  void labelsSearchMissAndOverheadDominates() {
    List<String> tags = OpportunityCensusLabels.labelQuery(
        false, false, false,
        Collections.singletonList(Long.valueOf(200L)),
        Collections.<Long>emptyList(),
        900L);
    assertTrue(tags.contains("search_miss_with_exec_gain"));
    assertTrue(tags.contains("candidate_fast_but_overhead_dominates"));
  }

  @Test
  void labelsSelectionMissLargeSaving() {
    List<String> tags = OpportunityCensusLabels.labelQuery(
        false, false, false,
        Collections.<Long>emptyList(),
        Collections.singletonList(Long.valueOf(5000L)),
        900L);
    assertTrue(tags.contains("selection_miss_with_exec_gain"));
    assertFalse(tags.contains("candidate_fast_but_overhead_dominates"));
    assertFalse(tags.contains("proposal_miss"));
  }

  @Test
  void labelsKeepFailureSeparateFromNoOpportunity() {
    List<String> tags = OpportunityCensusLabels.labelQuery(
        true, true, true,
        Arrays.asList(Long.valueOf(100L)),
        Collections.<Long>emptyList(),
        900L);
    assertTrue(tags.contains("oracle_or_execution_failure"));
    assertTrue(tags.contains("censored"));
    assertTrue(tags.contains("insufficient_repeats"));
  }

  @Test
  void executionSaving() {
    assertEquals(100L, OpportunityCensusLabels.executionSavingMs(Long.valueOf(150L), Long.valueOf(50L)));
    assertEquals(Long.MIN_VALUE, OpportunityCensusLabels.executionSavingMs(null, Long.valueOf(1L)));
  }

  private static BoundIr ir(String id, long start, long end) {
    BoundIr ir = new BoundIr();
    ir.ir_version = "1.0";
    ir.query_id = id;
    ir.source = new BoundIr.Source();
    ir.source.dataset_id = "tdrive_v1";
    ir.source.entity = "trajectory";
    ir.temporal = new BoundIr.Temporal();
    ir.temporal.start_ms = start;
    ir.temporal.end_ms = end;
    ir.result = new BoundIr.Result();
    ir.result.mode = "TRAJECTORY_IDS";
    return ir;
  }
}
