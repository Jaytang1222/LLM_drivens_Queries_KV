package kart.probe;

import kart.compile.LayoutContext;
import kart.compile.PhysicalPlan;
import kart.compile.QueryCompiler;
import kart.compile.ScanTask;
import kart.exec.KvBackend;
import kart.ir.BoundIr;
import kart.plan.PlanEnvelope;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Budgeted local probe (refine_6 §7): keys-only sample of index scan ranges to
 * estimate joint-filter candidate volume. Not a full plan execution.
 */
public final class JointCandidateProbe {

  public static final String ACTION_ID = "PROBE_JOINT_CANDIDATES";

  public static final class Result {
    public boolean started;
    public boolean completed;
    public boolean budgetExhausted;
    public boolean cancelled;
    public String error;
    public long wallMs;
    public long budgetMs;
    public int rangesTotal;
    public int rangesSampled;
    public long rowsSeen;
    public long bytesEst;
    public long rpcCalls;
    public double estCandidateScale;
    public Map<String, Object> extras = new LinkedHashMap<String, Object>();
  }

  private final LayoutContext layout;
  private final KvBackend kv;

  public JointCandidateProbe(LayoutContext layout, KvBackend kv) {
    this.layout = layout;
    this.kv = kv;
  }

  public static long resolveBudgetMs() {
    String raw = System.getenv("KART_PROBE_BUDGET_MS");
    if (raw == null || raw.trim().isEmpty()) {
      return 50L;
    }
    try {
      return Math.max(1L, Long.parseLong(raw.trim()));
    } catch (NumberFormatException e) {
      return 50L;
    }
  }

  public static int resolveMaxRows() {
    String raw = System.getenv("KART_PROBE_MAX_ROWS");
    if (raw == null || raw.trim().isEmpty()) {
      return 200;
    }
    try {
      return Math.max(1, Integer.parseInt(raw.trim()));
    } catch (NumberFormatException e) {
      return 200;
    }
  }

  public static long resolveSeed() {
    String raw = System.getenv("KART_PROBE_SEED");
    if (raw == null || raw.trim().isEmpty()) {
      return 42L;
    }
    try {
      return Long.parseLong(raw.trim());
    } catch (NumberFormatException e) {
      return 42L;
    }
  }

  /**
   * Probe candidate volume for {@code env} under wall/row budgets.
   * Stratifies by hashing range index with a fixed seed (not first-N RowKeys only).
   */
  public Result probe(PlanEnvelope env, BoundIr ir) {
    Result out = new Result();
    out.budgetMs = resolveBudgetMs();
    long maxRows = resolveMaxRows();
    long seed = resolveSeed();
    long t0 = System.currentTimeMillis();
    out.started = true;
    if (env == null || ir == null || layout == null || kv == null) {
      out.error = "probe_missing_inputs";
      out.wallMs = Math.max(0L, System.currentTimeMillis() - t0);
      return out;
    }
    PhysicalPlan phys;
    try {
      phys = new QueryCompiler(layout).compile(env, ir);
    } catch (Exception e) {
      out.error = "probe_compile_failed";
      out.wallMs = Math.max(0L, System.currentTimeMillis() - t0);
      return out;
    }
    List<ScanTask> tasks = phys != null && phys.scanTasks != null
        ? phys.scanTasks : Collections.<ScanTask>emptyList();
    out.rangesTotal = tasks.size();
    if (tasks.isEmpty()) {
      out.completed = true;
      out.estCandidateScale = 0.0d;
      out.wallMs = Math.max(0L, System.currentTimeMillis() - t0);
      return out;
    }

    List<Integer> order = new ArrayList<Integer>(tasks.size());
    for (int i = 0; i < tasks.size(); i++) {
      order.add(Integer.valueOf(i));
    }
    Collections.shuffle(order, new Random(seed ^ (env.plan_id == null ? 0L : env.plan_id.hashCode())));

    int sampleCap = Math.min(tasks.size(), Math.max(2, Math.min(8, tasks.size() / 4 + 1)));
    long rows = 0L;
    long rpcs = 0L;
    int sampled = 0;
    try {
      for (int k = 0; k < sampleCap; k++) {
        if (System.currentTimeMillis() - t0 >= out.budgetMs) {
          out.budgetExhausted = true;
          break;
        }
        if (rows >= maxRows) {
          out.budgetExhausted = true;
          break;
        }
        ScanTask task = tasks.get(order.get(k).intValue());
        if (task == null || task.table == null) {
          continue;
        }
        sampled++;
        rpcs++;
        final long[] local = new long[] {0L};
        final long rowBudget = maxRows - rows;
        final long deadline = t0 + out.budgetMs;
        try {
          kv.scanConsume(task.table, task.startBytes(), task.stopBytes(),
              Collections.<String>emptyList(), 64,
              new KvBackend.RowConsumer() {
                @Override
                public void accept(KvBackend.Row row) {
                  if (local[0] >= rowBudget || System.currentTimeMillis() >= deadline) {
                    throw new ProbeBudgetStop();
                  }
                  local[0]++;
                }
              });
        } catch (ProbeBudgetStop stop) {
          rows += local[0];
          out.budgetExhausted = true;
          break;
        }
        rows += local[0];
      }
      out.completed = true;
    } catch (ProbeBudgetStop stop) {
      out.budgetExhausted = true;
      out.completed = true;
    } catch (Exception e) {
      out.error = e.getClass().getSimpleName();
      out.completed = false;
    }
    out.rangesSampled = sampled;
    out.rowsSeen = rows;
    out.rpcCalls = rpcs;
    out.bytesEst = rows * 64L;
    // Scale sample rows by inverse sample fraction of ranges (heuristic).
    double frac = sampled <= 0 ? 0.0d : ((double) sampled) / Math.max(1, tasks.size());
    out.estCandidateScale = frac <= 0.0d ? 0.0d : (rows / frac);
    out.extras.put("probe_seed", Long.valueOf(seed));
    out.extras.put("probe_sample_cap", Integer.valueOf(sampleCap));
    out.extras.put("plan_id", env.plan_id);
    out.wallMs = Math.max(0L, System.currentTimeMillis() - t0);
    return out;
  }

  /** Control-flow stop when row/time budget hit inside scanner callback. */
  @SuppressWarnings("serial")
  static final class ProbeBudgetStop extends RuntimeException {
    ProbeBudgetStop() {
      super("probe_budget");
    }
  }
}
