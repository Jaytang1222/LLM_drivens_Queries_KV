package kart.bench;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Compact summary.md — main metrics only. */
public final class SummaryMd {

  private SummaryMd() {}

  public static void write(Path resultDir, String runId, List<Map<String, Object>> trials,
                           SuiteSpec suite) throws Exception {
    write(resultDir, runId, trials, suite, null);
  }

  public static void write(Path resultDir, String runId, List<Map<String, Object>> trials,
                           SuiteSpec suite, CacheProtocol cache) throws Exception {
    write(resultDir, runId, trials, suite, cache, suite == null ? 0 : suite.trials);
  }

  public static void write(Path resultDir, String runId, List<Map<String, Object>> trials,
                           SuiteSpec suite, CacheProtocol cache, int trialCount) throws Exception {
    StringBuilder sb = new StringBuilder();
    sb.append("# ").append(runId).append(" / ").append(suite.id).append('\n').append('\n');
    if (suite != null && suite.kind != null && "ablation".equalsIgnoreCase(suite.kind.trim())) {
      sb.append("Ablation is **leave-one-out vs Full KART** (`spec/ablation_experiment.md`). ")
          .append("External comparative arms are not in this table. ")
          .append("`no_coverage` (if present) is a **risk control**, not a deployable baseline.\n\n");
      int uniqueQ = uniqueQueryCount(trials);
      sb.append("- bound queries in this summary: `").append(uniqueQ).append("`");
      sb.append(" (formal BoundIR count is `").append(SuiteRunner.FORMAL_BOUND_IR_COUNT).append("`)\n");
      if (uniqueQ < SuiteRunner.FORMAL_BOUND_IR_COUNT) {
        sb.append("- warning: incomplete BoundIR workload; do not treat this as the formal ablation table\n");
      }
      sb.append('\n');
    } else {
      sb.append("External DIN/SAG/Bao/LLMOpt arms are **control-flow transplants** ")
          .append("(DraftIR / KART plan family), not upstream SQL/Mongo/PostgreSQL reproductions. ")
          .append("See `experiments/adapters/TRANSPLANT.md`.\n\n");
    }
    sb.append("Conclusions are limited to this **single-node mixed HBase classpath** environment.\n\n");
    if (cache != null) {
      sb.append("- cache mode: `").append(cache.mode).append("`\n");
      sb.append("- cache protocol: `").append(cache.protocolLabel()).append("`\n");
      sb.append("- cache_enforced: `").append(cache.cacheEnforced()).append("`\n");
      sb.append("- warmup_passes: `").append(cache.warmupPasses).append("`\n");
      sb.append("- trials: `").append(trialCount).append("`\n");
      String warn = cache.formalWarning(trialCount);
      if (warn != null) {
        sb.append("- warning: ").append(warn).append('\n');
      }
      sb.append('\n');
      if (!cache.cacheEnforced() && "cold".equals(cache.mode)) {
        sb.append("> Latency percentiles below are **not** proven OS-cold. ")
            .append("Use `--cache cold --trials 1` for first-run data, or ")
            .append("`--cache warm --trials 5` for in-process warm samples.\n\n");
      }
    }

    Map<String, List<Map<String, Object>>> byStage = new LinkedHashMap<String, List<Map<String, Object>>>();
    for (Map<String, Object> t : trials) {
      String st = String.valueOf(t.get("stage"));
      List<Map<String, Object>> list = byStage.get(st);
      if (list == null) {
        list = new ArrayList<Map<String, Object>>();
        byStage.put(st, list);
      }
      list.add(t);
    }

    for (Map.Entry<String, List<Map<String, Object>>> e : byStage.entrySet()) {
      String stage = e.getKey();
      sb.append("## ").append(stage).append('\n').append('\n');
      Map<String, List<Map<String, Object>>> byArm = groupByArm(e.getValue());
      if ("parse".equalsIgnoreCase(stage)) {
        sb.append("| arm | n | supported_EX | reject_ok | clarify_ok | early_reject | infra_fail | t_parse_p50 | t_model_p50 |\n");
        sb.append("|---|---:|---:|---:|---:|---:|---:|---:|---:|\n");
      } else if ("plan".equalsIgnoreCase(stage)) {
        sb.append("| arm | n | plan_ok | fail_rate | t_plan_p50 | t_plan_p95 |\n");
        sb.append("|---|---:|---:|---:|---:|---:|\n");
      } else {
        sb.append("| arm | n | oracle_ok | fail_rate | t_e2e_p50 | t_e2e_p95 | t_plan_p50 | t_exec_p50 |\n");
        sb.append("|---|---:|---:|---:|---:|---:|---:|---:|\n");
      }
      for (Map.Entry<String, List<Map<String, Object>>> a : byArm.entrySet()) {
        List<Map<String, Object>> rows = a.getValue();
        int n = rows.size();
        if ("parse".equalsIgnoreCase(stage)) {
          int exOk = 0;
          int exN = 0;
          int rejOk = 0;
          int rejN = 0;
          int clOk = 0;
          int clN = 0;
          int earlyRej = 0;
          int infraFail = 0;
          List<Long> parseTot = new ArrayList<Long>();
          List<Long> parseModel = new ArrayList<Long>();
          for (Map<String, Object> r : rows) {
            if (Boolean.TRUE.equals(r.get("early_reject"))) {
              earlyRej++;
            }
            if (Boolean.TRUE.equals(r.get("infrastructure_failure"))) {
              infraFail++;
            }
            String qc = String.valueOf(r.get("query_class"));
            boolean pass = Boolean.TRUE.equals(r.get("ok_ex"));
            if ("reject".equals(qc)) {
              rejN++;
              if (pass) {
                rejOk++;
              }
            } else if ("clarify".equals(qc)) {
              clN++;
              if (pass) {
                clOk++;
              }
            } else {
              exN++;
              if (pass) {
                exOk++;
                addLong(parseTot, first(r, "t_parse_total_ms", "t_parse_ms"));
                addLong(parseModel, r.get("t_parse_model_ms"));
              }
            }
          }
          sb.append("| ").append(a.getKey())
              .append(" | ").append(n)
              .append(" | ").append(exOk).append("/").append(exN)
              .append(" | ").append(rejOk).append("/").append(rejN)
              .append(" | ").append(clOk).append("/").append(clN)
              .append(" | ").append(earlyRej)
              .append(" | ").append(infraFail)
              .append(" | ").append(fmt(percentile(parseTot, 0.50)))
              .append(" | ").append(fmt(percentile(parseModel, 0.50)))
              .append(" |\n");
          continue;
        }
        int ok = 0;
        List<Long> primary = new ArrayList<Long>();
        List<Long> plan = new ArrayList<Long>();
        List<Long> exec = new ArrayList<Long>();
        String latKey = latencyKey(stage);
        for (Map<String, Object> r : rows) {
          if (isPass(r, stage)) {
            ok++;
            addLong(primary, r.get(latKey));
            addLong(plan, r.get("t_plan_ms"));
            addLong(exec, r.get("t_exec_ms"));
          }
        }
        double failRate = n == 0 ? 0.0 : (n - ok) * 1.0 / n;
        sb.append("| ").append(a.getKey())
            .append(" | ").append(n)
            .append(" | ").append(ok).append("/").append(n)
            .append(" | ").append(String.format(Locale.ROOT, "%.2f", failRate))
            .append(" | ").append(fmt(percentile(primary, 0.50)))
            .append(" | ").append(fmt(percentile(primary, 0.95)));
        if ("e2e".equalsIgnoreCase(stage)) {
          sb.append(" | ").append(fmt(percentile(plan, 0.50)))
              .append(" | ").append(fmt(percentile(exec, 0.50)));
        }
        sb.append(" |\n");
      }
      sb.append('\n');
      if ("parse".equalsIgnoreCase(stage)) {
        sb.append("E1 notes: `early_reject` counts shared-gate hits (not method credit); ")
            .append("`infra_fail` counts HTTP/quota/service failures (e.g. 402) and must not be ")
            .append("read as model DraftIR failure. Gold reject/clarify labels score only after ")
            .append("label-blind inference.\n\n");
      }
      if ("plan".equalsIgnoreCase(stage)) {
        sb.append("E2 notes: rows with `llm_fallback=true` are a separate arm ")
            .append("(`kart-rule-fallback`, `kart-conditional-llm-rule-fallback`, ")
            .append("`pool-llm-rule-fallback`, `pool-conditional-llm-rule-fallback`, or ")
            .append("`*-rule-fallback`). They are not merged into the pure requested arm. ")
            .append("`bao` is a **Bao control-flow surrogate** ")
            .append("(KART CostCard + hand-set family weights), not a trained Bao model. ")
            .append("Shared-pool arms (`pool-*`) report `plan_regret_scope=shared_pool` and are ")
            .append("not comparable to native-search `plan_regret_ms`.\n\n");
      }
      if (!"parse".equalsIgnoreCase(stage)) {
        appendFailTaxonomy(sb, e.getValue());
      }
      if ("e2e".equalsIgnoreCase(stage) || "plan".equalsIgnoreCase(stage)) {
        appendKeyedBreakdown(sb, e.getValue(), stage, "family", "by family");
        appendKeyedBreakdown(sb, e.getValue(), stage, "selectivity", "by selectivity");
        appendKeyedBreakdown(sb, e.getValue(), stage, "metric", "by metric");
        appendKeyedBreakdown(sb, e.getValue(), stage, "k", "by k");
        appendKeyedBreakdown(sb, e.getValue(), stage, "empty_boundary", "by empty/boundary");
        if (suite != null && suite.kind != null
            && "ablation".equalsIgnoreCase(suite.kind.trim())) {
          appendFactorFamily(sb, e.getValue(), stage);
        }
      }
    }

    Path out = resultDir.resolve("summary.md");
    if (Files.isRegularFile(out)) {
      String prev = new String(Files.readAllBytes(out), StandardCharsets.UTF_8);
      Files.write(out, (prev + "\n---\n\n" + sb).getBytes(StandardCharsets.UTF_8));
    } else {
      Files.write(out, sb.toString().getBytes(StandardCharsets.UTF_8));
    }
  }

  private static void appendFailTaxonomy(StringBuilder sb, List<Map<String, Object>> rows) {
    Map<String, Integer> counts = new LinkedHashMap<String, Integer>();
    for (Map<String, Object> r : rows) {
      String fc = r.get("fail_class") == null ? "unknown" : String.valueOf(r.get("fail_class"));
      Integer n = counts.get(fc);
      counts.put(fc, Integer.valueOf(n == null ? 1 : n.intValue() + 1));
    }
    sb.append("Fail taxonomy (n=").append(rows.size()).append("): ");
    boolean first = true;
    for (Map.Entry<String, Integer> e : counts.entrySet()) {
      if (!first) {
        sb.append(", ");
      }
      first = false;
      sb.append(e.getKey()).append("=").append(e.getValue());
    }
    sb.append("\n\n");
  }

  private static void appendKeyedBreakdown(StringBuilder sb, List<Map<String, Object>> rows,
                                           String stage, String field, String heading) {
    Map<String, List<Map<String, Object>>> byKey = new LinkedHashMap<String, List<Map<String, Object>>>();
    boolean any = false;
    for (Map<String, Object> r : rows) {
      Object v = r.get(field);
      if (v == null) {
        continue;
      }
      any = true;
      String key = String.valueOf(v);
      List<Map<String, Object>> list = byKey.get(key);
      if (list == null) {
        list = new ArrayList<Map<String, Object>>();
        byKey.put(key, list);
      }
      list.add(r);
    }
    if (!any) {
      return;
    }
    sb.append("### ").append(heading).append("\n\n");
    sb.append("| ").append(field).append(" | n | ok | t_p50 |\n|---|---:|---:|---:|\n");
    String latKey = latencyKey(stage);
    for (Map.Entry<String, List<Map<String, Object>>> e : byKey.entrySet()) {
      int ok = 0;
      List<Long> lat = new ArrayList<Long>();
      for (Map<String, Object> r : e.getValue()) {
        if (isPass(r, stage)) {
          ok++;
          addLong(lat, r.get(latKey));
        }
      }
      sb.append("| ").append(e.getKey())
          .append(" | ").append(e.getValue().size())
          .append(" | ").append(ok).append("/").append(e.getValue().size())
          .append(" | ").append(fmt(percentile(lat, 0.50)))
          .append(" |\n");
    }
    sb.append('\n');
  }

  private static void appendFactorFamily(StringBuilder sb, List<Map<String, Object>> rows,
                                         String stage) {
    Map<String, List<Map<String, Object>>> byKey = new LinkedHashMap<String, List<Map<String, Object>>>();
    boolean any = false;
    for (Map<String, Object> r : rows) {
      Object factor = r.get("factor");
      Object fam = r.get("family");
      if (factor == null || fam == null) {
        continue;
      }
      any = true;
      String key = factor + " / " + fam;
      List<Map<String, Object>> list = byKey.get(key);
      if (list == null) {
        list = new ArrayList<Map<String, Object>>();
        byKey.put(key, list);
      }
      list.add(r);
    }
    if (!any) {
      return;
    }
    sb.append("### by factor × family\n\n");
    sb.append("| factor / family | n | ok | t_p50 |\n|---|---:|---:|---:|\n");
    String latKey = latencyKey(stage);
    for (Map.Entry<String, List<Map<String, Object>>> e : byKey.entrySet()) {
      int ok = 0;
      List<Long> lat = new ArrayList<Long>();
      for (Map<String, Object> r : e.getValue()) {
        if (isPass(r, stage)) {
          ok++;
          addLong(lat, r.get(latKey));
        }
      }
      sb.append("| ").append(e.getKey())
          .append(" | ").append(e.getValue().size())
          .append(" | ").append(ok).append("/").append(e.getValue().size())
          .append(" | ").append(fmt(percentile(lat, 0.50)))
          .append(" |\n");
    }
    sb.append('\n');
  }

  private static int uniqueQueryCount(List<Map<String, Object>> trials) {
    if (trials == null) {
      return 0;
    }
    LinkedHashSet<String> ids = new LinkedHashSet<String>();
    for (Map<String, Object> t : trials) {
      Object q = t.get("query_id");
      if (q != null) {
        ids.add(String.valueOf(q));
      }
    }
    return ids.size();
  }

  private static String latencyKey(String stage) {
    if ("parse".equalsIgnoreCase(stage)) {
      return "t_parse_ms";
    }
    if ("plan".equalsIgnoreCase(stage)) {
      return "t_plan_ms";
    }
    return "t_e2e_ms";
  }

  private static boolean isPass(Map<String, Object> r, String stage) {
    if ("parse".equalsIgnoreCase(stage)) {
      return Boolean.TRUE.equals(r.get("ok_ex"));
    }
    if ("plan".equalsIgnoreCase(stage)) {
      return Boolean.TRUE.equals(r.get("plan_ok")) || Boolean.TRUE.equals(r.get("ok_oracle"));
    }
    return Boolean.TRUE.equals(r.get("ok_oracle"));
  }

  static String summaryArmKey(Map<String, Object> r) {
    Object arm = r.get("arm");
    if (arm == null) {
      arm = r.get("factor");
    }
    if (arm == null) {
      arm = r.get("arm_or_factor");
    }
    String key = String.valueOf(arm);
    if (!isLlmFallbackRow(r) || key.contains("fallback")) {
      return key;
    }
    Object requested = r.get("arm_requested");
    String base = requested != null ? String.valueOf(requested) : key;
    if ("kart".equals(base)) {
      return "kart-rule-fallback";
    }
    return base + "-rule-fallback";
  }

  static boolean isLlmFallbackRow(Map<String, Object> r) {
    Object v = r.get("llm_fallback");
    if (Boolean.TRUE.equals(v)) {
      return true;
    }
    return v != null && "true".equalsIgnoreCase(String.valueOf(v).trim());
  }

  private static Map<String, List<Map<String, Object>>> groupByArm(List<Map<String, Object>> rows) {
    Map<String, List<Map<String, Object>>> m = new LinkedHashMap<String, List<Map<String, Object>>>();
    for (Map<String, Object> r : rows) {
      String key = summaryArmKey(r);
      List<Map<String, Object>> list = m.get(key);
      if (list == null) {
        list = new ArrayList<Map<String, Object>>();
        m.put(key, list);
      }
      list.add(r);
    }
    return m;
  }

  private static Object first(Map<String, Object> r, String a, String b) {
    Object v = r.get(a);
    return v != null ? v : r.get(b);
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

  private static String fmt(Long v) {
    return v == null ? "-" : String.valueOf(v);
  }
}
