package kart.validation;

import kart.codec.Bytes;
import kart.codec.PrefixSuccessor;
import kart.compile.ScanTask;
import kart.exec.TrajectorySimilarity;
import kart.oracle.FullScanOracle;
import kart.ir.BoundIr;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PhysicalSafetyAndMetricTest {

  @Test
  public void stopWithinSameShardOk() {
    byte[] stop = new byte[] {3, 0x10};
    assertTrue(PlanValidator.stopWithinShard(stop, 3));
  }

  @Test
  public void stopAtExclusiveShardBoundOk() {
    byte[] bound = PrefixSuccessor.of(new byte[] {(byte) 3}).get();
    assertTrue(PlanValidator.stopWithinShard(bound, 3));
  }

  @Test
  public void stopWrongShardRejected() {
    byte[] stop = new byte[] {5, 0x00};
    assertFalse(PlanValidator.stopWithinShard(stop, 3));
  }

  @Test
  public void truncatedFlagOnScanTask() {
    ScanTask t = new ScanTask("t", Bytes.u8(1), Bytes.u8(2), "n1", 1, "x", true);
    assertTrue(t.truncated);
  }

  @Test
  public void oracleRejectsNullMetric() {
    BoundIr ir = new BoundIr();
    ir.similarity = new BoundIr.Similarity();
    ir.similarity.metric = null;
    ir.similarity.reference_tid = 1L;
    ir.result = new BoundIr.Result();
    ir.result.mode = "TOP_K";
    ir.result.k = 1;
    FullScanOracle oracle = new FullScanOracle(Collections.<kart.data.Trajectory>emptyList());
    assertThrows(IllegalArgumentException.class, () -> oracle.evaluate(ir));
  }

  @Test
  public void supportedMetrics() {
    assertTrue(TrajectorySimilarity.isSupported("DTW"));
    assertFalse(TrajectorySimilarity.isSupported(""));
  }
}
