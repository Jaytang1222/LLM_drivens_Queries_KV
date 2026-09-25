package kart.codec;

import org.apache.commons.codec.digest.MurmurHash3;

import java.nio.charset.StandardCharsets;

/**
 * Stable vehicle_id hash (murmur3_x64_128).
 */
public final class VehicleHash {

  public static final String VERSION = "murmur3_x64_128";

  private VehicleHash() {}

  public static byte[] hash128(String vehicleId) {
    byte[] data = vehicleId.getBytes(StandardCharsets.UTF_8);
    long[] hash = MurmurHash3.hash128x64(data);
    // commons-codec returns two longs; pack big-endian
    return Bytes.concat(Bytes.u64(hash[0]), Bytes.u64(hash[1]));
  }

  /** Lowercase hex of {@link #hash128(String)} for stats keys. */
  public static String hex128(String vehicleId) {
    byte[] h = hash128(vehicleId);
    StringBuilder sb = new StringBuilder(h.length * 2);
    for (byte b : h) {
      sb.append(String.format("%02x", b & 0xff));
    }
    return sb.toString();
  }
}
