package kart.bench;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Aggregate trials: oracle pass rate; latency P50/P95 on oracle-OK rows only (when required).
 */
public final class SummaryAggregator {

  private static final ObjectMapper MAPPER = new ObjectMapper()
      .enable(SerializationFeature.INDENT_OUTPUT);

  private SummaryAggregator() {}

  public static Map<String, Object> aggregate(List<Map<String, Object>> trials,
                                              boolean requireOracle) {
    Map<String, List<Map<String, Object>>> byKey =
        new LinkedHashMap<String, List<Map<String, Object>>>();
    for (Map<String, Object> t : trials) {
      String key = String.valueOf(t.get("arm_or_factor")) + "|" + String.valueOf(t.get("stage"));
      List<Map<String, Object>> list = byKey.get(key);
      if (list == null) {
        list = new ArrayList<Map<String, Object>>();
        byKey.put(key, list);
      }
      list.add(t);
    }

    List<Map<String, Object>> groups = new ArrayList<Map<String, Object>>();
    for (Map.Entry<String, List<Map<String, Object>>> e : byKey.entrySet()) {
      List<Map<String, Object>> rows = e.getValue();
      int n = rows.size();
      int oracleOk = 0;
      List<Long> e2eOk = new ArrayList<Long>();
      List<Long> planOk = new ArrayList<Long>();
      List<Long> execOk = new ArrayList<Long>();
      for (Map<String, Object> r : rows) {
        boolean ok = Boolean.TRUE.equals(r.get("ok_oracle"));
        if (ok) {
          oracleOk++;
          addLong(e2eOk, r.get("t_e2e_ms"));
          addLong(planOk, r.get("t_plan_ms"));
          addLong(execOk, r.get("t_exec_ms"));
        } else if (!requireOracle) {
          addLong(e2eOk, r.get("t_e2e_ms"));
          addLong(planOk, r.get("t_plan_ms"));
          addLong(execOk, r.get("t_exec_ms"));
        }
      }
      Map<String, Object> g = new LinkedHashMap<String, Object>();
      String[] parts = e.getKey().split("\\|", 2);
      g.put("arm_or_factor", parts[0]);
      g.put("stage", parts.length > 1 ? parts[1] : null);
      g.put("n_trials", Integer.valueOf(n));
      g.put("oracle_ok", Integer.valueOf(oracleOk));
      g.put("oracle_pass_rate", n == 0 ? 0.0 : (oracleOk * 1.0 / n));
      g.put("t_e2e_ms_p50", percentile(e2eOk, 0.50));
      g.put("t_e2e_ms_p95", percentile(e2eOk, 0.95));
      g.put("t_plan_ms_p50", percentile(planOk, 0.50));
      g.put("t_plan_ms_p95", percentile(planOk, 0.95));
      g.put("t_exec_ms_p50", percentile(execOk, 0.50));
      g.put("t_exec_ms_p95", percentile(execOk, 0.95));
      g.put("latency_n", Integer.valueOf(e2eOk.size()));
      groups.add(g);
    }

    Map<String, Object> summary = new LinkedHashMap<String, Object>();
    summary.put("require_oracle", Boolean.valueOf(requireOracle));
    summary.put("groups", groups);
    summary.put("total_trials", Integer.valueOf(trials.size()));
    return summary;
  }

  public static void write(Path path, Map<String, Object> summary) throws IOException {
    Files.createDirectories(path.getParent());
    Files.write(path, MAPPER.writeValueAsString(summary).getBytes(StandardCharsets.UTF_8));
  }

  private static void addLong(List<Long> xs, Object v) {
    if (v instanceof Number) {
      xs.add(Long.valueOf(((Number) v).longValue()));
    }
  }

  private static Long percentile(List<Long> values, double p) {
    if (values == null || values.isEmpty()) {
      return null;
    }
    List<Long> copy = new ArrayList<Long>(values);
    Collections.sort(copy);
    int idx = (int) Math.ceil(p * copy.size()) - 1;
    if (idx < 0) {
      idx = 0;
    }
    if (idx >= copy.size()) {
      idx = copy.size() - 1;
    }
    return copy.get(idx);
  }
}
