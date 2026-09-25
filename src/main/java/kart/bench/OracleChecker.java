package kart.bench;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kart.exec.QueryResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Oracle answer load + compare (logic aligned with {@code SmokeTdriveCmd}).
 */
public final class OracleChecker {

  private static final double DIST_EPS = 1e-3;
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private OracleChecker() {}

  public static final class Answer {
    public String queryId;
    public List<String> trajectoryIds = new ArrayList<String>();
    public List<Scored> topK;
  }

  public static final class Scored {
    public long tid;
    public String trajectoryId;
    public double distance;
  }

  public static Map<String, Answer> load(Path oraclePath) throws IOException {
    JsonNode root = MAPPER.readTree(Files.readAllBytes(oraclePath));
    Map<String, Answer> out = new LinkedHashMap<String, Answer>();
    if (root == null || root.get("answers") == null) {
      return out;
    }
    for (JsonNode a : root.get("answers")) {
      Answer oa = new Answer();
      oa.queryId = a.get("query_id").asText();
      for (JsonNode id : a.get("trajectory_ids")) {
        oa.trajectoryIds.add(id.asText());
      }
      if (a.has("top_k") && a.get("top_k").isArray()) {
        oa.topK = new ArrayList<Scored>();
        for (JsonNode s : a.get("top_k")) {
          Scored sc = new Scored();
          sc.tid = s.get("tid").asLong();
          sc.trajectoryId = s.get("trajectory_id").asText();
          sc.distance = s.get("distance").asDouble();
          oa.topK.add(sc);
        }
      }
      out.put(oa.queryId, oa);
    }
    return out;
  }

  /**
   * @return null if match; else mismatch message
   */
  public static String compare(Answer expected, QueryResult actual) {
    if (expected == null) {
      return "missing oracle answer";
    }
    if (actual == null) {
      return "null QueryResult";
    }
    if ("PLAN_ONLY".equals(actual.status)) {
      return null;
    }
    if (!"OK".equals(actual.status)) {
      return "status=" + actual.status + " error=" + actual.error;
    }
    if (actual.trajectoryIds == null) {
      return "null trajectoryIds";
    }
    if (expected.trajectoryIds.size() != actual.trajectoryIds.size()) {
      return "size mismatch oracle=" + expected.trajectoryIds.size()
          + " actual=" + actual.trajectoryIds.size();
    }
    for (int i = 0; i < expected.trajectoryIds.size(); i++) {
      if (!expected.trajectoryIds.get(i).equals(actual.trajectoryIds.get(i))) {
        return "id mismatch at " + i + " oracle=" + expected.trajectoryIds.get(i)
            + " actual=" + actual.trajectoryIds.get(i);
      }
    }
    if (expected.topK != null) {
      List<QueryResult.Scored> actualTop =
          actual.topK != null ? actual.topK : java.util.Collections.<QueryResult.Scored>emptyList();
      if (expected.topK.size() != actualTop.size()) {
        return "topK size mismatch oracle=" + expected.topK.size()
            + " actual=" + actualTop.size();
      }
      for (int i = 0; i < expected.topK.size(); i++) {
        Scored e = expected.topK.get(i);
        QueryResult.Scored a = actualTop.get(i);
        if (!e.trajectoryId.equals(a.trajectoryId)) {
          return "topK id mismatch at " + i;
        }
        if (Math.abs(e.distance - a.distance) > DIST_EPS
            && Math.abs(e.distance - a.distance)
            > DIST_EPS * Math.max(1.0, Math.abs(e.distance))) {
          return "topK distance mismatch at " + i + " oracle=" + e.distance
              + " actual=" + a.distance;
        }
      }
    }
    return null;
  }
}
