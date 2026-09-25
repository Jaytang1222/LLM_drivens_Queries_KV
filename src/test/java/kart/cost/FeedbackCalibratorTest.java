package kart.cost;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public final class FeedbackCalibratorTest {

  @TempDir
  Path tmp;

  @Test
  void fitsCoeffsAndWritesReport() throws Exception {
    Path q1 = tmp.resolve("q1");
    Files.createDirectories(q1);
    Files.write(q1.resolve("cost_features.json"),
        ("{\"scan_ranges\":10,\"estimated_index_rows\":1000,"
            + "\"estimated_candidate_chunks\":100,\"estimated_raw_bytes\":400000,"
            + "\"estimated_eligible_trajectories\":50,\"estimated_dtw_cells\":0}").getBytes(StandardCharsets.UTF_8));
    Files.write(q1.resolve("trace.json"),
        "{\"elapsedMs\":120,\"matched_chunks\":40,\"candidate_chunks\":100}"
            .getBytes(StandardCharsets.UTF_8));

    Path q2 = tmp.resolve("q2");
    Files.createDirectories(q2);
    Files.write(q2.resolve("cost_features.json"),
        ("{\"scan_ranges\":20,\"estimated_index_rows\":2000,"
            + "\"estimated_candidate_chunks\":200,\"estimated_raw_bytes\":800000,"
            + "\"estimated_eligible_trajectories\":80,\"estimated_dtw_cells\":0}").getBytes(StandardCharsets.UTF_8));
    Files.write(q2.resolve("trace.json"),
        "{\"elapsedMs\":250,\"matched_chunks\":60,\"candidate_chunks\":200}"
            .getBytes(StandardCharsets.UTF_8));

    Path report = tmp.resolve("report.json");
    Path coeffs = tmp.resolve("coeffs.json");
    FeedbackCalibrator.Report r = new FeedbackCalibrator().calibrate(tmp, report, coeffs, 0.6, 0.2);
    assertTrue(r.calibrated);
    assertTrue(r.trainSize >= 1);
    assertTrue(Files.isRegularFile(report));
    assertTrue(Files.isRegularFile(coeffs));
    assertTrue(r.filterPassRate != null && r.filterPassRate.doubleValue() > 0);
    assertEquals(CostModel.MODEL_VERSION, r.modelVersion);
  }
}
