package kart.bench;

import kart.ir.BoundIr;
import kart.llm.LlmClient;
import kart.llm.LlmMessage;
import kart.llm.LlmOptions;
import kart.llm.LlmResponse;
import kart.plan.PlanBuilder;
import kart.plan.PlanEnvelope;
import kart.query.QueryEngine;
import kart.search.PlannerMode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Development diagnostic arm: execute a single plan id from
 * {@code KART_FIXED_PLAN_ID}. Optional parallel LLM via
 * {@code KART_FIXED_PLAN_PARALLEL_LLM=1}.
 *
 * <p>refine_5: records full LLM call wall inside the worker
 * ({@code llm_call_ms}) separately from post-exec await
 * ({@code llm_await_after_exec_ms}). Never reports call duration as 0 when the
 * request completed before await.
 */
public final class FixedPlanIdArm implements Arm {

  @Override
  public String id() {
    return "fixed-plan";
  }

  static String resolvePlanId() {
    String raw = System.getenv("KART_FIXED_PLAN_ID");
    return raw == null ? null : raw.trim();
  }

  static boolean parallelLlm() {
    String raw = System.getenv("KART_FIXED_PLAN_PARALLEL_LLM");
    if (raw == null || raw.trim().isEmpty()) {
      return false;
    }
    String v = raw.trim().toLowerCase();
    return !(v.equals("0") || v.equals("false") || v.equals("off") || v.equals("no"));
  }

  /** Use short decision prompt (not dummy KEEP) when true (default on). */
  static boolean realShortPrompt() {
    String raw = System.getenv("KART_FIXED_PLAN_REAL_PROMPT");
    if (raw == null || raw.trim().isEmpty()) {
      return true;
    }
    String v = raw.trim().toLowerCase();
    return !(v.equals("0") || v.equals("false") || v.equals("off") || v.equals("no"));
  }

  @Override
  public TrialResult run(BoundIr ir, BenchContext ctx) throws Exception {
    long wall0 = System.currentTimeMillis();
    String planId = resolvePlanId();
    if (planId == null || planId.isEmpty()) {
      return TrialResult.fail("fixed-plan: set KART_FIXED_PLAN_ID");
    }
    PlanEnvelope env = null;
    for (PlanEnvelope e : PlanBuilder.buildCandidatesWithMergeVariants(ir)) {
      if (e != null && planId.equals(e.plan_id)) {
        env = e;
        break;
      }
    }
    if (env == null) {
      return TrialResult.fail("fixed-plan: unknown plan_id " + planId);
    }
    QueryEngine engine = ctx.newEngine(PlannerMode.RULE);
    Path art = ctx.planOnly ? null : ctx.artifactDir(id(), ir != null ? ir.query_id : "q");

    ExecutorService pool = null;
    Future<LlmProbe> llmFut = null;
    final AtomicLong submitMs = new AtomicLong(0L);
    boolean par = parallelLlm() && ctx.llm != null && !ctx.planOnly;
    if (par) {
      pool = Executors.newSingleThreadExecutor();
      final LlmClient llm = ctx.llm;
      final BoundIr qIr = ir;
      final boolean real = realShortPrompt();
      submitMs.set(System.currentTimeMillis());
      llmFut = pool.submit(new Callable<LlmProbe>() {
        @Override
        public LlmProbe call() throws Exception {
          long taskStart = System.currentTimeMillis();
          List<LlmMessage> msgs = new ArrayList<LlmMessage>();
          if (real) {
            msgs.add(LlmMessage.system(
                "Select the safer faster end-to-end plan. Reply one JSON only. "
                    + "Examples: {\"choice\":\"KEEP\"} or {\"choice\":0}. "
                    + "Never output the letter N."));
            msgs.add(LlmMessage.user(
                "prompt_version=cbo_llm_short_v9_iso\n"
                    + "task=choose_faster_safe_plan\n"
                    + "exec_saving_ms unit=ms; positive means candidate exec faster than CBO; "
                    + "does NOT subtract LLM/prep critical-path cost.\n"
                    + "All numbers are predictions; uncertainty unknown != 0.\n"
                    + "schedule_mode=parallel_probe\n"
                    + "cbo=P_TZ\n"
                    + "q=" + (qIr != null ? String.valueOf(qIr.query_id) : "?") + "\n"
                    + "0=P_T access=TIME_ONLY exec_saving_ms=1800 "
                    + "saving_uncertainty_ms=200 estimated_extra_critical_path_ms=400\n"
                    + "Examples: {\"choice\":\"KEEP\"} OR {\"choice\":0}\n"));
          } else {
            msgs.add(LlmMessage.system("Reply JSON {\"choice\":\"KEEP\"} only."));
            msgs.add(LlmMessage.user("choice probe"));
          }
          long reqStart = System.currentTimeMillis();
          LlmResponse resp = llm.chat(msgs, null, LlmOptions.oneShot(60_000));
          long reqEnd = System.currentTimeMillis();
          return new LlmProbe(taskStart, reqStart, reqEnd, resp);
        }
      });
    }

    long planStart = System.currentTimeMillis();
    QueryEngine.RunResult planned = engine.runFixed(ir, null, true, env,
        QueryEngine.PrepareMode.SAFETY_ONLY);
    long planMs = Math.max(0L, System.currentTimeMillis() - planStart);
    if (planned == null || planned.selected == null) {
      if (llmFut != null) {
        llmFut.cancel(true);
      }
      if (pool != null) {
        pool.shutdownNow();
      }
      return TrialResult.fail("fixed-plan: validate failed for " + planId);
    }

    QueryEngine.RunResult executed;
    long execMs;
    long execStart = 0L;
    long execEnd = 0L;
    if (ctx.planOnly) {
      executed = planned;
      execMs = 0L;
    } else {
      execStart = System.currentTimeMillis();
      executed = engine.executeSelected(ir, art, planned);
      execEnd = System.currentTimeMillis();
      execMs = Math.max(0L, execEnd - execStart);
    }

    Long llmCallMs = null;
    Long llmAwaitAfterExecMs = null;
    Long llmSubmitMs = null;
    Long llmTaskStartMs = null;
    Long llmReqStartMs = null;
    Long llmReqEndMs = null;
    String llmError = null;
    Integer tokensIn = null;
    Integer tokensOut = null;
    if (llmFut != null) {
      long await0 = System.currentTimeMillis();
      try {
        LlmProbe probe = llmFut.get();
        long awaitEnd = System.currentTimeMillis();
        llmAwaitAfterExecMs = Long.valueOf(Math.max(0L, awaitEnd - await0));
        if (probe != null) {
          llmTaskStartMs = Long.valueOf(probe.taskStartMs);
          llmReqStartMs = Long.valueOf(probe.reqStartMs);
          llmReqEndMs = Long.valueOf(probe.reqEndMs);
          llmCallMs = Long.valueOf(Math.max(0L, probe.reqEndMs - probe.reqStartMs));
          if (probe.resp != null) {
            tokensIn = probe.resp.promptTokens;
            tokensOut = probe.resp.completionTokens;
          }
        }
      } catch (Exception e) {
        llmAwaitAfterExecMs = Long.valueOf(Math.max(0L, System.currentTimeMillis() - await0));
        llmError = e.getClass().getSimpleName();
        // call may have finished; leave llm_call_ms null rather than fake 0
      }
      llmSubmitMs = Long.valueOf(submitMs.get());
      pool.shutdownNow();
    }

    long wall = Math.max(0L, System.currentTimeMillis() - wall0);
    TrialResult tr = TrialResult.fromRun(executed, wall);
    tr.t_plan_ms = Long.valueOf(planMs);
    tr.t_exec_ms = Long.valueOf(execMs);
    tr.t_e2e_ms = Long.valueOf(wall);
    tr.extras.put("fixed_plan_id", planId);
    tr.extras.put("parallel_llm", Boolean.valueOf(par));
    tr.extras.put("real_short_prompt", Boolean.valueOf(realShortPrompt()));
    // Full request wall inside worker (refine_5). Do not alias await-after-exec.
    if (llmCallMs != null) {
      tr.extras.put("llm_call_ms", llmCallMs);
      tr.extras.put("llm_latency_ms", llmCallMs);
    } else if (par) {
      tr.extras.put("llm_latency_missing_reason",
          llmError != null ? llmError : "response_not_observed");
    }
    if (llmAwaitAfterExecMs != null) {
      tr.extras.put("llm_await_after_exec_ms", llmAwaitAfterExecMs);
    }
    if (llmSubmitMs != null) {
      tr.extras.put("llm_submit_epoch_ms", llmSubmitMs);
    }
    if (llmTaskStartMs != null) {
      tr.extras.put("llm_task_start_epoch_ms", llmTaskStartMs);
    }
    if (llmReqStartMs != null) {
      tr.extras.put("llm_req_start_epoch_ms", llmReqStartMs);
    }
    if (llmReqEndMs != null) {
      tr.extras.put("llm_req_end_epoch_ms", llmReqEndMs);
    }
    if (execStart > 0L) {
      tr.extras.put("exec_start_epoch_ms", Long.valueOf(execStart));
      tr.extras.put("exec_end_epoch_ms", Long.valueOf(execEnd));
    }
    if (tokensIn != null) {
      tr.extras.put("tokens_in", tokensIn);
    }
    if (tokensOut != null) {
      tr.extras.put("tokens_out", tokensOut);
    }
    tr.extras.put("t_fixed_prepare_ms", planned.t_fixed_prepare_ms);
    tr.extras.put("t_fixed_safety_ms", planned.t_fixed_safety_ms);
    if (planned.validatorTiming != null) {
      planned.validatorTiming.putExtras(tr.extras);
    }
    tr.extras.put("diagnostic_arm", Boolean.TRUE);
    tr.extras.put("timing_schema_version", "refine5_iso_v1");
    return tr;
  }

  private static final class LlmProbe {
    final long taskStartMs;
    final long reqStartMs;
    final long reqEndMs;
    final LlmResponse resp;

    LlmProbe(long taskStartMs, long reqStartMs, long reqEndMs, LlmResponse resp) {
      this.taskStartMs = taskStartMs;
      this.reqStartMs = reqStartMs;
      this.reqEndMs = reqEndMs;
      this.resp = resp;
    }
  }
}
