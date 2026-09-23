package kart.codec;

import java.util.Arrays;
import java.util.Optional;

/**
 * prefixSuccessor(P): find rightmost non-0xFF byte, increment, truncate.
 * All-0xFF returns empty.
 */
public final class PrefixSuccessor {

  private PrefixSuccessor() {}

  public static Optional<byte[]> of(byte[] prefix) {
    if (prefix == null || prefix.length == 0) {
      throw new IllegalArgumentException("prefix must be non-empty");
    }
    byte[] p = Arrays.copyOf(prefix, prefix.length);
    for (int i = p.length - 1; i >= 0; i--) {
      if ((p[i] & 0xFF) != 0xFF) {
        p[i] = (byte) ((p[i] & 0xFF) + 1);
        return Optional.of(Arrays.copyOf(p, i + 1));
      }
    }
    return Optional.empty();
  }
}
