package kart.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;

/**
 * Loads YAML configs with defaults and validation (T0.3).
 */
public final class AppConfig {

  private static final Logger LOG = LoggerFactory.getLogger(AppConfig.class);
  private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

  private final Path root;
  private final EnvironmentConfig environment;
  private final LayoutConfig layout;
  private final PlannerConfig planner;
  private final RegionsConfig regions;

  private AppConfig(Path root, EnvironmentConfig environment, LayoutConfig layout,
                    PlannerConfig planner, RegionsConfig regions) {
    this.root = root;
    this.environment = environment;
    this.layout = layout;
    this.planner = planner;
    this.regions = regions;
  }

  public Path root() {
    return root;
  }

  public EnvironmentConfig environment() {
    return environment;
  }

  public LayoutConfig layout() {
    return layout;
  }

  public PlannerConfig planner() {
    return planner;
  }

  public RegionsConfig regions() {
    return regions;
  }

  public static AppConfig load(Path root) throws IOException {
    Path envPath = root.resolve("config/environment.yaml");
    if (!Files.isRegularFile(envPath)) {
      envPath = root.resolve("config/environment.example.yaml");
      LOG.warn("config/environment.yaml missing; using {}", envPath);
    }
    EnvironmentConfig env = readYaml(envPath, EnvironmentConfig.class, new EnvironmentConfig());

    Path layoutPath = resolve(root, env.layout_file, "config/layout.yaml");
    LayoutConfig layout = readYaml(layoutPath, LayoutConfig.class, LayoutConfig.defaults());
    layout.validate();

    Path plannerPath = resolve(root, env.planner_file, "config/planner.yaml");
    PlannerConfig planner = readYaml(plannerPath, PlannerConfig.class, PlannerConfig.defaults());

    Path regionsPath = resolve(root, env.regions_file, "config/regions.yaml");
    RegionsConfig regions = readYaml(regionsPath, RegionsConfig.class, new RegionsConfig());

    return new AppConfig(root, env, layout, planner, regions);
  }

  private static Path resolve(Path root, String configured, String fallback) {
    String rel = configured == null || configured.trim().isEmpty() ? fallback : configured;
    Path p = Paths.get(rel);
    return p.isAbsolute() ? p : root.resolve(rel);
  }

  private static <T> T readYaml(Path path, Class<T> type, T defaults) throws IOException {
    if (!Files.isRegularFile(path)) {
      LOG.warn("Missing config {}; using defaults", path);
      return defaults;
    }
    return YAML.readValue(path.toFile(), type);
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class EnvironmentConfig {
    public HBaseBlock hbase = new HBaseBlock();
    public CatalogBlock catalog = new CatalogBlock();
    public String layout_file = "config/layout.yaml";
    public String planner_file = "config/planner.yaml";
    public String regions_file = "config/regions.yaml";
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class HBaseBlock {
    public String site_xml = "config/hbase/hbase-site.xml";
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class CatalogBlock {
    public String dir = "catalog";
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class LayoutConfig {
    public int shard_count = 4;
    public int chunk_max_points = 256;
    public long bucket_ms = 600_000L;
    public int zorder_level = 8;
    public String hash_version = "murmur3_x64_128";
    public String crs = "EPSG:32650";
    public Domain domain = new Domain();
    public String table_suffix = "_v1";

    public static LayoutConfig defaults() {
      return new LayoutConfig();
    }

    public void validate() {
      if (shard_count <= 0) {
        throw new IllegalArgumentException("layout.shard_count must be > 0, got " + shard_count);
      }
      if (chunk_max_points <= 0) {
        throw new IllegalArgumentException("layout.chunk_max_points must be > 0");
      }
      if (bucket_ms <= 0) {
        throw new IllegalArgumentException("layout.bucket_ms must be > 0");
      }
      if (zorder_level <= 0 || zorder_level > 16) {
        throw new IllegalArgumentException("layout.zorder_level must be in 1..16, got " + zorder_level);
      }
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class Domain {
    public double xmin;
    public double xmax;
    public double ymin;
    public double ymax;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class PlannerConfig {
    public int max_llm_calls = 6;
    public int max_candidates = 8;
    public long max_plan_ms = 5000;
    public int beam_width = 3;
    public int stagnation_steps = 2;
    public long max_candidate_chunks = 200_000L;
    public long max_dtw_cells = 500_000_000L;
    public int max_zorder_ranges = 64;
    public int fetch_batch_size = 500;
    public CostCoeffs cost = new CostCoeffs();

    public static PlannerConfig defaults() {
      return new PlannerConfig();
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class CostCoeffs {
    public boolean calibrated = false;
    public String model_version = "cost_v2_rs_sched";
    public int concurrency_index = 4;
    public int concurrency_get = 4;
    public int concurrency_per_rs = 2;
    // §13.4 alphas / betas / …
    public double alpha_rpc = 1.0;
    public double alpha_seek = 0.5;
    public double alpha_byte = 0.000001;
    public double alpha_decode = 0.0001;
    public double beta_hash = 0.00001;
    public double beta_emit = 0.00001;
    public double beta_spill = 0.00000001;
    /** Extra set-cost multiplier for SORT_MERGE vs HASH_SET (log factor applied in model). */
    public double beta_sort_merge = 0.00002;
    public double gamma_rpc = 0.05;
    public double gamma_byte = 0.000001;
    public double gamma_decode = 0.0001;
    public double delta_point = 0.0001;
    public double delta_geometry = 0.00005;
    public double rho_linear = 0.00005;
    public double eta_cell = 0.00001;
    /** Metric-specific cell costs (fallback to eta_cell when unset / 0). */
    public double eta_cell_dtw = 0.0;
    public double eta_cell_frechet = 0.0;
    public double eta_cell_hausdorff = 0.0;
    public double theta_heap = 0.001;
    /** Soft heap budget for spill estimate / executor retained-bytes cap (bytes). */
    public long soft_memory_bytes = 512L * 1024L * 1024L;
    /** Wall-clock execution timeout (ms); 0 = use ExecLimits default. */
    public long max_exec_ms = 120_000L;
    // Legacy linear MVP coeffs (still accepted in YAML; mapped if §13.4 unset).
    public double c_scan = 1.0;
    public double c_row = 0.01;
    public double c_get = 0.05;
    public double c_byte = 0.000001;
    public double c_point = 0.0001;
    public double c_dtw = 0.00001;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class RegionsConfig {
    public List<Region> regions = Collections.emptyList();
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class Region {
    public String name;
    public double min_lon;
    public double min_lat;
    public double max_lon;
    public double max_lat;
    /**
     * When true, lon/lat fields are already local meters (fixture domains), not WGS84.
     * Production regions leave this false and are projected EPSG:4326→UTM.
     */
    public boolean local_meters;
  }
}
