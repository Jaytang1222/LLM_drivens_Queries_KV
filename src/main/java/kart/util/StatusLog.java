package kart.util;

/**
 * Runtime progress lines for operator debugging. Default on; disable with
 * {@code -Dkart.status=false} or {@code KART_STATUS=false}.
 */
public final class StatusLog {

  private StatusLog() {
  }

  public static boolean enabled() {
    String prop = System.getProperty("kart.status");
    if (prop != null && !prop.trim().isEmpty()) {
      return !"false".equalsIgnoreCase(prop.trim()) && !"0".equals(prop.trim());
    }
    String env = System.getenv("KART_STATUS");
    if (env != null && !env.trim().isEmpty()) {
      return !"false".equalsIgnoreCase(env.trim()) && !"0".equals(env.trim());
    }
    return true;
  }

  public static void info(String phase, String message) {
    if (!enabled()) {
      return;
    }
    System.err.println("[STATUS] " + phase + " | " + message);
  }
}
