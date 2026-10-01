package kart.data;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;

/**
 * Parses AIS trajectory lines:
 * {@code tid|start|end|lon,lat;lon,lat;...}
 * where start/end are {@code yyyy-MM-dd HH:mm:ss[.S]} (no zone; treated as UTC).
 * Per-point timestamps are linearly interpolated between start and end.
 */
public final class AisParser {

  private final SimpleDateFormat tsFmt;

  public AisParser() {
    tsFmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    tsFmt.setTimeZone(TimeZone.getTimeZone("UTC"));
    tsFmt.setLenient(false);
  }

  public RawTrajectory parseLine(String line) {
    if (line == null) {
      throw new IllegalArgumentException("null line");
    }
    String trimmed = line.trim();
    if (trimmed.isEmpty()) {
      throw new IllegalArgumentException("empty line");
    }
    String[] fields = trimmed.split("\\|", -1);
    if (fields.length != 4) {
      throw new IllegalArgumentException("expected 4 |-separated fields, got " + fields.length);
    }
    String tid = fields[0].trim();
    if (tid.isEmpty()) {
      throw new IllegalArgumentException("empty tid");
    }
    long t0 = parseTimestamp(fields[1].trim());
    long t1 = parseTimestamp(fields[2].trim());
    if (t1 < t0) {
      throw new IllegalArgumentException("end before start");
    }
    String[] coords = fields[3].split(";");
    List<RawTrajectory.RawPoint> points = new ArrayList<RawTrajectory.RawPoint>();
    for (String c : coords) {
      String p = c.trim();
      if (p.isEmpty()) {
        continue;
      }
      int comma = p.indexOf(',');
      if (comma <= 0 || comma == p.length() - 1) {
        throw new IllegalArgumentException("bad lon,lat: " + p);
      }
      try {
        double lon = Double.parseDouble(p.substring(0, comma).trim());
        double lat = Double.parseDouble(p.substring(comma + 1).trim());
        points.add(new RawTrajectory.RawPoint(lon, lat, 0L)); // time filled below
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException("bad number in: " + p, e);
      }
    }
    if (points.isEmpty()) {
      throw new IllegalArgumentException("no points");
    }
    int n = points.size();
    List<RawTrajectory.RawPoint> timed = new ArrayList<RawTrajectory.RawPoint>(n);
    if (n == 1) {
      timed.add(new RawTrajectory.RawPoint(points.get(0).lon, points.get(0).lat, t0));
    } else {
      for (int i = 0; i < n; i++) {
        double frac = (double) i / (double) (n - 1);
        long t = t0 + Math.round(frac * (t1 - t0));
        RawTrajectory.RawPoint src = points.get(i);
        timed.add(new RawTrajectory.RawPoint(src.lon, src.lat, t));
      }
    }
    return new RawTrajectory(tid, "ais-" + tid, timed);
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

  private long parseTimestamp(String raw) {
    if (raw == null || raw.isEmpty()) {
      throw new IllegalArgumentException("empty timestamp");
    }
    // Strip fractional seconds: 2017-05-09 17:24:51.0
    String s = raw;
    int dot = s.indexOf('.');
    if (dot > 0) {
      s = s.substring(0, dot);
    }
    try {
      synchronized (tsFmt) {
        return tsFmt.parse(s).getTime();
      }
    } catch (ParseException e) {
      throw new IllegalArgumentException("bad timestamp: " + raw, e);
    }
  }

  private static String preview(String line) {
    String t = line.trim();
    if (t.length() <= 120) {
      return t;
    }
    return t.substring(0, 117) + "...";
  }
}
