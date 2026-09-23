package kart.geo;

import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.locationtech.proj4j.CoordinateTransform;
import org.locationtech.proj4j.CoordinateTransformFactory;
import org.locationtech.proj4j.ProjCoordinate;

/**
 * WGS84 (EPSG:4326) ↔ UTM 50N (EPSG:32650) via Proj4J.
 */
public final class Projection {

  private final CoordinateTransform toUtm;
  private final CoordinateTransform toWgs;

  public Projection() {
    CRSFactory crsFactory = new CRSFactory();
    CoordinateReferenceSystem wgs84 = crsFactory.createFromName("EPSG:4326");
    CoordinateReferenceSystem utm50n = crsFactory.createFromName("EPSG:32650");
    CoordinateTransformFactory ctf = new CoordinateTransformFactory();
    this.toUtm = ctf.createTransform(wgs84, utm50n);
    this.toWgs = ctf.createTransform(utm50n, wgs84);
  }

  public static final class LonLat {
    public final double lon;
    public final double lat;

    public LonLat(double lon, double lat) {
      this.lon = lon;
      this.lat = lat;
    }
  }

  public static final class Meters {
    public final double x;
    public final double y;

    public Meters(double x, double y) {
      this.x = x;
      this.y = y;
    }
  }

  public Meters toUtm(double lon, double lat) {
    ProjCoordinate src = new ProjCoordinate(lon, lat);
    ProjCoordinate dst = new ProjCoordinate();
    toUtm.transform(src, dst);
    return new Meters(dst.x, dst.y);
  }

  public LonLat toWgs84(double x, double y) {
    ProjCoordinate src = new ProjCoordinate(x, y);
    ProjCoordinate dst = new ProjCoordinate();
    toWgs.transform(src, dst);
    return new LonLat(dst.x, dst.y);
  }

  /** Axis-aligned meter bbox of the four projected corners of a lon/lat rectangle. */
  public Rect metersEnvelope(double minLon, double minLat, double maxLon, double maxLat) {
    Meters[] corners = new Meters[]{
        toUtm(minLon, minLat),
        toUtm(minLon, maxLat),
        toUtm(maxLon, minLat),
        toUtm(maxLon, maxLat)
    };
    double minX = Double.POSITIVE_INFINITY;
    double minY = Double.POSITIVE_INFINITY;
    double maxX = Double.NEGATIVE_INFINITY;
    double maxY = Double.NEGATIVE_INFINITY;
    for (Meters m : corners) {
      minX = Math.min(minX, m.x);
      minY = Math.min(minY, m.y);
      maxX = Math.max(maxX, m.x);
      maxY = Math.max(maxY, m.y);
    }
    return new Rect(minX, minY, maxX, maxY);
  }
}
