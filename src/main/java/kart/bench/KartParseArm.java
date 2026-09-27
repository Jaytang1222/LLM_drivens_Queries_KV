package kart.bench;

import com.fasterxml.jackson.databind.ObjectMapper;
import kart.config.AppConfig;
import kart.dialog.Dialog;
import kart.exec.QueryResult;
import kart.ir.IrBinder;
import kart.ir.IrSchemaValidator;
import kart.llm.LlmUsageAccumulator;
import kart.llm.PromptBuilder;
import kart.query.QueryEngine;
import kart.search.PlannerMode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Native E1 arm: NL → DraftIR → BoundIR (single round, no clarify).
 * Inference is label-blind; gold reject/clarify labels are applied only via {@link ParseScore}.
 * When gold ids are present on supported items, runs {@code query-ir} with RULE for EX.
 */
public final class KartParseArm implements ParseArm {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Override
  public String id() {
    return "kart";
  }

  @Override
  public ParseTrialResult parse(NlItem item, BenchContext ctx) throws Exception {
    ParseTrialResult out = new ParseTrialResult();
    if (item == null || item.utterance == null || item.utterance.trim().isEmpty()) {
      out.error = "empty utterance";
      out.ok_ir_valid = Boolean.FALSE;
      out.ok_ex = Boolean.FALSE;
      ParseScore.attachGoldLabels(out, item);
      return out;
    }
    String early = ParseFairness.earlyReject(item.utterance.trim());
    if (early != null) {
      return ParseFairness.rejectedTrial(item, early);
    }
    if (ctx.llm == null) {
      out.error = "LLM client required for parse arm kart";
      out.ok_ir_valid = Boolean.FALSE;
      out.ok_ex = Boolean.FALSE;
      ParseScore.attachGoldLabels(out, item);
      return out;
    }

    AppConfig cfg = AppConfig.load(ctx.root);
    IrSchemaValidator validator = new IrSchemaValidator(ctx.root.resolve("schemas"));
    PromptBuilder prompts = new PromptBuilder(cfg.regions(), "tdrive_v1");
    IrBinder binder = new IrBinder(
        cfg.regions(), ctx.kv, ctx.layout.tableMeta, ctx.layout.shardCount,
        ctx.manifest.manifest_id, "point_similarity_v2");
    QueryEngine engine = ctx.newEngine(PlannerMode.RULE);
    LlmUsageAccumulator usage = engine.usageAccumulator();
    Dialog dialog = new Dialog(ctx.llm, prompts, validator, binder, engine,
        ctx.artifactDir(id(), item.query_id) != null
            ? ctx.artifactDir(id(), item.query_id) : ctx.runDir.resolve("tmp-parse"), 0);

    long t0 = System.currentTimeMillis();
    Dialog.Outcome oc = dialog.parseToBoundNoClarify(item.utterance.trim());
    long total = Math.max(0L, System.currentTimeMillis() - t0);
    out.t_parse_ms = Long.valueOf(total);
    out.t_parse_total_ms = Long.valueOf(total);
    long modelMs = usage.latencyMs();
    if (modelMs > total) {
      modelMs = total;
    }
    out.t_parse_model_ms = Long.valueOf(modelMs);
    out.t_adapter_startup_ms = Long.valueOf(0L);
    out.llm_calls = usage.callsAsLongOrNull();
    out.tokens_in = usage.promptTokensOrNull();
    out.tokens_out = usage.completionTokensOrNull();
    if (usage.jsonModeFallbacks() > 0) {
      out.extras.put("json_mode_fallbacks", Integer.valueOf(usage.jsonModeFallbacks()));
    }
    if (usage.failedAttempts() > 0) {
      out.extras.put("llm_failed_attempts", Integer.valueOf(usage.failedAttempts()));
    }
    if (usage.lastHttpStatus() != null) {
      out.extras.put("http_status", usage.lastHttpStatus());
    }
    if (usage.anyHttpFailure()) {
      out.extras.put("infrastructure_failure", Boolean.TRUE);
    }

    boolean unsupported = Dialog.State.Unsupported.equals(oc.state)
        || "UNSUPPORTED_QUERY".equals(oc.status);
    boolean needClarify = "NEED_CLARIFICATION".equals(oc.status);
    out.reject_actual = Boolean.valueOf(unsupported);
    out.clarify_actual = Boolean.valueOf(needClarify);

    if (unsupported || needClarify || oc.bound == null) {
      out.error = oc.error != null ? oc.error : oc.status;
      ParseScore.scoreAgainstGold(out, item);
      return out;
    }

    out.ok_ir_valid = Boolean.TRUE;
    out.bound = oc.bound;
    try {
      out.draft_ir_json = MAPPER.writeValueAsString(oc.bound);
    } catch (Exception ignore) {
      out.draft_ir_json = null;
    }

    Path art = ctx.artifactDir("parse-ex", item.query_id);
    QueryEngine.RunResult rr = engine.run(oc.bound, art, false, null);
    out.queryResult = rr == null ? null : rr.result;
    String mismatch = compareGold(item, out.queryResult);
    out.ok_ex = Boolean.valueOf(mismatch == null);
    out.ok = Boolean.TRUE.equals(out.ok_ex);
    if (mismatch != null) {
      out.error = mismatch;
    }
    if (item.reject_expected || item.clarify_expected) {
      ParseScore.scoreAgainstGold(out, item);
    } else {
      ParseScore.attachGoldLabels(out, item);
    }
    return out;
  }

  static String compareGold(NlItem item, QueryResult actual) {
    if (item == null) {
      return "missing NlItem";
    }
    if (actual == null) {
      return "null QueryResult";
    }
    if (!"OK".equals(actual.status)) {
      return "status=" + actual.status + " error=" + actual.error;
    }
    if ("contains".equalsIgnoreCase(item.oracle_mode)) {
      if (actual.trajectoryIds == null) {
        return "null trajectoryIds";
      }
      List<String> need = item.gold_must_contain != null
          ? item.gold_must_contain : item.gold_trajectory_ids;
      if (need == null || need.isEmpty()) {
        return "contains mode missing gold_must_contain";
      }
      for (String id : need) {
        if (!actual.trajectoryIds.contains(id)) {
          return "missing required id=" + id;
        }
      }
      return null;
    }
    OracleChecker.Answer ans = toAnswer(item);
    if (ans.topK != null) {
      // Handbook gold often lists ordered ids without distances — compare id order only.
      boolean anyDist = false;
      for (OracleChecker.Scored s : ans.topK) {
        if (s.distance != 0.0) {
          anyDist = true;
          break;
        }
      }
      if (!anyDist && actual.topK != null) {
        if (ans.topK.size() != actual.topK.size()
            && ans.trajectoryIds.size() != actual.trajectoryIds.size()) {
          // fall through to full compare using trajectory_ids
        } else if (ans.trajectoryIds.size() == actual.trajectoryIds.size()) {
          for (int i = 0; i < ans.trajectoryIds.size(); i++) {
            if (!ans.trajectoryIds.get(i).equals(actual.trajectoryIds.get(i))) {
              return "id mismatch at " + i;
            }
          }
          return null;
        }
      }
    }
    return OracleChecker.compare(ans, actual);
  }

  static OracleChecker.Answer toAnswer(NlItem item) {
    OracleChecker.Answer a = new OracleChecker.Answer();
    a.queryId = item.query_id;
    a.trajectoryIds = item.gold_trajectory_ids != null
        ? new ArrayList<String>(item.gold_trajectory_ids) : new ArrayList<String>();
    if (item.gold_top_k != null && !item.gold_top_k.isEmpty()) {
      a.topK = new ArrayList<OracleChecker.Scored>();
      for (NlItem.GoldScored g : item.gold_top_k) {
        if (g == null) {
          continue;
        }
        OracleChecker.Scored s = new OracleChecker.Scored();
        s.trajectoryId = g.trajectory_id;
        s.tid = g.tid;
        s.distance = g.distance;
        a.topK.add(s);
      }
    }
    return a;
  }
}
