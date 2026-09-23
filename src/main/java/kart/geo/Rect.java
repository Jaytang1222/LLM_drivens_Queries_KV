package kart.geo;

/** Closed axis-aligned rectangle in meters. */
public final class Rect {
  public final double minX;
  public final double minY;
  public final double maxX;
  public final double maxY;

  public Rect(double minX, double minY, double maxX, double maxY) {
    this.minX = minX;
    this.minY = minY;
    this.maxX = maxX;
    this.maxY = maxY;
  }

  public boolean contains(double x, double y) {
    return x >= minX && x <= maxX && y >= minY && y <= maxY;
  }

  public Rect expandMeters(double margin) {
    return new Rect(minX - margin, minY - margin, maxX + margin, maxY + margin);
  }
}
