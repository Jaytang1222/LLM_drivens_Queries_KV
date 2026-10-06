package kart.rewrite;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Measured action outcomes of training queries, retrieved by feature similarity (RAG evidence). */
public final class ActionMemory {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private final JsonNode entries;

  public ActionMemory(JsonNode entries) {
    this.entries = entries;
  }

  public static ActionMemory load(String path) {
    if (path == null || path.isEmpty()) {
      return null;
    }
    try {
      return new ActionMemory(MAPPER.readTree(new File(path)));
    } catch (Exception e) {
      throw new IllegalStateException("bad KART_ACTION_MEMORY " + path, e);
    }
  }

  /** Stores the k nearest same-mode training queries (excluding {@code queryId}) as ctx.evidence. */
  public void attach(ObjectNode ctx, String queryId, int k) {
    List<Object[]> scored = new ArrayList<Object[]>();
    for (JsonNode e : entries) {
      if (e.path("query").asText().equals(ctx.path("query").asText()) && !e.path("query_id").asText().equals(queryId)) {
        scored.add(new Object[] {Double.valueOf(distance(ctx.path("features"), e.path("features"))), e});
      }
    }
    scored.sort(Comparator.comparingDouble(o -> ((Double) o[0]).doubleValue()));
    ArrayNode ev = ctx.putArray("evidence");
    for (int i = 0; i < Math.min(k, scored.size()); i++) {
      ObjectNode n = ((JsonNode) scored.get(i)[1]).deepCopy();
      n.put("distance", Math.round((Double) scored.get(i)[0] * 1000) / 1000.0);
      ev.add(n);
    }
  }

  static double distance(JsonNode a, JsonNode b) {
    double sum = 0;
    int n = 0;
    for (Iterator<String> it = a.fieldNames(); it.hasNext(); ) {
      String f = it.next();
      JsonNode x = a.get(f);
      JsonNode y = b.get(f);
      if (y == null) {
        continue;
      }
      if (x.isNumber() && y.isNumber()) {
        double d = Math.log1p(Math.abs(x.asDouble())) - Math.log1p(Math.abs(y.asDouble()));
        sum += d * d;
        n++;
      } else if (x.isTextual() && !x.asText().equals(y.asText())) {
        sum += 1.0;
      }
    }
    return n == 0 ? Double.POSITIVE_INFINITY : Math.sqrt(sum / n);
  }

  /** Nearest neighbour's measured-best program restricted to options available here, or null. */
  public static RewriteProtocol.Program knn(JsonNode ctx) {
    for (JsonNode e : ctx.path("evidence")) {
      Map<String, String> m = new TreeMap<String, String>(RewriteProtocol.defaults(ctx).choice);
      boolean ok = e.path("best").size() > 0;
      for (Iterator<Map.Entry<String, JsonNode>> it = e.path("best").fields(); it.hasNext() && ok; ) {
        Map.Entry<String, JsonNode> s = it.next();
        ok = RewriteProtocol.options(ctx, s.getKey()).contains(s.getValue().asText());
        m.put(s.getKey(), s.getValue().asText());
      }
      if (ok) {
        return new RewriteProtocol.Program(m, "knn");
      }
    }
    return null;
  }

  /** Every non-default option of {@code prog} was measured faster on some retrieved neighbour. */
  public static boolean supported(JsonNode ctx, Map<String, String> prog) {
    Map<String, String> def = RewriteProtocol.defaults(ctx).choice;
    boolean any = false;
    for (Map.Entry<String, String> s : prog.entrySet()) {
      if (s.getValue().equals(def.get(s.getKey()))) {
        continue;
      }
      any = true;
      boolean seen = false;
      for (JsonNode e : ctx.path("evidence")) {
        for (JsonNode m : e.path("measured")) {
          seen |= s.getValue().equals(m.path("program").path(s.getKey()).asText()) && m.path("delta_ms").asDouble() < 0;
        }
      }
      if (!seen) {
        return false;
      }
    }
    return any;
  }

  static String text(JsonNode ctx) {
    StringBuilder sb = new StringBuilder(
        "\nmeasured on similar past queries (exec delta ms, negative = faster; more reliable than the estimates):");
    int i = 1;
    for (JsonNode e : ctx.path("evidence")) {
      JsonNode f = e.path("features");
      sb.append("\n#").append(i++).append(' ').append(f.path("cbo_plan").asText());
      for (String k : new String[] {"time_span_s", "rect_area_km2", "k", "metric"}) {
        if (f.has(k)) {
          sb.append(' ').append(k).append('=').append(f.get(k).asText());
        }
      }
      sb.append(':');
      int j = 0;
      for (JsonNode m : e.path("measured")) {
        if (j++ == 4) {
          break;
        }
        List<String> parts = new ArrayList<String>();
        for (Iterator<Map.Entry<String, JsonNode>> it = m.path("program").fields(); it.hasNext(); ) {
          Map.Entry<String, JsonNode> s = it.next();
          parts.add(s.getKey() + "=" + s.getValue().asText());
        }
        long d = Math.round(m.path("delta_ms").asDouble());
        sb.append(' ').append(String.join(",", parts)).append(' ').append(d > 0 ? "+" : "").append(d).append(';');
      }
    }
    return sb.toString();
  }
}
