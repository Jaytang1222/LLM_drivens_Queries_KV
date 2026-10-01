package kart.geo;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectionTest {

  private final Projection proj = new Projection();

  @Test
  void tiananmenKnownApprox() {
    // Tiananmen ~ 116.3974, 39.9093 — UTM 50N around E 457xxx N 4417xxx
    Projection.Meters m = proj.toUtm(116.397428, 39.90923);
    // UTM 50N easting for central Beijing is typically ~440–460 km
    assertTrue(m.x > 440000 && m.x < 460000, "easting=" + m.x);
    assertTrue(m.y > 4410000 && m.y < 4425000, "northing=" + m.y);
  }

  @Test
  void roundTripErrorSmall() {
    double lon = 116.391, lat = 39.907;
    Projection.Meters m = proj.toUtm(lon, lat);
    Projection.LonLat back = proj.toWgs84(m.x, m.y);
    assertEquals(lon, back.lon, 1e-6);
    assertEquals(lat, back.lat, 1e-6);
  }

  @Test
  void threeKnownPointsStable() {
    double[][] pts = {
        {116.397428, 39.90923},
        {116.3100, 39.9800},
        {116.4500, 39.8500}
    };
    for (double[] p : pts) {
      Projection.Meters m = proj.toUtm(p[0], p[1]);
      Projection.LonLat b = proj.toWgs84(m.x, m.y);
      assertEquals(p[0], b.lon, 1e-6);
      assertEquals(p[1], b.lat, 1e-6);
    }
  }

  @Test
  void aisCrsProjectsGreekLonLatIntoUtm34() {
    Projection ais = new Projection(Projection.AIS_CRS);
    assertEquals(Projection.AIS_CRS, ais.crs());
    Projection.Meters m = ais.toUtm(23.55, 37.95);
    // UTM 34N: false easting 500km; Aegean easting typically 300–700 km, northing ~4.1–4.3e6
    assertTrue(m.x > 200000 && m.x < 800000, "easting=" + m.x);
    assertTrue(m.y > 4_100_000 && m.y < 4_300_000, "northing=" + m.y);
    Projection.LonLat back = ais.toWgs84(m.x, m.y);
    assertEquals(23.55, back.lon, 1e-6);
    assertEquals(37.95, back.lat, 1e-6);
  }
}
