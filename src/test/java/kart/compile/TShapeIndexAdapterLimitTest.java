package kart.compile;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public final class TShapeIndexAdapterLimitTest {

  @Test
  void configuredLimitCanOnlyLowerTheRequestedRangeBudget() {
    assertEquals(8, TShapeIndexAdapter.boundedMaxRanges(64, "8"));
    assertEquals(8, TShapeIndexAdapter.boundedMaxRanges(8, "16"));
    assertEquals(64, TShapeIndexAdapter.boundedMaxRanges(64, "invalid"));
    assertEquals(64, TShapeIndexAdapter.boundedMaxRanges(64, "0"));
    assertEquals(1, TShapeIndexAdapter.boundedMaxRanges(0, null));
  }
}
