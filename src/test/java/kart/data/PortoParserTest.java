package kart.data;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortoParserTest {

  private final PortoParser parser = new PortoParser();

  @Test
  void parsesOfficialCsvRow() {
    String line = "\"1372636858620000589\",\"C\",\"\",\"\",\"20000589\",\"1372636858\",\"A\",\"False\","
        + "\"[[-8.618643,41.141412],[-8.618499,41.141376],[-8.620326,41.14251]]\"";
    RawTrajectory t = parser.parseLine(line);
    assertEquals("20000589", t.vehicleId);
    assertEquals("20000589-1372636858620000589", t.trajectoryId);
    assertEquals(3, t.points.size());
    assertEquals(-8.618643, t.points.get(0).lon, 1e-9);
    assertEquals(41.141412, t.points.get(0).lat, 1e-9);
    assertEquals(1372636858_000L, t.points.get(0).timestampMs);
    assertEquals(1372636858_000L + PortoParser.SAMPLE_INTERVAL_MS, t.points.get(1).timestampMs);
  }

  @Test
  void rejectsMissingDataAndHeader() {
    assertThrows(IllegalArgumentException.class,
        () -> parser.parseLine("TRIP_ID,CALL_TYPE,ORIGIN_CALL,ORIGIN_STAND,TAXI_ID,TIMESTAMP,DAY_TYPE,MISSING_DATA,POLYLINE"));
    String miss = "\"1\",\"C\",\"\",\"\",\"20000589\",\"1372636858\",\"A\",\"True\",\"[[-8.62,41.14]]\"";
    assertThrows(IllegalArgumentException.class, () -> parser.parseLine(miss));
  }

  @Test
  void rejectsOutOfMetroOutlier() {
    String far = "\"1\",\"C\",\"\",\"\",\"20000589\",\"1372636858\",\"A\",\"False\",\"[[-12.15,45.65]]\"";
    IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> parser.parseLine(far));
    assertTrue(ex.getMessage().contains("outside Porto metro"));
  }
}
