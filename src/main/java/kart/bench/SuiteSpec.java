package kart.bench;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import kart.KartPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** YAML suite definition for compare / ablation / param. */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class SuiteSpec {

  private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

  public String kind; // compare | ablation | param
  public String id;
  public String workload = "datasets/tdrive-query/simple_query.json";
  public String oracle = "datasets/tdrive-query/simple_query.json";
  public String manifest = "tdrive_v1_ready";
  public String catalog = "catalog";
  public boolean require_oracle = true;
  public int trials = 1;
  public Integer limit; // optional query limit for smoke subsets
  public List<String> stages = new ArrayList<String>();
  public List<String> arms = new ArrayList<String>();
  public String base_arm = "kart";
  public List<Factor> factors = new ArrayList<Factor>();
  /** Param experiment defaults pinned on every OAT cell (beam / candidates / llm). */
  public Map<String, Object> defaults = new LinkedHashMap<String, Object>();
  /** {@code oat} = one-at-a-time (formal param); {@code cartesian} = full factorial (smoke). */
  public String grid_mode;
  public Map<String, List<Object>> grid = new LinkedHashMap<String, List<Object>>();
  public boolean unsafe = false;

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static final class Factor {
    public String id;
    public boolean enabled = true;
    public boolean unsafe = false;
    public Map<String, Object> overrides = new LinkedHashMap<String, Object>();
  }

  /**
   * Load a suite. {@code path} may be:
   * <ul>
   *   <li>a suite id (e.g. {@code parse}) resolved from {@link KartPaths#SUITES_YAML}</li>
   *   <li>{@code catalog.yaml#id}</li>
   *   <li>a single-suite YAML file (legacy)</li>
   *   <li>a catalog YAML with {@code suites:} map (requires {@code suiteId} or {@code #id})</li>
   * </ul>
   */
  public static SuiteSpec load(Path root, Path pathOrId, String suiteId) throws IOException {
    String raw = pathOrId == null ? null : pathOrId.toString().replace('\\', '/').trim();
    if (raw == null || raw.isEmpty()) {
      throw new IOException("suite path/id required");
    }
    String idFromHash = null;
    int hash = raw.indexOf('#');
    if (hash >= 0) {
      idFromHash = raw.substring(hash + 1).trim();
      raw = raw.substring(0, hash).trim();
    }
    String id = suiteId != null && !suiteId.trim().isEmpty() ? suiteId.trim() : idFromHash;

    Path file;
    boolean bareId = !raw.contains("/") && !raw.contains("\\") && !raw.endsWith(".yaml")
        && !raw.endsWith(".yml");
    if (bareId) {
      id = raw;
      file = root.resolve(KartPaths.SUITES_YAML);
    } else {
      Path p = Paths.get(raw);
      file = p.isAbsolute() ? p : root.resolve(p);
    }
    if (!Files.isRegularFile(file)) {
      throw new IOException("suite catalog/file not found: " + file
          + (bareId ? " (id=" + id + ")" : ""));
    }

    JsonNode rootNode = YAML.readTree(file.toFile());
    if (rootNode.has("suites") && rootNode.get("suites").isObject()) {
      if (id == null || id.isEmpty()) {
        throw new IOException("suite catalog requires --suite-id or path#id: " + file);
      }
      JsonNode node = rootNode.get("suites").get(id);
      if (node == null || node.isNull()) {
        List<String> keys = new ArrayList<String>();
        Iterator<String> it = rootNode.get("suites").fieldNames();
        while (it.hasNext()) {
          keys.add(it.next());
        }
        throw new IOException("unknown suite id '" + id + "' in " + file + "; known=" + keys);
      }
      SuiteSpec s = YAML.treeToValue(node, SuiteSpec.class);
      return normalize(s, id, file);
    }
    SuiteSpec s = YAML.treeToValue(rootNode, SuiteSpec.class);
    return normalize(s, id != null ? id : null, file);
  }

  /** Legacy single-file load (project cwd as root). */
  public static SuiteSpec load(Path path) throws IOException {
    Path abs = path.toAbsolutePath().normalize();
    Path root = Paths.get(".").toAbsolutePath().normalize();
    String s = abs.toString().replace('\\', '/');
    int idx = s.indexOf("/src/main/resources/");
    if (idx >= 0) {
      root = Paths.get(s.substring(0, idx));
    }
    return load(root, path, null);
  }

  private static SuiteSpec normalize(SuiteSpec s, String preferredId, Path path) {
    if (s == null) {
      s = new SuiteSpec();
    }
    if (s.stages == null || s.stages.isEmpty()) {
      s.stages = new ArrayList<String>();
      s.stages.add("e2e");
    }
    if (preferredId != null && !preferredId.isEmpty()) {
      s.id = preferredId;
    } else if (s.id == null || s.id.trim().isEmpty()) {
      String name = path.getFileName().toString();
      int dot = name.lastIndexOf('.');
      s.id = dot > 0 ? name.substring(0, dot) : name;
    }
    if (s.kind == null || s.kind.trim().isEmpty()) {
      s.kind = s.id;
    }
    if (s.defaults == null) {
      s.defaults = new LinkedHashMap<String, Object>();
    }
    if (s.grid_mode == null || s.grid_mode.trim().isEmpty()) {
      if (s.kind != null && "param".equalsIgnoreCase(s.kind.trim())) {
        s.grid_mode = "oat";
      } else {
        s.grid_mode = "cartesian";
      }
    } else {
      s.grid_mode = s.grid_mode.trim().toLowerCase();
    }
    return s;
  }

  /** Resolved grid expansion mode for param suites. */
  public String resolvedGridMode() {
    if (grid_mode != null && !grid_mode.trim().isEmpty()) {
      return grid_mode.trim().toLowerCase();
    }
    if (kind != null && "param".equalsIgnoreCase(kind.trim())) {
      return "oat";
    }
    return "cartesian";
  }

  public List<String> enabledFactorIds() {
    if (factors == null) {
      return Collections.emptyList();
    }
    List<String> out = new ArrayList<String>();
    for (Factor f : factors) {
      if (f != null && f.enabled && f.id != null) {
        out.add(f.id);
      }
    }
    return out;
  }
}
