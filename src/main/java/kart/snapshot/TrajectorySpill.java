package kart.snapshot;

import kart.data.CanonicalPoint;
import kart.data.Trajectory;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Disk spill of cleaned trajectories (encounter order) so snapshot build need not hold
 * all points in heap. Tid order is derived from sorted trajectory ids after spill completes.
 */
public final class TrajectorySpill implements Closeable {

  private static final int VERSION = 1;

  private final Path dir;
  private final Path dataFile;
  private final Path offsetsFile;
  private final Path idsFile;
  private final Path completeFile;

  private DataOutputStream out;
  private final List<Long> offsets = new ArrayList<Long>();
  private final List<String> encounterIds = new ArrayList<String>();
  private long bytesWritten;
  private boolean writing;
  private boolean complete;

  /** tid-sorted: order[tid0] = encounter index. */
  private int[] tidToEncounter;
  private RandomAccessData data;

  private TrajectorySpill(Path dir) {
    this.dir = dir;
    this.dataFile = dir.resolve("data.bin");
    this.offsetsFile = dir.resolve("offsets.bin");
    this.idsFile = dir.resolve("ids.txt");
    this.completeFile = dir.resolve("COMPLETE");
  }

  public static boolean isComplete(Path dir) {
    return dir != null
        && Files.isRegularFile(dir.resolve("COMPLETE"))
        && Files.isRegularFile(dir.resolve("data.bin"))
        && Files.isRegularFile(dir.resolve("offsets.bin"))
        && Files.isRegularFile(dir.resolve("ids.txt"));
  }

  public static TrajectorySpill create(Path dir) throws IOException {
    Files.createDirectories(dir);
    TrajectorySpill spill = new TrajectorySpill(dir);
    OutputStream raw = Files.newOutputStream(spill.dataFile);
    spill.out = new DataOutputStream(new BufferedOutputStream(raw, 1 << 20));
    spill.out.writeInt(VERSION);
    spill.bytesWritten = 4;
    spill.writing = true;
    return spill;
  }

  public static TrajectorySpill openComplete(Path dir) throws IOException {
    if (!isComplete(dir)) {
      throw new IllegalStateException("incomplete spill: " + dir);
    }
    TrajectorySpill spill = new TrajectorySpill(dir);
    spill.loadIndex();
    spill.data = RandomAccessData.open(spill.dataFile);
    spill.complete = true;
    return spill;
  }

  public Path dir() {
    return dir;
  }

  public int size() {
    return encounterIds.size();
  }

  /** Append one cleaned trajectory (tid may be placeholder 0). */
  public void append(Trajectory t) throws IOException {
    if (!writing || out == null) {
      throw new IllegalStateException("spill not open for write");
    }
    offsets.add(bytesWritten);
    encounterIds.add(t.trajectoryId);
    writeTrajectory(out, t);
    // DataOutputStream size tracking: use channel/position via counting wrapper
    // writeTrajectory updates via counting — see Counting below
  }

  private void writeTrajectory(DataOutputStream dos, Trajectory t) throws IOException {
    CountingOutputStream count = new CountingOutputStream(dos);
    DataOutputStream w = new DataOutputStream(count);
    writeUtf(w, t.vehicleId);
    writeUtf(w, t.trajectoryId);
    w.writeLong(t.dedupRemoved);
    w.writeLong(t.sameTDropped);
    w.writeInt(t.size());
    for (CanonicalPoint p : t.points()) {
      w.writeLong(p.timestampMs);
      w.writeDouble(p.xM);
      w.writeDouble(p.yM);
    }
    w.flush();
    bytesWritten += count.written;
  }

  public void finish() throws IOException {
    if (!writing) {
      return;
    }
    out.flush();
    out.close();
    out = null;
    writing = false;

    try (DataOutputStream o = new DataOutputStream(new BufferedOutputStream(
        Files.newOutputStream(offsetsFile), 1 << 16))) {
      o.writeInt(offsets.size());
      for (Long off : offsets) {
        o.writeLong(off);
      }
    }
    try (BufferedWriter w = Files.newBufferedWriter(idsFile, StandardCharsets.UTF_8)) {
      for (String id : encounterIds) {
        w.write(id);
        w.newLine();
      }
    }
    buildTidOrder();
    Files.write(completeFile, ("n=" + encounterIds.size() + "\n").getBytes(StandardCharsets.UTF_8));
    data = RandomAccessData.open(dataFile);
    complete = true;
    System.out.println("SPILL_PHASE=done trajectories=" + encounterIds.size()
        + " bytes=" + bytesWritten + " dir=" + dir);
    System.out.flush();
  }

  private void loadIndex() throws IOException {
    try (DataInputStream in = new DataInputStream(new BufferedInputStream(
        Files.newInputStream(offsetsFile), 1 << 16))) {
      int n = in.readInt();
      offsets.clear();
      for (int i = 0; i < n; i++) {
        offsets.add(in.readLong());
      }
    }
    encounterIds.clear();
    try (BufferedReader br = Files.newBufferedReader(idsFile, StandardCharsets.UTF_8)) {
      String line;
      while ((line = br.readLine()) != null) {
        encounterIds.add(line);
      }
    }
    if (offsets.size() != encounterIds.size()) {
      throw new IllegalStateException("spill offsets=" + offsets.size()
          + " ids=" + encounterIds.size());
    }
    buildTidOrder();
  }

  private void buildTidOrder() {
    int n = encounterIds.size();
    Integer[] order = new Integer[n];
    for (int i = 0; i < n; i++) {
      order[i] = i;
    }
    final List<String> ids = encounterIds;
    java.util.Arrays.sort(order, new Comparator<Integer>() {
      @Override
      public int compare(Integer a, Integer b) {
        return ids.get(a).compareTo(ids.get(b));
      }
    });
    tidToEncounter = new int[n];
    for (int tid0 = 0; tid0 < n; tid0++) {
      tidToEncounter[tid0] = order[tid0];
    }
  }

  /** Encounter index for tid-sorted position {@code tid0} (0-based; tid = tid0+1). */
  public int encounterIndexForTid0(int tid0) {
    return tidToEncounter[tid0];
  }

  public Trajectory readByTid0(int tid0) throws IOException {
    return readEncounter(tidToEncounter[tid0]);
  }

  public Trajectory readEncounter(int encounterIndex) throws IOException {
    if (!complete || data == null) {
      throw new IllegalStateException("spill not ready for read");
    }
    long off = offsets.get(encounterIndex);
    return data.readAt(off);
  }

  public List<String> encounterIdsView() {
    return Collections.unmodifiableList(encounterIds);
  }

  @Override
  public void close() throws IOException {
    if (out != null) {
      try {
        out.close();
      } finally {
        out = null;
      }
    }
    if (data != null) {
      data.close();
      data = null;
    }
  }

  public static void deleteQuietly(Path dir) {
    if (dir == null || !Files.exists(dir)) {
      return;
    }
    try {
      Path[] files = new Path[]{
          dir.resolve("COMPLETE"),
          dir.resolve("data.bin"),
          dir.resolve("offsets.bin"),
          dir.resolve("ids.txt"),
          dir.resolve("meta.json")
      };
      for (Path f : files) {
        Files.deleteIfExists(f);
      }
      Files.deleteIfExists(dir);
    } catch (IOException ignored) {
      // best-effort
    }
  }

  private static void writeUtf(DataOutputStream w, String s) throws IOException {
    byte[] b = (s == null ? "" : s).getBytes(StandardCharsets.UTF_8);
    w.writeInt(b.length);
    w.write(b);
  }

  private static String readUtf(DataInputStream in) throws IOException {
    int n = in.readInt();
    if (n < 0 || n > 1_000_000) {
      throw new IOException("bad utf length " + n);
    }
    byte[] b = new byte[n];
    in.readFully(b);
    return new String(b, StandardCharsets.UTF_8);
  }

  private static final class CountingOutputStream extends OutputStream {
    private final OutputStream delegate;
    long written;

    CountingOutputStream(OutputStream delegate) {
      this.delegate = delegate;
    }

    @Override
    public void write(int b) throws IOException {
      delegate.write(b);
      written++;
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
      delegate.write(b, off, len);
      written += len;
    }

    @Override
    public void flush() throws IOException {
      delegate.flush();
    }
  }

  /** Random-access reader over data.bin (shared file handle). */
  private static final class RandomAccessData implements Closeable {
    private final java.io.RandomAccessFile raf;

    private RandomAccessData(java.io.RandomAccessFile raf) {
      this.raf = raf;
    }

    static RandomAccessData open(Path file) throws IOException {
      return new RandomAccessData(new java.io.RandomAccessFile(file.toFile(), "r"));
    }

    Trajectory readAt(long offset) throws IOException {
      raf.seek(offset);
      // wrap RAF for DataInput — use a stream that reads from current pos
      DataInputStream in = new DataInputStream(new InputStream() {
        @Override
        public int read() throws IOException {
          return raf.read();
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
          return raf.read(b, off, len);
        }
      });
      String vehicleId = readUtf(in);
      String trajectoryId = readUtf(in);
      long dedup = in.readLong();
      long sameT = in.readLong();
      int n = in.readInt();
      if (n < 0 || n > 50_000_000) {
        throw new IOException("bad point count " + n);
      }
      List<CanonicalPoint> pts = new ArrayList<CanonicalPoint>(n);
      for (int i = 0; i < n; i++) {
        long ts = in.readLong();
        double x = in.readDouble();
        double y = in.readDouble();
        pts.add(new CanonicalPoint(vehicleId, trajectoryId, 0L, i, ts, x, y));
      }
      Trajectory t = new Trajectory(vehicleId, trajectoryId, 0L, pts);
      t.dedupRemoved = dedup;
      t.sameTDropped = sameT;
      return t;
    }

    @Override
    public void close() throws IOException {
      raf.close();
    }
  }
}
