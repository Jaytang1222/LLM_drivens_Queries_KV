package kart.snapshot;

import kart.data.AisParser;
import kart.data.Cleaner;
import kart.data.PortoParser;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Load / clean / tid-assign trajectory files the same way as build/verify.
 */
public final class TDriveLoader {

  /** Default raw trajectories per clean batch for streaming loaders. */
  public static final int DEFAULT_STREAM_BATCH = 2000;

  /** Consume cleaned trajectories (tid still placeholder 0). */
  public interface CleanedBatchConsumer {
    void accept(List<Trajectory> cleaned) throws Exception;
  }

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
    PortoParser porto = "porto".equals(fmt) ? new PortoParser() : null;
    if (tdrive == null && ais == null && porto == null) {
      throw new IllegalArgumentException("unsupported format: " + format + " (use tdrive|ais|porto)");
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
            } else if (porto != null) {
              raws.add(porto.parseLine(line));
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

  /**
   * Stream parse→clean in batches without retaining the full dataset.
   * Trajectories keep placeholder tid=0; assign via {@link #inventoryTids} + {@link TidAssigner#withTid}.
   */
  public static void forEachCleanedBatch(Path data, Rect domain, String format, String crs,
      int batchRawCount, CleanedBatchConsumer consumer) throws Exception {
    if (consumer == null) {
      throw new IllegalArgumentException("consumer required");
    }
    int batch = batchRawCount <= 0 ? DEFAULT_STREAM_BATCH : batchRawCount;
    String fmt = format == null ? "tdrive" : format.trim().toLowerCase();
    List<Path> files = SnapshotBuilder.listInputFiles(data, fmt);
    TDriveParser tdrive = "tdrive".equals(fmt) ? new TDriveParser() : null;
    AisParser ais = "ais".equals(fmt) ? new AisParser() : null;
    PortoParser porto = "porto".equals(fmt) ? new PortoParser() : null;
    if (tdrive == null && ais == null && porto == null) {
      throw new IllegalArgumentException("unsupported format: " + format + " (use tdrive|ais|porto)");
    }
    Cleaner cleaner = new Cleaner(new Projection(crs), domain);
    List<RawTrajectory> raws = new ArrayList<RawTrajectory>(batch);
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
            } else if (porto != null) {
              raws.add(porto.parseLine(line));
            } else {
              raws.add(tdrive.parseLine(line));
            }
          } catch (IllegalArgumentException ignored) {
            // skip bad lines (same as verify-snapshot)
          }
          if (raws.size() >= batch) {
            flushCleanBatch(cleaner, raws, consumer);
          }
        }
      }
    }
    if (!raws.isEmpty()) {
      flushCleanBatch(cleaner, raws, consumer);
    }
  }

  /**
   * First pass: trajectory_id → tid matching {@link TidAssigner#assign} (lexicographic ids, tid from 1).
   */
  public static Map<String, Long> inventoryTids(Path data, Rect domain, String format, String crs,
      int batchRawCount) throws Exception {
    final TreeSet<String> ids = new TreeSet<String>();
    forEachCleanedBatch(data, domain, format, crs, batchRawCount, new CleanedBatchConsumer() {
      @Override
      public void accept(List<Trajectory> cleaned) {
        for (Trajectory t : cleaned) {
          if (t != null && t.trajectoryId != null) {
            ids.add(t.trajectoryId);
          }
        }
      }
    });
    Map<String, Long> out = new HashMap<String, Long>(Math.max(16, ids.size() * 2));
    long tid = 1L;
    for (String id : ids) {
      out.put(id, Long.valueOf(tid));
      tid++;
    }
    return out;
  }

  private static void flushCleanBatch(Cleaner cleaner, List<RawTrajectory> raws,
      CleanedBatchConsumer consumer) throws Exception {
    Cleaner.Result cleaned = cleaner.cleanAll(raws);
    raws.clear();
    consumer.accept(cleaned.trajectories);
  }
}
