package kart.validation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * Ordered findings from validator checks.
 */
public final class ValidationReport {

  public static final class Finding {
    public final String check;
    public final boolean ok;
    public final String message;

    public Finding(String check, boolean ok, String message) {
      this.check = check;
      this.ok = ok;
      this.message = message;
    }

    @Override
    public String toString() {
      return (ok ? "PASS " : "FAIL ") + check + ": " + message;
    }
  }

  private final List<Finding> findings = new ArrayList<Finding>();

  public void pass(String check, String message) {
    findings.add(new Finding(check, true, message));
  }

  public void fail(String check, String message) {
    findings.add(new Finding(check, false, message));
  }

  public List<Finding> findings() {
    return findings;
  }

  public boolean ok() {
    for (Finding f : findings) {
      if (!f.ok) {
        return false;
      }
    }
    return true;
  }

  /** Deterministic SHA-256 hex over all findings. */
  public String hashHex() {
    StringBuilder sb = new StringBuilder();
    for (Finding f : findings) {
      sb.append(f.toString()).append('\n');
    }
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] d = md.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder();
      for (byte b : d) {
        hex.append(Character.forDigit((b >>> 4) & 0xF, 16));
        hex.append(Character.forDigit(b & 0xF, 16));
      }
      return hex.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    for (Finding f : findings) {
      sb.append(f).append('\n');
    }
    return sb.toString();
  }
}
