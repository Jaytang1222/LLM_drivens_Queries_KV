package kart.data;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses Porto Taxi (ECML/PKDD 2015) CSV rows:
 * {@code TRIP_ID,CALL_TYPE,...,TAXI_ID,TIMESTAMP,...,MISSING_DATA,POLYLINE}
 * where POLYLINE is {@code [[lon,lat],...]} sampled every 15s from TIMESTAMP (unix sec).
 * Header lines and {@code MISSING_DATA=True} are rejected.
 */
public final class PortoParser {

  /** Official sampling interval for Porto polyline points. */
  public static final long SAMPLE_INTERVAL_MS = 15_000L;

  public RawTrajectory parseLine(String line) {
    if (line == null) {
      throw new IllegalArgumentException("null line");
    }
    String trimmed = line.trim();
    if (trimmed.isEmpty()) {
      throw new IllegalArgumentException("empty line");
    }
    if (trimmed.startsWith("TRIP_ID") || trimmed.startsWith("\"TRIP_ID\"")) {
      throw new IllegalArgumentException("header");
    }

    List<String> cols = splitCsv(trimmed);
    if (cols.size() < 9) {
      throw new IllegalArgumentException("expected >=9 CSV columns, got " + cols.size());
    }
    String tripId = unquote(cols.get(0));
    String taxiId = unquote(cols.get(4));
    String tsRaw = unquote(cols.get(5));
    String missing = unquote(cols.get(7));
    String poly = unquote(cols.get(8));
    if (tripId.isEmpty() || taxiId.isEmpty()) {
      throw new IllegalArgumentException("empty trip/taxi id");
    }
    if ("true".equalsIgnoreCase(missing)) {
      throw new IllegalArgumentException("MISSING_DATA");
    }
    long t0Sec;
    try {
      t0Sec = Long.parseLong(tsRaw);
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("bad TIMESTAMP: " + tsRaw, e);
    }
    List<double[]> coords = parsePolyline(poly);
    if (coords.isEmpty()) {
      throw new IllegalArgumentException("empty polyline");
    }
    List<RawTrajectory.RawPoint> points = new ArrayList<RawTrajectory.RawPoint>(coords.size());
    for (int i = 0; i < coords.size(); i++) {
      double lon = coords.get(i)[0];
      double lat = coords.get(i)[1];
      if (lon < -10.0 || lon > -7.0 || lat < 40.5 || lat > 42.0) {
        throw new IllegalArgumentException("lon/lat outside Porto metro: " + lon + "," + lat);
      }
      long tMs = t0Sec * 1000L + i * SAMPLE_INTERVAL_MS;
      points.add(new RawTrajectory.RawPoint(lon, lat, tMs));
    }
    // trajectory_id mirrors T-Drive style: taxiId-tripId
    String trajectoryId = taxiId + "-" + tripId;
    return new RawTrajectory(taxiId, trajectoryId, points);
  }

  public TDriveParser.ParseResult parseLines(Iterable<String> lines) {
    TDriveParser.ParseResult out = new TDriveParser.ParseResult();
    for (String line : lines) {
      if (line == null || line.trim().isEmpty()) {
        continue;
      }
      try {
        out.accepted.add(parseLine(line));
      } catch (IllegalArgumentException e) {
        out.rejected.add(new TDriveParser.Rejected(preview(line), TDriveParser.RejectReason.PARSE,
            e.getMessage()));
      }
    }
    return out;
  }

  static List<String> splitCsv(String line) {
    List<String> out = new ArrayList<String>();
    StringBuilder cur = new StringBuilder();
    boolean inQuote = false;
    for (int i = 0; i < line.length(); i++) {
      char c = line.charAt(i);
      if (c == '"') {
        if (inQuote && i + 1 < line.length() && line.charAt(i + 1) == '"') {
          cur.append('"');
          i++;
        } else {
          inQuote = !inQuote;
        }
      } else if (c == ',' && !inQuote) {
        out.add(cur.toString());
        cur.setLength(0);
      } else {
        cur.append(c);
      }
    }
    out.add(cur.toString());
    return out;
  }

  private static String unquote(String s) {
    if (s == null) {
      return "";
    }
    String t = s.trim();
    if (t.length() >= 2 && t.charAt(0) == '"' && t.charAt(t.length() - 1) == '"') {
      t = t.substring(1, t.length() - 1).replace("\"\"", "\"");
    }
    return t.trim();
  }

  static List<double[]> parsePolyline(String poly) {
    String s = poly == null ? "" : poly.trim();
    if (s.isEmpty() || "[]".equals(s)) {
      return new ArrayList<double[]>();
    }
    // Expect [[lon,lat],[lon,lat],...]
    if (!s.startsWith("[") || !s.endsWith("]")) {
      throw new IllegalArgumentException("polyline must be JSON-like array");
    }
    List<double[]> out = new ArrayList<double[]>();
    int i = 0;
    int n = s.length();
    while (i < n) {
      int open = s.indexOf('[', i);
      if (open < 0) {
        break;
      }
      // skip outer [
      if (open == 0) {
        i = 1;
        continue;
      }
      int close = s.indexOf(']', open + 1);
      if (close < 0) {
        throw new IllegalArgumentException("unclosed point in polyline");
      }
      String body = s.substring(open + 1, close).trim();
      i = close + 1;
      if (body.isEmpty()) {
        continue;
      }
      // nested point [lon, lat]
      if (body.charAt(0) == '[') {
        continue;
      }
      int comma = body.indexOf(',');
      if (comma <= 0) {
        throw new IllegalArgumentException("bad point: " + body);
      }
      try {
        double lon = Double.parseDouble(body.substring(0, comma).trim());
        double lat = Double.parseDouble(body.substring(comma + 1).trim());
        out.add(new double[] {lon, lat});
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException("bad number in point: " + body, e);
      }
    }
    return out;
  }

  private static String preview(String line) {
    String t = line.trim();
    if (t.length() <= 120) {
      return t;
    }
    return t.substring(0, 117) + "...";
  }
}
