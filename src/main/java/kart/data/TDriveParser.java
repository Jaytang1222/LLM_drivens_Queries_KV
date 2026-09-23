package kart.data;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses {@code taxiId-segmentId-MULTIPOINT Z((lon lat t), ...)} lines.
 * Any field failure rejects the whole trajectory (REJECT_PARSE).
 */
public final class TDriveParser {

  public enum RejectReason {
    PARSE,
    OUT_OF_DOMAIN,
    EMPTY
  }

  public static final class Rejected {
    public final String linePreview;
    public final RejectReason reason;
    public final String detail;

    public Rejected(String linePreview, RejectReason reason, String detail) {
      this.linePreview = linePreview;
      this.reason = reason;
      this.detail = detail;
    }
  }

  public static final class ParseResult {
    public final List<RawTrajectory> accepted = new ArrayList<RawTrajectory>();
    public final List<Rejected> rejected = new ArrayList<Rejected>();
  }

  private static final String MARKER = "-MULTIPOINT Z(";

  public RawTrajectory parseLine(String line) {
    if (line == null) {
      throw new IllegalArgumentException("null line");
    }
    String trimmed = line.trim();
    if (trimmed.isEmpty()) {
      throw new IllegalArgumentException("empty line");
    }
    int marker = trimmed.indexOf(MARKER);
    if (marker < 0) {
      throw new IllegalArgumentException("missing -MULTIPOINT Z(");
    }
    String idPart = trimmed.substring(0, marker);
    int dash = idPart.indexOf('-');
    if (dash <= 0 || dash == idPart.length() - 1) {
      throw new IllegalArgumentException("bad trajectory_id prefix: " + idPart);
    }
    String vehicleId = idPart.substring(0, dash);
    String trajectoryId = idPart; // taxiId-segmentId as opaque external id

    String rest = trimmed.substring(marker + MARKER.length());
    if (!rest.endsWith(")")) {
      throw new IllegalArgumentException("missing closing )");
    }
    // strip outer closing paren of MULTIPOINT Z( ... )
    String body = rest.substring(0, rest.length() - 1).trim();
    // body starts with '(' of first point group: ((lon lat t), (lon lat t), ...)
    if (body.startsWith("(") && body.endsWith(")")) {
      // keep as-is; split on "), ("
    } else if (!body.startsWith("(")) {
      throw new IllegalArgumentException("points must start with (");
    }

    List<RawTrajectory.RawPoint> points = new ArrayList<RawTrajectory.RawPoint>();
    // Split by "), (" while allowing nested? points are flat triples.
    String[] parts = body.split("\\), \\(");
    for (int i = 0; i < parts.length; i++) {
      String part = parts[i].trim();
      if (part.startsWith("(")) {
        part = part.substring(1);
      }
      if (part.endsWith(")")) {
        part = part.substring(0, part.length() - 1);
      }
      String[] tok = part.trim().split("\\s+");
      if (tok.length != 3) {
        throw new IllegalArgumentException("point must have 3 fields, got " + tok.length + " in: " + part);
      }
      try {
        double lon = Double.parseDouble(tok[0]);
        double lat = Double.parseDouble(tok[1]);
        long t = Long.parseLong(tok[2]);
        points.add(new RawTrajectory.RawPoint(lon, lat, t));
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException("bad number in point: " + part, e);
      }
    }
    if (points.isEmpty()) {
      throw new IllegalArgumentException("no points");
    }
    return new RawTrajectory(vehicleId, trajectoryId, points);
  }

  public ParseResult parseLines(Iterable<String> lines) {
    ParseResult out = new ParseResult();
    for (String line : lines) {
      if (line == null || line.trim().isEmpty()) {
        continue;
      }
      try {
        out.accepted.add(parseLine(line));
      } catch (IllegalArgumentException e) {
        out.rejected.add(new Rejected(preview(line), RejectReason.PARSE, e.getMessage()));
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
