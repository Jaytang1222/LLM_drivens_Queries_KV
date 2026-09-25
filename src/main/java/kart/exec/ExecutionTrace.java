package kart.exec;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-node and overall execution metrics (IMPLEMENTATION_PLAN.md §16 / NFR-3).
 * Metrics that cannot be observed are serialized as {@code null} (not 0).
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public final class ExecutionTrace {

  @JsonInclude(JsonInclude.Include.ALWAYS)
  public static final class NodeTrace {
    public long rows;
    public long bytes;
    public long elapsedMs;
    /** Planner estimate when available; otherwise null. */
    public Long estimated_rows;
    public Long estimated_bytes;
    /**
     * True HBase RPC count when available from client/server metrics; otherwise null
     * (do not invent — §13.6 / design.md §11).
     */
    public Long rpc_count;
    /**
     * Client-visible Scan/Get work units: opens + estimated Scan pages from
     * {@code caching} (aligned with cost-model {@code rpcPerRange} semantics).
     */
    public Long client_ops;
    /** Number of ScanTask / range opens emitted by this node (design.md §11). */
    public Long emitted_ranges;
  }

  public String run_id;
  public String queryId;
  public String plan_id;
  public String planId;
  public String manifest_id;
  public Map<String, NodeTrace> nodes = new LinkedHashMap<String, NodeTrace>();
  public List<Map<String, Object>> operator_metrics = new ArrayList<Map<String, Object>>();

  public long totalRows;
  public long totalBytes;
  public long elapsedMs;

  public Long candidate_chunks;
  public Long matched_chunks;
  public Long matched_trajectories;
  public Long deduplicated_chunks;
  public Long fetched_chunks;
  public Long filter_passed;
  public Long dtw_cells;
  public Long llm_calls;
  public Long llm_tokens;
  /** True HBase RPC metrics only; null when unavailable. */
  public Long rpc_count;
  /** Client Scan/Get open count. */
  public Long client_ops_count;

  /** Phase wall-clock ms (filled when observable). */
  public Long phase_index_ms;
  public Long phase_set_ms;
  public Long phase_fetch_ms;
  public Long phase_exact_ms;
  public Long phase_reconstruct_ms;
  public Long phase_sim_ms;
  public Long phase_topk_ms;

  /** Planning wall ms (search → select); E2/E3 {@code t_plan_ms}. */
  public Long t_plan_ms;
  /** Execute wall ms (Coordinator only); null for plan-only runs. */
  public Long t_exec_ms;
  /** selected.estimated_ms − min(safe); §19.1 plan regret. */
  public Double plan_regret_ms;
  public Double best_safe_estimated_ms;
  /** Wire name: rule | best_first | llm | llm_direct. */
  public String planner_mode;

  /** @deprecated use {@link #dtw_cells}; kept for older readers */
  public long dtwCells;
  /** @deprecated use {@link #candidate_chunks} */
  public long candidateChunks;

  public String status = "OK";

  public NodeTrace node(String id) {
    NodeTrace t = nodes.get(id);
    if (t == null) {
      t = new NodeTrace();
      t.estimated_rows = null;
      t.estimated_bytes = null;
      t.rpc_count = null;
      t.client_ops = null;
      t.emitted_ranges = null;
      nodes.put(id, t);
    }
    return t;
  }

  public void addNode(String id, long rows, long bytes, long elapsedMs) {
    NodeTrace t = node(id);
    t.rows += rows;
    t.bytes += bytes;
    t.elapsedMs += elapsedMs;
    // estimated_* must come from planner CostFeatures when available; do not mirror actuals.
    totalRows += rows;
    totalBytes += bytes;
  }

  /** Rebuild operator_metrics list from nodes map before serialization. */
  public void finalizeMetrics() {
    operator_metrics = new ArrayList<Map<String, Object>>();
    for (Map.Entry<String, NodeTrace> e : nodes.entrySet()) {
      Map<String, Object> m = new LinkedHashMap<String, Object>();
      m.put("node_id", e.getKey());
      NodeTrace t = e.getValue();
      m.put("actual_rows", Long.valueOf(t.rows));
      m.put("actual_bytes", Long.valueOf(t.bytes));
      m.put("elapsed_ms", Long.valueOf(t.elapsedMs));
      m.put("estimated_rows", t.estimated_rows);
      m.put("estimated_bytes", t.estimated_bytes);
      m.put("rpc_count", t.rpc_count);
      m.put("client_ops", t.client_ops);
      m.put("emitted_ranges", t.emitted_ranges);
      operator_metrics.add(m);
    }
    // sync deprecated aliases
    if (dtw_cells != null) {
      dtwCells = dtw_cells.longValue();
    }
    if (candidate_chunks != null) {
      candidateChunks = candidate_chunks.longValue();
    }
  }

  public String toJson() throws IOException {
    finalizeMetrics();
    return new ObjectMapper()
        .enable(SerializationFeature.INDENT_OUTPUT)
        .writeValueAsString(this);
  }
}
