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
    /** Not separately estimated in MVP executor. */
    public Long estimated_rows;
    public Long estimated_bytes;
    /** HBase client RPC count — unavailable → null. */
    public Long rpc_count;
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
  public Long rpc_count;

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
      nodes.put(id, t);
    }
    return t;
  }

  public void addNode(String id, long rows, long bytes, long elapsedMs) {
    NodeTrace t = node(id);
    t.rows += rows;
    t.bytes += bytes;
    t.elapsedMs += elapsedMs;
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
