package kart.snapshot;

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
 * Load / clean / tid-assign T-Drive parts the same way as build/verify.
 */
public final class TDriveLoader {

  private TDriveLoader() {}

  public static List<Trajectory> loadCleaned(Path data, Rect domain) throws Exception {
    TDriveParser parser = new TDriveParser();
    List<RawTrajectory> raws = new ArrayList<RawTrajectory>();
    for (Path part : DataProfiler.listParts(data)) {
      try (BufferedReader br = Files.newBufferedReader(part, StandardCharsets.UTF_8)) {
        String line;
        while ((line = br.readLine()) != null) {
          if (line.trim().isEmpty()) {
            continue;
          }
          try {
            raws.add(parser.parseLine(line));
          } catch (IllegalArgumentException ignored) {
            // skip bad lines (same as verify-snapshot)
          }
        }
      }
    }
    Cleaner.Result cleaned = new Cleaner(new Projection(), domain).cleanAll(raws);
    return TidAssigner.assign(cleaned.trajectories);
  }
}
