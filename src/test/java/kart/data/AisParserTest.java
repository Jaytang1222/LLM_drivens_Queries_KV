package kart.data;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AisParserTest {

  private final AisParser parser = new AisParser();

  @Test
  void parsesAndInterpolatesEndpoints() {
    String line = "1|2017-05-09 17:24:51.0|2017-05-09 18:41:51.0|"
        + "23.54969,37.9606733333333;23.5505283333333,37.9583816666667";
    RawTrajectory t = parser.parseLine(line);
    assertEquals("1", t.vehicleId);
    assertEquals("ais-1", t.trajectoryId);
    assertEquals(2, t.points.size());
    assertEquals(23.54969, t.points.get(0).lon, 1e-9);
    assertEquals(37.9606733333333, t.points.get(0).lat, 1e-9);
    // 2017-05-09 17:24:51 UTC
    assertEquals(1494350691000L, t.points.get(0).timestampMs);
    assertEquals(1494355311000L, t.points.get(1).timestampMs);
    assertTrue(t.points.get(1).timestampMs > t.points.get(0).timestampMs);
  }

  @Test
  void singlePointUsesStartTime() {
    String line = "9|2017-05-09 14:00:00.0|2017-05-09 15:00:00.0|23.5,37.9";
    RawTrajectory t = parser.parseLine(line);
    assertEquals(1, t.points.size());
    assertEquals(1494338400000L, t.points.get(0).timestampMs);
  }

  @Test
  void rejectsBadRows() {
    assertThrows(IllegalArgumentException.class, () -> parser.parseLine(""));
    assertThrows(IllegalArgumentException.class, () -> parser.parseLine("only-one-field"));
    assertThrows(IllegalArgumentException.class,
        () -> parser.parseLine("1|badts|2017-05-09 15:00:00.0|23.5,37.9"));
    assertThrows(IllegalArgumentException.class,
        () -> parser.parseLine("1|2017-05-09 16:00:00.0|2017-05-09 15:00:00.0|23.5,37.9"));
  }

  @Test
  void parseLinesCollectsRejects() {
    TDriveParser.ParseResult r = parser.parseLines(Arrays.asList(
        "1|2017-05-09 14:00:00.0|2017-05-09 15:00:00.0|23.5,37.9",
        "BAD",
        "2|2017-05-09 14:00:00.0|2017-05-09 15:00:00.0|23.6,37.8"
    ));
    assertEquals(2, r.accepted.size());
    assertEquals(1, r.rejected.size());
  }
}
