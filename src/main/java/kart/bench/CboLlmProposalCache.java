package kart.bench;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import kart.ir.BoundIr;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-local plan-id cache for {@link CboLlmProposalArm}.
 * Stores only reconstructible plan IDs — never query answers or safety proofs.
 * Key excludes {@code query_id}; semantic BoundIR fields + version stamps matter.
 */
public final class CboLlmProposalCache {

  public static final String KEY_VERSION = "cbo_llm_proposal_cache/v2";

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final CboLlmProposalCache SHARED = new CboLlmProposalCache();

  private final ConcurrentHashMap<String, String> planIdByKey = new ConcurrentHashMap<String, String>();

  public static CboLlmProposalCache shared() {
    return SHARED;
  }

  public void clear() {
    planIdByKey.clear();
  }

  public int size() {
    return planIdByKey.size();
  }

  public String get(String key) {
    return key == null ? null : planIdByKey.get(key);
  }

  public void put(String key, String planId) {
    if (key == null || planId == null || planId.isEmpty()) {
      return;
    }
    planIdByKey.put(key, planId);
  }

  public void remove(String key) {
    if (key != null) {
      planIdByKey.remove(key);
    }
  }

  /**
   * Canonical cache key: normalized BoundIR body (no query_id) + version stamps.
   */
  public static String keyFor(BoundIr ir, String manifestId, String semanticsVersion,
                              String layoutVersion, String costModelVersion) {
    try {
      ObjectNode root = MAPPER.createObjectNode();
      root.put("cache_key_version", KEY_VERSION);
      root.put("manifest_id", nullToEmpty(manifestId));
      root.put("semantics_version", nullToEmpty(semanticsVersion));
      root.put("layout_version", nullToEmpty(layoutVersion));
      root.put("cost_model_version", nullToEmpty(costModelVersion));
      root.put("plan_construct_version", "PlanBuilder.buildCandidatesWithMergeVariants/v1");
      if (ir != null) {
        ObjectNode body = MAPPER.valueToTree(ir);
        body.remove("query_id");
        root.set("bound_ir", body);
      }
      String canon = MAPPER.writeValueAsString(root);
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] dig = md.digest(canon.getBytes(StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder(KEY_VERSION);
      sb.append(':');
      for (byte b : dig) {
        sb.append(String.format("%02x", Integer.valueOf(b & 0xff)));
      }
      return sb.toString();
    } catch (Exception e) {
      return KEY_VERSION + ":error";
    }
  }

  private static String nullToEmpty(String s) {
    return s == null ? "" : s;
  }
}
