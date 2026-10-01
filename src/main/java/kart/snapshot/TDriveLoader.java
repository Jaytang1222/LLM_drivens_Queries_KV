package kart.snapshot;

import kart.data.AisParser;
import kart.data.Cleaner;
import kart.data.RawTrajectory;
import kart.data.TDriveParser;
import kart.data.TidAssigner;
import kart.data.Trajectory;
import kart.geo.Projection;
import kart.geo.Rect;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Load / clean / tid-assign trajectory files the same way as build/verify.
 */
public final class TDriveLoader {

  private TDriveLoader() {}

  public static List<Trajectory> loadCleaned(Path data, Rect domain) throws Exception {
    return loadCleaned(data, domain, "tdrive", Projection.DEFAULT_CRS);
  }

  public static List<Trajectory> loadCleaned(Path data, Rect domain, String format, String crs)
      throws Exception {
    String fmt = format == null ? "tdrive" : format.trim().toLowerCase();
    List<Path> files = SnapshotBuilder.listInputFiles(data, fmt);
    List<RawTrajectory> raws = new ArrayList<RawTrajectory>();
    TDriveParser tdrive = "tdrive".equals(fmt) ? new TDriveParser() : null;
    AisParser ais = "ais".equals(fmt) ? new AisParser() : null;
    if (tdrive == null && ais == null) {
      throw new IllegalArgumentException("unsupported format: " + format);
    }
    for (Path part : files) {
      try (BufferedReader br = Files.newBufferedReader(part, StandardCharsets.UTF_8)) {
        String line;
        while ((line = br.readLine()) != null) {
          if (line.trim().isEmpty()) {
            continue;
          }
          try {
            if (ais != null) {
              raws.add(ais.parseLine(line));
            } else {
              raws.add(tdrive.parseLine(line));
            }
          } catch (IllegalArgumentException ignored) {
            // skip bad lines (same as verify-snapshot)
          }
        }
      }
    }
    Cleaner.Result cleaned = new Cleaner(new Projection(crs), domain).cleanAll(raws);
    return TidAssigner.assign(cleaned.trajectories);
  }
}
