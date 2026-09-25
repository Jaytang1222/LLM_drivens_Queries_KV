package kart.bench;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** YAML suite definition for compare / ablation / param. */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class SuiteSpec {

  private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

  public String kind; // compare | ablation | param
  public String id;
  public String workload = "experiments/workloads/tdrive_smoke.json";
  public String oracle = "experiments/workloads/tdrive_smoke.oracle.json";
  public String manifest = "tdrive_v1_ready";
  public String catalog = "catalog";
  public boolean require_oracle = true;
  public int trials = 1;
  public Integer limit; // optional query limit for smoke subsets
  public List<String> stages = new ArrayList<String>();
  public List<String> arms = new ArrayList<String>();
  public String base_arm = "kart";
  public List<Factor> factors = new ArrayList<Factor>();
  public Map<String, List<Object>> grid = new LinkedHashMap<String, List<Object>>();
  public boolean unsafe = false;

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static final class Factor {
    public String id;
    public boolean enabled = true;
    public boolean unsafe = false;
    public Map<String, Object> overrides = new LinkedHashMap<String, Object>();
  }

  public static SuiteSpec load(Path path) throws IOException {
    if (!Files.isRegularFile(path)) {
      throw new IOException("suite not found: " + path);
    }
    SuiteSpec s = YAML.readValue(path.toFile(), SuiteSpec.class);
    if (s.stages == null || s.stages.isEmpty()) {
      s.stages = new ArrayList<String>();
      s.stages.add("e2e");
    }
    if (s.id == null || s.id.trim().isEmpty()) {
      String name = path.getFileName().toString();
      int dot = name.lastIndexOf('.');
      s.id = dot > 0 ? name.substring(0, dot) : name;
    }
    if (s.kind == null || s.kind.trim().isEmpty()) {
      s.kind = s.id;
    }
    return s;
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
