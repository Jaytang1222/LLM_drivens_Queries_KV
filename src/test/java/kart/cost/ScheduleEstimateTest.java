package kart.cost;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ScheduleEstimateTest {

  @Test
  public void serialSumWhenConcurrencyOne() {
    List<ScheduleEstimate.WorkItem> items = Arrays.asList(
        new ScheduleEstimate.WorkItem("rs1", 10),
        new ScheduleEstimate.WorkItem("rs1", 20));
    ScheduleEstimate.Result r = ScheduleEstimate.estimate(items, 1, 1, false);
    assertEquals(30.0, r.wallClockMs, 1e-9);
  }

  @Test
  public void parallelWithinRs() {
    List<ScheduleEstimate.WorkItem> items = Arrays.asList(
        new ScheduleEstimate.WorkItem("rs1", 10),
        new ScheduleEstimate.WorkItem("rs1", 10));
    ScheduleEstimate.Result r = ScheduleEstimate.estimate(items, 4, 2, false);
    assertEquals(10.0, r.wallClockMs, 1e-9);
  }

  @Test
  public void costModelProducesComponents() {
    CostFeatures f = new CostFeatures();
    f.planId = "P_T";
    f.scanRanges = 4;
    f.estIndexRows = 100;
    f.estCandidateChunks = 50;
    f.estRawBytes = 10000;
    f.estEligibleTrajs = 10;
    f.estDtwCells = 1000;
    f.needsReconstruct = true;
    f.topK = 5;
    f.shardCountHint = 4;
    CostCard card = new CostModel(new kart.config.AppConfig.CostCoeffs()).estimateFinal(f);
    assertTrue(card.estimated_ms > 0);
    assertTrue(card.l_hat_index >= 0);
    assertEquals(CostModel.MODEL_VERSION, card.model_version);
  }
}
