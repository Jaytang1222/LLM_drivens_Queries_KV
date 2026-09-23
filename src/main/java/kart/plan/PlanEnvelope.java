package kart.plan;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Logical plan envelope (schemas/plan.schema.json).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class PlanEnvelope {

  public String plan_id;
  public String query_id;
  public String manifest_id;
  public String root;
  public List<PlanNode> nodes = new ArrayList<PlanNode>();

  private static final ObjectMapper MAPPER = new ObjectMapper()
      .enable(SerializationFeature.INDENT_OUTPUT)
      .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

  public String toJson() throws IOException {
    return MAPPER.writeValueAsString(this);
  }

  public static PlanEnvelope fromJson(String json) throws IOException {
    return MAPPER.readValue(json, PlanEnvelope.class);
  }

  public PlanNode node(String id) {
    for (PlanNode n : nodes) {
      if (n.id.equals(id)) {
        return n;
      }
    }
    return null;
  }

  /**
   * Structural signature for isomorphism testing: independent of node ids,
   * plan_id and query_id. Commutative ops (INTERSECT/UNION) sort child
   * signatures; params are canonicalized with sorted keys.
   */
  public String signature() {
    Map<String, PlanNode> byId = new HashMap<String, PlanNode>();
    for (PlanNode n : nodes) {
      byId.put(n.id, n);
    }
    Map<String, String> memo = new HashMap<String, String>();
    return sigOf(root, byId, memo, 0);
  }

  private static String sigOf(String id, Map<String, PlanNode> byId,
                              Map<String, String> memo, int depth) {
    if (depth > 10_000) {
      throw new IllegalStateException("plan graph too deep or cyclic");
    }
    String cached = memo.get(id);
    if (cached != null) {
      return cached;
    }
    PlanNode n = byId.get(id);
    if (n == null) {
      return "<missing:" + id + ">";
    }
    List<String> childSigs = new ArrayList<String>();
    for (String in : n.inputs) {
      childSigs.add(sigOf(in, byId, memo, depth + 1));
    }
    if (n.op != null && n.op.isCommutative()) {
      Collections.sort(childSigs);
    }
    StringBuilder sb = new StringBuilder();
    sb.append(n.op == null ? "?" : n.op.name()).append('(');
    for (int i = 0; i < childSigs.size(); i++) {
      if (i > 0) {
        sb.append(',');
      }
      sb.append(childSigs.get(i));
    }
    sb.append(';').append(canonicalParams(n.params)).append(')');
    String sig = sb.toString();
    memo.put(id, sig);
    return sig;
  }

  private static String canonicalParams(Map<String, Object> params) {
    if (params == null || params.isEmpty()) {
      return "{}";
    }
    try {
      return MAPPER.copy()
          .disable(SerializationFeature.INDENT_OUTPUT)
          .writeValueAsString(new TreeMap<String, Object>(params));
    } catch (IOException e) {
      throw new IllegalStateException("params not serializable", e);
    }
  }
}
