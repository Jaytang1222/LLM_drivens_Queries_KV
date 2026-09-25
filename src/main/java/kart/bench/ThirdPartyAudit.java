package kart.bench;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Frozen third-party checkout + adapter SHA256 for meta.json (audit P1).
 */
public final class ThirdPartyAudit {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private ThirdPartyAudit() {}

  public static Map<String, Object> collect(Path root) {
    Map<String, Object> out = new LinkedHashMap<String, Object>();
    out.put("transplant", Boolean.TRUE);
    out.put("transplant_doc", "experiments/adapters/TRANSPLANT.md");
    out.put("claim", "control_flow_transplant_not_upstream_reproduction");

    Path commits = root.resolve("experiments/third_party/commits.json");
    if (Files.isRegularFile(commits)) {
      try {
        out.put("commits", MAPPER.readValue(commits.toFile(), Object.class));
      } catch (Exception e) {
        out.put("commits_error", e.getMessage());
      }
    } else {
      out.put("commits", null);
      out.put("commits_missing", Boolean.TRUE);
    }

    Map<String, Object> entries = new LinkedHashMap<String, Object>();
    putEntry(entries, root, "din_sql",
        "experiments/third_party/din-sql/DIN-SQL.py",
        "experiments/third_party/din-sql/DIN-SQL_BIRD.py");
    putEntry(entries, root, "text_to_nosql",
        "experiments/third_party/text-to-nosql/src/tend/solver/sag/runtime.py");
    putEntry(entries, root, "bao",
        "experiments/third_party/bao/bao_server/main.py");
    putEntry(entries, root, "llmopt",
        "experiments/third_party/llmopt/README.md");
    out.put("upstream_entries", entries);

    Map<String, Object> adapters = new LinkedHashMap<String, Object>();
    hashFile(adapters, root, "din_bridge", "experiments/adapters/din/din_bridge.py");
    hashFile(adapters, root, "sag_bridge", "experiments/adapters/sag/sag_bridge.py");
    hashFile(adapters, root, "llmopt_bridge", "experiments/adapters/llmopt/llmopt_bridge.py");
    hashFile(adapters, root, "llm_client", "experiments/adapters/llm_client.py");
    hashFile(adapters, root, "bao_weights", "experiments/adapters/bao/kart_plan_family_weights.json");
    hashFile(adapters, root, "transplant_md", "experiments/adapters/TRANSPLANT.md");
    hashFile(adapters, root, "bird_knowledge", "experiments/adapters/din/bird_knowledge.txt");
    out.put("adapter_sha256", adapters);

    List<String> missing = new ArrayList<String>();
    for (Map.Entry<String, Object> e : entries.entrySet()) {
      @SuppressWarnings("unchecked")
      Map<String, Object> rec = (Map<String, Object>) e.getValue();
      if (!Boolean.TRUE.equals(rec.get("all_present"))) {
        missing.add(e.getKey());
      }
    }
    out.put("missing_upstreams", missing);
    out.put("ready_for_external_arms", Boolean.valueOf(missing.isEmpty()));
    return out;
  }

  /**
   * Fail fast when a selected external arm's upstream entry file is absent.
   * Native arms (fullscan/rbo/cbo/kart) do not require third_party checkouts.
   */
  public static void requireEntriesForArms(Path root, Iterable<String> armIds) {
    if (armIds == null) {
      return;
    }
    Map<String, Object> audit = collect(root);
    @SuppressWarnings("unchecked")
    Map<String, Object> entries = (Map<String, Object>) audit.get("upstream_entries");
    if (entries == null) {
      entries = new LinkedHashMap<String, Object>();
    }
    for (String arm : armIds) {
      if (arm == null) {
        continue;
      }
      String key = upstreamKey(arm);
      if (key == null) {
        continue;
      }
      Object recObj = entries.get(key);
      boolean present = false;
      if (recObj instanceof Map) {
        present = Boolean.TRUE.equals(((Map<?, ?>) recObj).get("all_present"));
      }
      if (!present) {
        throw new IllegalStateException("upstream entry files missing for arm '"
            + arm + "' (" + key + "). Run: bash experiments/adapters/clone-third-party.sh "
            + "&& bash experiments/adapters/clone-third-party.sh --verify");
      }
    }
  }

  static String upstreamKey(String armId) {
    String a = armId.trim().toLowerCase();
    if ("din-spider".equals(a) || "din-bird".equals(a)) {
      return "din_sql";
    }
    if ("sag".equals(a)) {
      return "text_to_nosql";
    }
    if ("bao".equals(a)) {
      return "bao";
    }
    if ("llmopt".equals(a)) {
      return "llmopt";
    }
    return null;
  }

  private static void putEntry(Map<String, Object> dest, Path root, String key, String... rels) {
    Map<String, Object> rec = new LinkedHashMap<String, Object>();
    List<Map<String, Object>> files = new ArrayList<Map<String, Object>>();
    boolean all = true;
    for (String rel : rels) {
      Map<String, Object> f = new LinkedHashMap<String, Object>();
      f.put("path", rel);
      Path p = root.resolve(rel);
      boolean present = Files.isRegularFile(p);
      f.put("present", Boolean.valueOf(present));
      if (present) {
        f.put("sha256", sha256(p));
      } else {
        all = false;
      }
      files.add(f);
    }
    rec.put("files", files);
    rec.put("all_present", Boolean.valueOf(all));
    dest.put(key, rec);
  }

  private static void hashFile(Map<String, Object> dest, Path root, String key, String rel) {
    Path p = root.resolve(rel);
    Map<String, Object> rec = new LinkedHashMap<String, Object>();
    rec.put("path", rel);
    if (Files.isRegularFile(p)) {
      rec.put("sha256", sha256(p));
    } else {
      rec.put("missing", Boolean.TRUE);
    }
    dest.put(key, rec);
  }

  static String sha256(Path file) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] dig = md.digest(Files.readAllBytes(file));
      StringBuilder sb = new StringBuilder();
      for (byte b : dig) {
        sb.append(String.format("%02x", Byte.valueOf(b)));
      }
      return sb.toString();
    } catch (Exception e) {
      return null;
    }
  }

  static Map<String, Map<String, Object>> loadCatalog(Path workloadPath) {
    Map<String, Map<String, Object>> out = new LinkedHashMap<String, Map<String, Object>>();
    if (workloadPath == null || !Files.isRegularFile(workloadPath)) {
      return out;
    }
    try {
      JsonNode root = MAPPER.readTree(workloadPath.toFile());
      JsonNode cat = root.get("catalog");
      if (cat == null || !cat.isObject()) {
        return out;
      }
      java.util.Iterator<String> it = cat.fieldNames();
      while (it.hasNext()) {
        String id = it.next();
        JsonNode node = cat.get(id);
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        if (node.has("family")) {
          row.put("family", node.get("family").asText());
        }
        if (node.has("selectivity")) {
          row.put("selectivity", node.get("selectivity").asText());
        }
        if (node.has("rbo_trap")) {
          row.put("rbo_trap", Boolean.valueOf(node.get("rbo_trap").asBoolean()));
        }
        if (node.has("notes")) {
          row.put("layer_notes", node.get("notes").asText());
        }
        out.put(id, row);
      }
    } catch (Exception ignore) {
      // catalog is optional metadata
    }
    return out;
  }
}
