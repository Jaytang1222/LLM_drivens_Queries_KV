package kart.compile;

import java.util.ArrayList;
import java.util.List;

/**
 * A single physical range scan: [startHex, stopHex) on one table.
 */
public final class ScanTask {

  public String table;
  public String startHex;
  public String stopHex;
  public List<String> columns = new ArrayList<String>();
  public int caching = 1000;
  public String sourceNodeId;
  public int shard;
  /** Human/machine-readable coverage descriptor, e.g. "bucket:42" or "z:[8,15]". */
  public String coverageRef;
  /**
   * True if the compiler truncated the intended cover (must fail PhysicalSafetyCheck).
   * Widening ranges to stay under caps is not truncation; dropping cells is.
   */
  public boolean truncated;

  public ScanTask() {}

  public ScanTask(String table, byte[] start, byte[] stop,
                  String sourceNodeId, int shard, String coverageRef) {
    this(table, start, stop, sourceNodeId, shard, coverageRef, false);
  }

  public ScanTask(String table, byte[] start, byte[] stop,
                  String sourceNodeId, int shard, String coverageRef, boolean truncated) {
    this.table = table;
    this.startHex = toHex(start);
    this.stopHex = toHex(stop);
    this.sourceNodeId = sourceNodeId;
    this.shard = shard;
    this.coverageRef = coverageRef;
    this.truncated = truncated;
  }

  public byte[] startBytes() {
    return fromHex(startHex);
  }

  public byte[] stopBytes() {
    return fromHex(stopHex);
  }

  private static final char[] HEX = "0123456789abcdef".toCharArray();

  public static String toHex(byte[] b) {
    if (b == null) {
      return null;
    }
    char[] out = new char[b.length * 2];
    for (int i = 0; i < b.length; i++) {
      int v = b[i] & 0xFF;
      out[i * 2] = HEX[v >>> 4];
      out[i * 2 + 1] = HEX[v & 0x0F];
    }
    return new String(out);
  }

  public static byte[] fromHex(String s) {
    if (s == null) {
      return null;
    }
    if (s.length() % 2 != 0) {
      throw new IllegalArgumentException("odd hex length: " + s);
    }
    byte[] out = new byte[s.length() / 2];
    for (int i = 0; i < out.length; i++) {
      out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
    }
    return out;
  }
}
