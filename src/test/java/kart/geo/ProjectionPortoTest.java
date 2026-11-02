package kart.geo;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectionPortoTest {

  @Test
  void portoCrsProjectsCityCenterIntoUtm29() {
    Projection porto = new Projection(Projection.PORTO_CRS);
    assertEquals(Projection.PORTO_CRS, porto.crs());
    // Ribeira / downtown Porto
    Projection.Meters m = porto.toUtm(-8.615, 41.141);
    assertTrue(m.x > 500_000 && m.x < 600_000, "easting=" + m.x);
    assertTrue(m.y > 4_500_000 && m.y < 4_600_000, "northing=" + m.y);
    Projection.LonLat back = porto.toWgs84(m.x, m.y);
    assertEquals(-8.615, back.lon, 1e-6);
    assertEquals(41.141, back.lat, 1e-6);
  }
}
