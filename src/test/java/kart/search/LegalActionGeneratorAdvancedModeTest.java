package kart.search;

import kart.ir.BoundIr;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public final class LegalActionGeneratorAdvancedModeTest {

  @Test
  void boundedAdvancedModeLeavesAdvancedCandidatesForRouterInjection() {
    BoundIr ir = spatialTemporalIr();
    List<LegalAction> actions = new LegalActionGenerator(true, false)
        .generate(SearchState.initial(ir));

    assertFalse(hasIndex(actions, IndexId.XZT));
    assertFalse(hasIndex(actions, IndexId.XZ));
    assertFalse(hasIndex(actions, IndexId.TSHAPE));
    assertTrue(hasIndex(actions, IndexId.TIME));
    assertTrue(hasIndex(actions, IndexId.ZORDER));
  }

  @Test
  void fullAdvancedModeStillExposesAdvancedActions() {
    List<LegalAction> actions = new LegalActionGenerator(true, true)
        .generate(SearchState.initial(spatialTemporalIr()));

    assertTrue(hasIndex(actions, IndexId.XZT));
    assertTrue(hasIndex(actions, IndexId.XZ));
    assertTrue(hasIndex(actions, IndexId.TSHAPE));
  }

  private static boolean hasIndex(List<LegalAction> actions, String indexId) {
    for (LegalAction action : actions) {
      if (indexId.equals(action.indexId)) {
        return true;
      }
    }
    return false;
  }

  private static BoundIr spatialTemporalIr() {
    BoundIr ir = new BoundIr();
    ir.temporal = new BoundIr.Temporal();
    ir.temporal.start_ms = 1_200_000_000_000L;
    ir.temporal.end_ms = ir.temporal.start_ms + 48L * 3600L * 1000L;
    ir.spatial = new BoundIr.Spatial();
    ir.spatial.min_x = 0.0d;
    ir.spatial.min_y = 0.0d;
    ir.spatial.max_x = 1_000.0d;
    ir.spatial.max_y = 1_000.0d;
    return ir;
  }
}
