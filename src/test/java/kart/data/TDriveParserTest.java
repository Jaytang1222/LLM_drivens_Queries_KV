package kart.data;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TDriveParserTest {

  private final TDriveParser parser = new TDriveParser();

  @Test
  void parsesSampleLine() {
    String line = "3644-3644_1-MULTIPOINT Z((116.37497 39.85789 1201930859000), (116.37542 39.85764 1201930931000))";
    RawTrajectory t = parser.parseLine(line);
    assertEquals("3644", t.vehicleId);
    assertEquals("3644-3644_1", t.trajectoryId);
    assertEquals(2, t.points.size());
    assertEquals(116.37497, t.points.get(0).lon, 1e-9);
    assertEquals(39.85789, t.points.get(0).lat, 1e-9);
    assertEquals(1201930859000L, t.points.get(0).timestampMs);
  }

  @Test
  void rejectsFiveBadRows() {
    List<String> bad = Arrays.asList(
        "",
        "no-marker-here",
        "3644-MULTIPOINT Z((1 2 3))", // missing segment dash properly? actually 3644- then MULTIPOINT - wait
        "3644-seg-MULTIPOINT Z((116.3 39.8 notanumber))",
        "3644-seg-MULTIPOINT Z((116.3 39.8))", // only 2 fields
        "3644-seg-MULTIPOINT Z((116.3 39.8 1), (bad))"
    );
    // empty skipped by parseLines; use parseLine for empties
    assertThrows(IllegalArgumentException.class, () -> parser.parseLine("   "));
    assertThrows(IllegalArgumentException.class, () -> parser.parseLine(bad.get(1)));
    assertThrows(IllegalArgumentException.class, () -> parser.parseLine("onlyid-MULTIPOINT Z((1 2 3))")); // vehicle-segment: onlyid has no dash
    // "onlyid-MULTIPOINT" — idPart = "onlyid", no dash → fail
    assertThrows(IllegalArgumentException.class, () -> parser.parseLine(bad.get(3)));
    assertThrows(IllegalArgumentException.class, () -> parser.parseLine(bad.get(4)));
    assertThrows(IllegalArgumentException.class, () -> parser.parseLine(bad.get(5)));
  }

  @Test
  void parseLinesCollectsRejects() {
    TDriveParser.ParseResult r = parser.parseLines(Arrays.asList(
        "3644-a-MULTIPOINT Z((116.0 39.0 1))",
        "BAD",
        "3655-b-MULTIPOINT Z((116.1 39.1 2))"
    ));
    assertEquals(2, r.accepted.size());
    assertEquals(1, r.rejected.size());
    assertEquals(TDriveParser.RejectReason.PARSE, r.rejected.get(0).reason);
  }
}
