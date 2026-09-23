package kart.snapshot;

import kart.data.RawTrajectory;
import kart.data.TDriveParser;
import kart.geo.Projection;
import kart.geo.Rect;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Profile T-Drive part files for domain / epoch / quality stats (T1.3).
 */
public final class DataProfiler {

  public static final class Profile {
    public long lines;
    public long trajectories;
    public long points;
    public long parseRejected;
    public long minTs = Long.MAX_VALUE;
    public long maxTs = Long.MIN_VALUE;
    public double minLon = Double.POSITIVE_INFINITY;
    public double maxLon = Double.NEGATIVE_INFINITY;
    public double minLat = Double.POSITIVE_INFINITY;
    public double maxLat = Double.NEGATIVE_INFINITY;
    public double minX = Double.POSITIVE_INFINITY;
    public double maxX = Double.NEGATIVE_INFINITY;
    public double minY = Double.POSITIVE_INFINITY;
    public double maxY = Double.NEGATIVE_INFINITY;
    public long duplicateExactPoints;
    public long pointsOutOfBeijingRough; // lon/lat outside rough Beijing box
    public List<Integer> pointsPerTraj = new ArrayList<Integer>();

    public Rect meterDomainExpanded(double marginMeters) {
      return new Rect(minX, minY, maxX, maxY).expandMeters(marginMeters);
    }

    public long epochMs(long bucketMs) {
      return (minTs / bucketMs) * bucketMs;
    }
  }

  private final Projection projection = new Projection();
  private final TDriveParser parser = new TDriveParser();

  /** Rough Beijing bounding box for anomaly count (not the final domain). */
  private static final double BJ_MIN_LON = 115.0;
  private static final double BJ_MAX_LON = 118.0;
  private static final double BJ_MIN_LAT = 39.0;
  private static final double BJ_MAX_LAT = 41.5;

  public Profile profileDirectory(Path dir) throws IOException {
    List<Path> parts = listParts(dir);
    Profile p = new Profile();
    for (Path part : parts) {
      profileFile(part, p);
    }
    Collections.sort(p.pointsPerTraj);
    return p;
  }

  public Profile profileFile(Path file, Profile p) throws IOException {
    try (BufferedReader br = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
      String line;
      while ((line = br.readLine()) != null) {
        p.lines++;
        if (line.trim().isEmpty()) {
          continue;
        }
        try {
          RawTrajectory t = parser.parseLine(line);
          p.trajectories++;
          p.pointsPerTraj.add(t.points.size());
          RawTrajectory.RawPoint prev = null;
          for (RawTrajectory.RawPoint rp : t.points) {
            p.points++;
            p.minTs = Math.min(p.minTs, rp.timestampMs);
            p.maxTs = Math.max(p.maxTs, rp.timestampMs);
            p.minLon = Math.min(p.minLon, rp.lon);
            p.maxLon = Math.max(p.maxLon, rp.lon);
            p.minLat = Math.min(p.minLat, rp.lat);
            p.maxLat = Math.max(p.maxLat, rp.lat);
            if (rp.lon < BJ_MIN_LON || rp.lon > BJ_MAX_LON || rp.lat < BJ_MIN_LAT || rp.lat > BJ_MAX_LAT) {
              p.pointsOutOfBeijingRough++;
            }
            Projection.Meters m = projection.toUtm(rp.lon, rp.lat);
            p.minX = Math.min(p.minX, m.x);
            p.maxX = Math.max(p.maxX, m.x);
            p.minY = Math.min(p.minY, m.y);
            p.maxY = Math.max(p.maxY, m.y);
            if (prev != null && prev.timestampMs == rp.timestampMs && prev.lon == rp.lon && prev.lat == rp.lat) {
              p.duplicateExactPoints++;
            }
            prev = rp;
          }
        } catch (IllegalArgumentException e) {
          p.parseRejected++;
        }
      }
    }
    return p;
  }

  public static List<Path> listParts(Path dir) throws IOException {
    List<Path> out = new ArrayList<Path>();
    try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "part-*")) {
      for (Path p : ds) {
        out.add(p);
      }
    }
    Collections.sort(out);
    return out;
  }

  public String toMarkdown(Profile p, Path sourceDir) {
    StringBuilder sb = new StringBuilder();
    sb.append("# T-Drive profile\n\n");
    sb.append("- Source: `").append(sourceDir).append("`\n");
    sb.append("- Lines: ").append(p.lines).append('\n');
    sb.append("- Trajectories (parsed): ").append(p.trajectories).append('\n');
    sb.append("- Points: ").append(p.points).append('\n');
    sb.append("- Parse rejected: ").append(p.parseRejected).append('\n');
    sb.append("- Exact duplicate consecutive points: ").append(p.duplicateExactPoints)
        .append(" (").append(pct(p.duplicateExactPoints, p.points)).append("%)\n");
    sb.append("- Points outside rough Beijing box [115–118]×[39–41.5]: ")
        .append(p.pointsOutOfBeijingRough)
        .append(" (").append(pct(p.pointsOutOfBeijingRough, p.points)).append("%)\n");
    sb.append("- Time range (UTC ms): [").append(p.minTs).append(", ").append(p.maxTs).append("]\n");
    sb.append("- Lon/Lat range: lon[").append(p.minLon).append(", ").append(p.maxLon)
        .append("] lat[").append(p.minLat).append(", ").append(p.maxLat).append("]\n");
    sb.append("- UTM50N meters: x[").append(p.minX).append(", ").append(p.maxX)
        .append("] y[").append(p.minY).append(", ").append(p.maxY).append("]\n");
    if (!p.pointsPerTraj.isEmpty()) {
      sb.append("- Points/traj: min=").append(p.pointsPerTraj.get(0))
          .append(" p50=").append(percentile(p.pointsPerTraj, 0.5))
          .append(" p95=").append(percentile(p.pointsPerTraj, 0.95))
          .append(" max=").append(p.pointsPerTraj.get(p.pointsPerTraj.size() - 1)).append('\n');
    }
    Rect domain = p.meterDomainExpanded(1000);
    sb.append("\n## Recommended manifest domain (min/max ±1 km)\n\n");
    sb.append("```\nxmin=").append(domain.minX).append(" xmax=").append(domain.maxX)
        .append(" ymin=").append(domain.minY).append(" ymax=").append(domain.maxY).append("\n```\n");
    sb.append("\n## OI-9 decision\n\n");
    double outPct = pct(p.pointsOutOfBeijingRough, p.points);
    if (outPct < 0.1) {
      sb.append("Out-of-rough-box points < 0.1%. **Default: reject whole trajectory on any domain-out point** ")
          .append("(domain = profile min/max + 1 km). Clamp is not used.\n");
    } else {
      sb.append("Out-of-rough-box points = ").append(outPct)
          .append("%. Review before locking reject-vs-clamp; MVP still defaults to reject trajectory.\n");
    }
    long epoch = p.epochMs(600_000L);
    sb.append("\n## Epoch\n\n");
    sb.append("`epoch_ms = floor(min_ts / 600000) * 600000` = ").append(epoch).append('\n');
    return sb.toString();
  }

  private static double pct(long n, long d) {
    if (d == 0) {
      return 0;
    }
    return Math.round(n * 10000.0 / d) / 100.0;
  }

  private static int percentile(List<Integer> sorted, double q) {
    if (sorted.isEmpty()) {
      return 0;
    }
    int idx = (int) Math.min(sorted.size() - 1, Math.floor(q * (sorted.size() - 1)));
    return sorted.get(idx);
  }
}
