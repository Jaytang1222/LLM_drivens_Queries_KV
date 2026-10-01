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
  private final List<CoverageCertificate> certificates = new ArrayList<CoverageCertificate>();
  /** Optional refine_3 validator sub-stage timings. */
  public ValidatorTiming timing;

  public void pass(String check, String message) {
    findings.add(new Finding(check, true, message));
  }

  public void fail(String check, String message) {
    findings.add(new Finding(check, false, message));
  }

  public void addCertificate(CoverageCertificate cert) {
    if (cert != null) {
      certificates.add(cert);
    }
  }

  public List<CoverageCertificate> certificates() {
    return certificates;
  }

  /** Jackson-friendly alias for NFR-3 artifact serialization. */
  public List<CoverageCertificate> getCertificates() {
    return certificates;
  }

  public List<Finding> findings() {
    return findings;
  }

  /** Jackson-friendly alias for NFR-3 artifact serialization. */
  public List<Finding> getFindings() {
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
