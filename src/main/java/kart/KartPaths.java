package kart;

import java.nio.file.Path;

/** Canonical on-disk paths relative to {@code kart.root}. */
public final class KartPaths {

  public static final String CONFIG_DIR = "src/main/resources/config";
  public static final String KART_YAML = CONFIG_DIR + "/kart.yaml";
  public static final String HBASE_SITE = CONFIG_DIR + "/hbase/hbase-site.xml";
  public static final String UNCALIBRATED_COEFFS = CONFIG_DIR + "/cost_coeffs_uncalibrated.yaml";
  public static final String SCHEMAS_DIR = "src/main/resources/schemas";
  public static final String SUITES_YAML = "src/main/resources/experiments/suites.yaml";

  private KartPaths() {}

  public static Path config(Path root, String relativeUnderConfig) {
    return root.resolve(CONFIG_DIR).resolve(relativeUnderConfig);
  }

  public static Path schemas(Path root) {
    return root.resolve(SCHEMAS_DIR);
  }

  public static Path hbaseSite(Path root) {
    return root.resolve(HBASE_SITE);
  }

  public static Path suitesCatalog(Path root) {
    return root.resolve(SUITES_YAML);
  }
}
