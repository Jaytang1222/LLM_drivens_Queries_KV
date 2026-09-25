package kart.exec;

import kart.codec.RowKeyCodec;
import kart.compile.LayoutContext;
import kart.compile.PhysicalPlan;
import kart.compile.ScanTask;
import kart.cost.RegionMapping;
import kart.data.Chunk;
import kart.ir.BoundIr;
import kart.plan.Op;
import kart.plan.PlanEnvelope;
import kart.plan.PlanNode;
import kart.validation.SafePlanHandle;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Topological executor for a validated SafePlanHandle against a KvBackend.
 */
public final class Coordinator {

  private final KvBackend kv;
  private final BoundIr ir;
  private final LayoutContext layout;
  private final ExecLimits limits;
  private final RegionMapping regionMapping;

  /** Reused raw chunk payloads across FETCH / BATCH_GET. */
  private final Map<ChunkRef, List<Chunk.DecodedPoint>> chunkCache =
      new HashMap<ChunkRef, List<Chunk.DecodedPoint>>();
  /** Wire payload sizes for retained-byte accounting (soft_memory). */
  private final Map<ChunkRef, Long> payloadBytesByRef =
      Collections.synchronizedMap(new HashMap<ChunkRef, Long>());
  private final Map<Long, MetaRow> metaCache = new HashMap<Long, MetaRow>();
  private final AtomicLong rpcCounter = new AtomicLong(0L);
  private final AtomicLong retainedBytes = new AtomicLong(0L);
  private final ThreadLocal<ExecutionTrace.NodeTrace> activeNode =
      new ThreadLocal<ExecutionTrace.NodeTrace>();
  private long execDeadlineMs;
  private kart.cost.CostFeatures costEstimates;

  public Coordinator(KvBackend kv, BoundIr ir, LayoutContext layout, ExecLimits limits) {
    this(kv, ir, layout, limits, null);
  }

  public Coordinator(KvBackend kv, BoundIr ir, LayoutContext layout, ExecLimits limits,
                     RegionMapping regionMapping) {
    this.kv = kv;
    this.ir = ir;
    this.layout = layout;
    this.limits = limits == null ? ExecLimits.defaults() : limits;
    this.regionMapping = regionMapping != null ? regionMapping : new RegionMapping.ShardFallback();
  }

  /**
   * Inject planner CostFeatures so NodeTrace {@code estimated_*} come from the model,
   * not mirrored actuals (§14 / §16).
   */
  public void applyCostEstimates(kart.cost.CostFeatures features) {
    this.costEstimates = features;
  }

  public QueryResult execute(SafePlanHandle handle) {
    long t0 = System.currentTimeMillis();
    QueryResult result = new QueryResult();
    ExecutionTrace trace = new ExecutionTrace();
    result.trace = trace;
    PlanEnvelope env = handle.plan();
    PhysicalPlan phys = handle.physicalPlan();
    trace.run_id = env.query_id;
    trace.queryId = env.query_id;
    trace.plan_id = env.plan_id;
    trace.planId = env.plan_id;
    if (ir.snapshot != null) {
      trace.manifest_id = ir.snapshot.manifest_id;
    }
    // Filled by QueryEngine from LlmUsageAccumulator when available (NFR-3).
    trace.llm_calls = null;
    trace.llm_tokens = null;
    rpcCounter.set(0L);
    retainedBytes.set(0L);
    execDeadlineMs = limits.maxExecMs > 0
        ? (t0 + limits.maxExecMs)
        : Long.MAX_VALUE;
    seedEstimates(trace, env);

    Map<String, Object> values = new HashMap<String, Object>();
    try {
      List<PlanNode> order = topoOrder(env);
      int i = 0;
      while (i < order.size()) {
        PlanNode first = order.get(i);
        if (isIndexBranch(first.op)) {
          int j = i + 1;
          while (j < order.size() && isIndexBranch(order.get(j).op)) {
            j++;
          }
          runIndexBranches(order.subList(i, j), values, phys, trace);
          i = j;
        } else {
          runOneNode(first, values, phys, trace);
          i++;
        }
      }
      Object rootVal = values.get(env.root);
      applyRoot(result, rootVal);
      if (result.trajectoryIds != null) {
        trace.matched_trajectories = Long.valueOf(result.trajectoryIds.size());
      } else if (result.topK != null) {
        trace.matched_trajectories = Long.valueOf(result.topK.size());
      }
      result.status = "OK";
      trace.status = "OK";
    } catch (ExecException e) {
      result.status = e.code().name();
      result.error = e.getMessage();
      result.trajectoryIds = new ArrayList<String>();
      result.topK = null;
      trace.status = result.status;
    } catch (Exception e) {
      result.status = "FAILED";
      result.error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
      result.trajectoryIds = new ArrayList<String>();
      result.topK = null;
      trace.status = "FAILED";
    }
    trace.rpc_count = null; // no HBase RPC metrics available
    trace.client_ops_count = Long.valueOf(rpcCounter.get());
    trace.elapsedMs = System.currentTimeMillis() - t0;
    trace.finalizeMetrics();
    return result;
  }

  private void seedEstimates(ExecutionTrace trace, PlanEnvelope env) {
    if (costEstimates == null || env == null || env.nodes == null) {
      return;
    }
    long idxRows = Math.max(0L, costEstimates.estIndexRows);
    long cand = Math.max(0L, costEstimates.estCandidateChunks);
    long rawBytes = Math.max(0L, costEstimates.estRawBytes);
    long eligible = Math.max(0L, costEstimates.estEligibleTrajs);
    for (PlanNode n : env.nodes) {
      if (n == null || n.id == null) {
        continue;
      }
      ExecutionTrace.NodeTrace nt = trace.node(n.id);
      switch (n.op) {
        case TIME_RANGE_SCAN:
        case ZORDER_RANGE_SCAN:
        case EQUALITY_LOOKUP:
        case FULL_SCAN_CHUNKS:
          nt.estimated_rows = Long.valueOf(idxRows);
          nt.estimated_bytes = Long.valueOf((long) (idxRows * Math.max(16.0, costEstimates.avgChunkBytes / 8.0)));
          break;
        case INTERSECT:
        case UNION:
        case DEDUPLICATE:
          nt.estimated_rows = Long.valueOf(cand);
          nt.estimated_bytes = Long.valueOf(cand * 16L);
          break;
        case FETCH_TRAJECTORY_CHUNK:
          nt.estimated_rows = Long.valueOf(cand);
          nt.estimated_bytes = Long.valueOf(rawBytes);
          break;
        case EXACT_ST_FILTER:
          nt.estimated_rows = Long.valueOf(
              Math.max(1L, (long) Math.ceil(cand * costEstimates.filterPassRate)));
          nt.estimated_bytes = Long.valueOf(rawBytes);
          break;
        case PROJECT_TRAJECTORY_IDS:
        case EXCLUDE_REFERENCE:
          nt.estimated_rows = Long.valueOf(eligible);
          nt.estimated_bytes = Long.valueOf(eligible * 8L);
          break;
        case BATCH_GET_TRAJECTORY:
          nt.estimated_rows = Long.valueOf(eligible);
          nt.estimated_bytes = Long.valueOf(
              (long) Math.ceil(eligible * costEstimates.avgTrajLen * 24.0));
          break;
        case SIMILARITY:
          nt.estimated_rows = Long.valueOf(eligible);
          nt.estimated_bytes = Long.valueOf(Math.max(0L, costEstimates.estDtwCells));
          break;
        case TOP_K:
        case RETURN_TRAJECTORY_IDS:
          nt.estimated_rows = Long.valueOf(Math.max(1, costEstimates.topK));
          nt.estimated_bytes = Long.valueOf(Math.max(1, costEstimates.topK) * 32L);
          break;
        default:
          break;
      }
    }
  }

  private void noteRpc(int n) {
    if (n > 0) {
      rpcCounter.addAndGet(n);
      ExecutionTrace.NodeTrace nt = activeNode.get();
      if (nt != null) {
        long prev = nt.client_ops == null ? 0L : nt.client_ops.longValue();
        nt.client_ops = Long.valueOf(prev + n);
      }
    }
  }

  private void noteEmittedRanges(int n) {
    if (n <= 0) {
      return;
    }
    ExecutionTrace.NodeTrace nt = activeNode.get();
    if (nt != null) {
      long prev = nt.emitted_ranges == null ? 0L : nt.emitted_ranges.longValue();
      nt.emitted_ranges = Long.valueOf(prev + n);
    }
  }

  private void rememberPayload(ChunkRef ref, int payloadLen) {
    if (ref != null && payloadLen > 0) {
      payloadBytesByRef.put(ref, Long.valueOf(payloadLen));
    }
  }

  private static long rowWireBytes(KvBackend.Row row) {
    long n = 0L;
    if (row == null) {
      return 0L;
    }
    if (row.key != null) {
      n += row.key.length;
    }
    if (row.columns != null) {
      for (byte[] v : row.columns.values()) {
        if (v != null) {
          n += v.length;
        }
      }
    }
    return n;
  }

  private void checkBudgets() {
    if (System.currentTimeMillis() > execDeadlineMs) {
      throw new ExecException(ExecException.Code.RESOURCE_EXHAUSTED,
          "execution exceeded max_exec_ms=" + limits.maxExecMs);
    }
    if (limits.softMemoryBytes > 0 && retainedBytes.get() > limits.softMemoryBytes) {
      throw new ExecException(ExecException.Code.RESOURCE_EXHAUSTED,
          "retained bytes " + retainedBytes.get() + " > soft_memory_bytes " + limits.softMemoryBytes);
    }
  }

  private void accountBytes(long bytes) {
    if (bytes > 0) {
      retainedBytes.addAndGet(bytes);
      checkBudgets();
    }
  }

  /**
   * Index access leaves that may run concurrently before INTERSECT/UNION
   * (design.md §11: thread pool size = {@link ExecLimits#indexParallelism}).
   */
  static boolean isIndexBranch(Op op) {
    return op == Op.TIME_RANGE_SCAN || op == Op.ZORDER_RANGE_SCAN || op == Op.EQUALITY_LOOKUP;
  }

  private void runOneNode(PlanNode n, Map<String, Object> values, PhysicalPlan phys,
                          ExecutionTrace trace) throws IOException {
    checkBudgets();
    long nt0 = System.currentTimeMillis();
    activeNode.set(trace.node(n.id));
    Object out;
    try {
      out = eval(n, values, phys, trace);
    } finally {
      activeNode.remove();
    }
    values.put(n.id, out);
    accountBytes(estimateBytes(out));
    long elapsed = System.currentTimeMillis() - nt0;
    trace.addNode(n.id, estimateRows(out), estimateBytes(out), elapsed);
    recordOpMetrics(trace, n, out, values);
  }

  /**
   * Run independent index-branch nodes. Pool size {@link ExecLimits#indexParallelism}.
   * Result-set membership stays deterministic (each scan is deterministic; INTERSECT
   * walks {@code n.inputs} in plan order). Trace metrics are recorded in node-id order.
   */
  private void runIndexBranches(List<PlanNode> branches, Map<String, Object> values,
                                PhysicalPlan phys, ExecutionTrace trace) throws IOException {
    if (branches.isEmpty()) {
      return;
    }
    List<PlanNode> sorted = new ArrayList<PlanNode>(branches);
    Collections.sort(sorted, new Comparator<PlanNode>() {
      @Override
      public int compare(PlanNode a, PlanNode b) {
        return a.id.compareTo(b.id);
      }
    });
    if (sorted.size() == 1 || limits.indexParallelism <= 1) {
      for (PlanNode n : sorted) {
        runOneNode(n, values, phys, trace);
      }
      return;
    }
    int poolSize = Math.min(limits.indexParallelism, sorted.size());
    ExecutorService pool = Executors.newFixedThreadPool(poolSize);
    Map<String, Object> outs = new HashMap<String, Object>();
    Map<String, Long> elapsedById = new HashMap<String, Long>();
    try {
      List<Future<Void>> futures = new ArrayList<Future<Void>>(sorted.size());
      for (final PlanNode n : sorted) {
        futures.add(pool.submit(new Callable<Void>() {
          @Override
          public Void call() throws Exception {
            long nt0 = System.currentTimeMillis();
            activeNode.set(trace.node(n.id));
            Object out;
            try {
              // Index leaves do not read {@code values}; safe to eval concurrently.
              out = eval(n, values, phys, trace);
            } finally {
              activeNode.remove();
            }
            synchronized (outs) {
              outs.put(n.id, out);
              elapsedById.put(n.id, Long.valueOf(System.currentTimeMillis() - nt0));
            }
            return null;
          }
        }));
      }
      for (Future<Void> f : futures) {
        try {
          f.get();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new ExecException(ExecException.Code.FAILED, "index branch interrupted");
        } catch (ExecutionException e) {
          Throwable c = e.getCause() == null ? e : e.getCause();
          if (c instanceof ExecException) {
            throw (ExecException) c;
          }
          if (c instanceof IOException) {
            throw (IOException) c;
          }
          if (c instanceof RuntimeException) {
            throw (RuntimeException) c;
          }
          throw new ExecException(ExecException.Code.FAILED,
              c.getMessage() == null ? c.getClass().getSimpleName() : c.getMessage());
        }
      }
      for (PlanNode n : sorted) {
        Object out = outs.get(n.id);
        values.put(n.id, out);
        accountBytes(estimateBytes(out));
        long elapsed = elapsedById.get(n.id).longValue();
        trace.addNode(n.id, estimateRows(out), estimateBytes(out), elapsed);
        recordOpMetrics(trace, n, out, values);
      }
    } finally {
      pool.shutdownNow();
    }
  }

  @SuppressWarnings("unchecked")
  private void recordOpMetrics(ExecutionTrace trace, PlanNode n, Object out,
                               Map<String, Object> values) {
    long nodeMs = 0L;
    ExecutionTrace.NodeTrace nt = trace.nodes.get(n.id);
    if (nt != null) {
      nodeMs = nt.elapsedMs;
    }
    addPhase(trace, n.op, nodeMs);
    if (out instanceof Set && !((Set<?>) out).isEmpty()
        && ((Set<?>) out).iterator().next() instanceof ChunkRef) {
      int size = ((Set<?>) out).size();
      if (size > limits.maxCandidateChunks) {
        throw new ExecException(ExecException.Code.RESOURCE_EXHAUSTED,
            "candidate chunks " + size + " > max " + limits.maxCandidateChunks);
      }
      long prev = trace.candidate_chunks == null ? 0L : trace.candidate_chunks.longValue();
      if (size > prev) {
        trace.candidate_chunks = Long.valueOf(size);
        trace.candidateChunks = size;
      }
      if (n.op == Op.EXACT_ST_FILTER) {
        trace.filter_passed = Long.valueOf(size);
        trace.matched_chunks = Long.valueOf(size);
      }
      if (n.op == Op.DEDUPLICATE) {
        Object in = values.get(n.inputs.get(0));
        int before = in instanceof Set ? ((Set<?>) in).size() : size;
        trace.deduplicated_chunks = Long.valueOf(Math.max(0, before - size));
      }
    }
    if (out instanceof Map) {
      Map<?, ?> m = (Map<?, ?>) out;
      if (!m.isEmpty() && m.keySet().iterator().next() instanceof ChunkRef) {
        int size = m.size();
        if (size > limits.maxCandidateChunks) {
          throw new ExecException(ExecException.Code.RESOURCE_EXHAUSTED,
              "candidate chunks " + size + " > max " + limits.maxCandidateChunks);
        }
        long prev = trace.candidate_chunks == null ? 0L : trace.candidate_chunks.longValue();
        if (size > prev) {
          trace.candidate_chunks = Long.valueOf(size);
          trace.candidateChunks = size;
        }
        if (n.op == Op.FETCH_TRAJECTORY_CHUNK || n.op == Op.FULL_SCAN_CHUNKS) {
          long prevFetch = trace.fetched_chunks == null ? 0L : trace.fetched_chunks.longValue();
          trace.fetched_chunks = Long.valueOf(prevFetch + size);
        }
      }
    }
  }

  private static void addPhase(ExecutionTrace trace, Op op, long ms) {
    if (op == null || ms <= 0) {
      return;
    }
    switch (op) {
      case TIME_RANGE_SCAN:
      case ZORDER_RANGE_SCAN:
      case EQUALITY_LOOKUP:
      case FULL_SCAN_CHUNKS:
        trace.phase_index_ms = Long.valueOf(
            (trace.phase_index_ms == null ? 0L : trace.phase_index_ms.longValue()) + ms);
        break;
      case INTERSECT:
      case UNION:
      case DEDUPLICATE:
        trace.phase_set_ms = Long.valueOf(
            (trace.phase_set_ms == null ? 0L : trace.phase_set_ms.longValue()) + ms);
        break;
      case FETCH_TRAJECTORY_CHUNK:
        trace.phase_fetch_ms = Long.valueOf(
            (trace.phase_fetch_ms == null ? 0L : trace.phase_fetch_ms.longValue()) + ms);
        break;
      case EXACT_ST_FILTER:
        trace.phase_exact_ms = Long.valueOf(
            (trace.phase_exact_ms == null ? 0L : trace.phase_exact_ms.longValue()) + ms);
        break;
      case BATCH_GET_TRAJECTORY:
        trace.phase_reconstruct_ms = Long.valueOf(
            (trace.phase_reconstruct_ms == null ? 0L : trace.phase_reconstruct_ms.longValue()) + ms);
        break;
      case SIMILARITY:
        trace.phase_sim_ms = Long.valueOf(
            (trace.phase_sim_ms == null ? 0L : trace.phase_sim_ms.longValue()) + ms);
        break;
      case TOP_K:
        trace.phase_topk_ms = Long.valueOf(
            (trace.phase_topk_ms == null ? 0L : trace.phase_topk_ms.longValue()) + ms);
        break;
      default:
        break;
    }
  }

  private Object eval(PlanNode n, Map<String, Object> values, PhysicalPlan phys,
                      ExecutionTrace trace) throws IOException {
    switch (n.op) {
      case TIME_RANGE_SCAN:
      case ZORDER_RANGE_SCAN:
      case EQUALITY_LOOKUP:
        return scanIndex(n, phys);
      case FULL_SCAN_CHUNKS:
        return fullScan(n, phys);
      case INTERSECT:
        return intersect(n, values);
      case UNION:
        return union(n, values);
      case DEDUPLICATE:
        return dedup(n, values);
      case FETCH_TRAJECTORY_CHUNK:
        return fetchChunks(n, values);
      case EXACT_ST_FILTER:
        return exactFilter(n, values);
      case PROJECT_TRAJECTORY_IDS:
        return projectTids(n, values);
      case EXCLUDE_REFERENCE:
        return excludeRef(n, values);
      case BATCH_GET_TRAJECTORY:
        return batchGetTrajectories(n, values);
      case SIMILARITY:
        return similarity(n, values, trace);
      case TOP_K:
        return topK(n, values);
      case RETURN_TRAJECTORY_IDS:
        return returnIds(n, values);
      default:
        throw new ExecException(ExecException.Code.FAILED, "unsupported op: " + n.op);
    }
  }

  @SuppressWarnings("unchecked")
  private Set<ChunkRef> scanIndex(PlanNode n, PhysicalPlan phys) throws IOException {
    Set<ChunkRef> out = new LinkedHashSet<ChunkRef>();
    String expectedVehicle = null;
    if (n.op == Op.EQUALITY_LOOKUP && ir.predicates != null) {
      for (BoundIr.Predicate p : ir.predicates) {
        if (p != null && "vehicle_id".equals(p.field) && "EQ".equals(p.op)) {
          expectedVehicle = p.value;
          break;
        }
      }
    }
    List<ScanTask> tasks = new ArrayList<ScanTask>(phys.tasksForNode(n.id));
    Collections.sort(tasks, new Comparator<ScanTask>() {
      @Override
      public int compare(ScanTask a, ScanTask b) {
        String ra = rsKey(a);
        String rb = rsKey(b);
        int c = ra.compareTo(rb);
        if (c != 0) {
          return c;
        }
        String sa = a.startHex == null ? "" : a.startHex;
        String sb = b.startHex == null ? "" : b.startHex;
        return sa.compareTo(sb);
      }
    });
    final String veh = expectedVehicle;
    if (tasks.isEmpty()) {
      return out;
    }
    if (tasks.size() == 1 || limits.scanParallelism <= 1) {
      for (ScanTask task : tasks) {
        scanOneTask(task, veh, out);
      }
    } else {
      parallelScanTasks(tasks, veh, out);
    }
    // Deterministic membership order for downstream INTERSECT.
    List<ChunkRef> sorted = new ArrayList<ChunkRef>(out);
    Collections.sort(sorted);
    return new LinkedHashSet<ChunkRef>(sorted);
  }

  private String rsKey(ScanTask t) {
    String rs = regionMapping.locate(t.table, t.startBytes());
    if (rs == null || rs.isEmpty()) {
      return "shard:" + t.shard;
    }
    return rs;
  }

  private void scanOneTask(ScanTask task, final String veh, final Set<ChunkRef> out)
      throws IOException {
    checkBudgets();
    noteEmittedRanges(1);
    int caching = task.caching > 0 ? task.caching : 1000;
    final AtomicLong rowsSeen = new AtomicLong(0L);
    kv.scanConsume(task.table, task.startBytes(), task.stopBytes(), null, caching,
        new KvBackend.RowConsumer() {
      @Override
      public void accept(KvBackend.Row row) throws IOException {
        long seen = rowsSeen.incrementAndGet();
        if ((seen & 63L) == 0L) {
          checkBudgets();
        }
        accountBytes(rowWireBytes(row));
        ChunkRef ref = decodePosting(row.key);
        if (ref == null) {
          return;
        }
        if (veh != null) {
          byte[] v = row.columns.get("d:v");
          if (v != null) {
            String got = new String(v, StandardCharsets.UTF_8);
            if (!veh.equals(got)) {
              return;
            }
          }
        }
        synchronized (out) {
          out.add(ref);
        }
      }
    });
    long rows = rowsSeen.get();
    int pages = (int) Math.max(1L, (rows + caching - 1L) / (long) caching);
    noteRpc(pages);
  }

  private void parallelScanTasks(List<ScanTask> tasks, final String veh, final Set<ChunkRef> out)
      throws IOException {
    int poolSize = Math.min(Math.max(1, limits.scanParallelism), tasks.size());
    ExecutorService pool = Executors.newFixedThreadPool(poolSize);
    // Per-RS semaphore approximation: at most scanParallelismPerRs concurrent tasks share an RS key.
    final Map<String, java.util.concurrent.Semaphore> rsSem =
        new HashMap<String, java.util.concurrent.Semaphore>();
    for (ScanTask t : tasks) {
      String rs = rsKey(t);
      if (!rsSem.containsKey(rs)) {
        rsSem.put(rs, new java.util.concurrent.Semaphore(Math.max(1, limits.scanParallelismPerRs)));
      }
    }
    List<Future<Void>> futures = new ArrayList<Future<Void>>();
    try {
      for (final ScanTask task : tasks) {
        final String rs = rsKey(task);
        futures.add(pool.submit(new Callable<Void>() {
          @Override
          public Void call() throws Exception {
            java.util.concurrent.Semaphore sem = rsSem.get(rs);
            sem.acquire();
            try {
              scanOneTask(task, veh, out);
              return null;
            } finally {
              sem.release();
            }
          }
        }));
      }
      for (Future<Void> f : futures) {
        try {
          f.get();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new ExecException(ExecException.Code.FAILED, "scan interrupted");
        } catch (ExecutionException e) {
          Throwable c = e.getCause() == null ? e : e.getCause();
          if (c instanceof ExecException) {
            throw (ExecException) c;
          }
          if (c instanceof IOException) {
            throw (IOException) c;
          }
          if (c instanceof RuntimeException) {
            throw (RuntimeException) c;
          }
          throw new ExecException(ExecException.Code.FAILED,
              c.getMessage() == null ? c.getClass().getSimpleName() : c.getMessage());
        }
      }
    } finally {
      pool.shutdownNow();
    }
  }

  private Map<ChunkRef, List<Chunk.DecodedPoint>> fullScan(PlanNode n, PhysicalPlan phys)
      throws IOException {
    Map<ChunkRef, List<Chunk.DecodedPoint>> out =
        new LinkedHashMap<ChunkRef, List<Chunk.DecodedPoint>>();
    for (ScanTask task : phys.tasksForNode(n.id)) {
      checkBudgets();
      noteEmittedRanges(1);
      int caching = task.caching > 0 ? task.caching : 1000;
      final AtomicLong rowsSeen = new AtomicLong(0L);
      kv.scanConsume(task.table, task.startBytes(), task.stopBytes(), null, caching,
          new KvBackend.RowConsumer() {
        @Override
        public void accept(KvBackend.Row row) throws IOException {
          long seen = rowsSeen.incrementAndGet();
          if ((seen & 63L) == 0L) {
            checkBudgets();
          }
          if (row.key == null || row.key.length != 13) {
            return;
          }
          RowKeyCodec.RawKey rk = RowKeyCodec.decodeRaw(row.key);
          ChunkRef ref = new ChunkRef(rk.tid, rk.chunkId);
          byte[] payload = row.columns.get("d:p");
          if (payload == null) {
            throw new ExecException(ExecException.Code.DATA_INTEGRITY_ERROR,
                "missing d:p for raw " + ref);
          }
          accountBytes(payload.length);
          rememberPayload(ref, payload.length);
          List<Chunk.DecodedPoint> pts = Chunk.decodePayload(payload);
          chunkCache.put(ref, pts);
          out.put(ref, pts);
        }
      });
      long rows = rowsSeen.get();
      int pages = (int) Math.max(1L, (rows + caching - 1L) / (long) caching);
      noteRpc(pages);
    }
    return out;
  }

  private static ChunkRef decodePosting(byte[] key) {
    if (key == null) {
      return null;
    }
    if (key.length == 13) {
      RowKeyCodec.RawKey rk = RowKeyCodec.decodeRaw(key);
      return new ChunkRef(rk.tid, rk.chunkId);
    }
    if (key.length == 21) {
      // time and zorder share tid/chunk layout at offsets 9/17
      return new ChunkRef(
          kart.codec.Bytes.readU64(key, 9),
          kart.codec.Bytes.readU32(key, 17));
    }
    if (key.length == 30) {
      RowKeyCodec.HashKey hk = RowKeyCodec.decodeHash(key);
      return new ChunkRef(hk.tid, hk.chunkId);
    }
    return null;
  }

  @SuppressWarnings("unchecked")
  private Set<ChunkRef> intersect(PlanNode n, Map<String, Object> values) {
    if (n.params == null || !(n.params.get("merge") instanceof String)) {
      throw new ExecException(ExecException.Code.FAILED,
          "INTERSECT missing required merge param (HASH_SET|SORT_MERGE)");
    }
    String merge = (String) n.params.get("merge");
    if (!"HASH_SET".equals(merge) && !"SORT_MERGE".equals(merge)) {
      throw new ExecException(ExecException.Code.FAILED,
          "unsupported INTERSECT merge=" + merge);
    }
    List<Set<ChunkRef>> sets = new ArrayList<Set<ChunkRef>>();
    for (String in : n.inputs) {
      sets.add((Set<ChunkRef>) values.get(in));
    }
    if (sets.isEmpty()) {
      return new LinkedHashSet<ChunkRef>();
    }
    // Prefer intersecting smaller sets first (both HASH_SET and SORT_MERGE).
    Collections.sort(sets, new Comparator<Set<ChunkRef>>() {
      @Override
      public int compare(Set<ChunkRef> a, Set<ChunkRef> b) {
        return Integer.compare(a.size(), b.size());
      }
    });
    if ("SORT_MERGE".equals(merge)) {
      // True multi-way sorted merge intersection (not HashSet.contains).
      List<List<ChunkRef>> sortedLists = new ArrayList<List<ChunkRef>>();
      for (Set<ChunkRef> s : sets) {
        List<ChunkRef> list = new ArrayList<ChunkRef>(s);
        Collections.sort(list);
        sortedLists.add(list);
      }
      List<ChunkRef> acc = sortedLists.get(0);
      for (int i = 1; i < sortedLists.size(); i++) {
        acc = sortedIntersect(acc, sortedLists.get(i));
        if (acc.isEmpty()) {
          break;
        }
      }
      return new LinkedHashSet<ChunkRef>(acc);
    }
    Set<ChunkRef> acc = new LinkedHashSet<ChunkRef>(sets.get(0));
    for (int i = 1; i < sets.size(); i++) {
      acc.retainAll(sets.get(i));
    }
    return acc;
  }

  /** Two-pointer intersection of ascending ChunkRef lists. */
  private static List<ChunkRef> sortedIntersect(List<ChunkRef> a, List<ChunkRef> b) {
    List<ChunkRef> out = new ArrayList<ChunkRef>();
    int i = 0;
    int j = 0;
    while (i < a.size() && j < b.size()) {
      ChunkRef x = a.get(i);
      ChunkRef y = b.get(j);
      int c = x.compareTo(y);
      if (c == 0) {
        out.add(x);
        i++;
        j++;
      } else if (c < 0) {
        i++;
      } else {
        j++;
      }
    }
    return out;
  }

  @SuppressWarnings("unchecked")
  private Set<ChunkRef> union(PlanNode n, Map<String, Object> values) {
    Set<ChunkRef> acc = new LinkedHashSet<ChunkRef>();
    for (String in : n.inputs) {
      acc.addAll((Set<ChunkRef>) values.get(in));
    }
    return acc;
  }

  @SuppressWarnings("unchecked")
  private Set<ChunkRef> dedup(PlanNode n, Map<String, Object> values) {
    return new LinkedHashSet<ChunkRef>((Set<ChunkRef>) values.get(n.inputs.get(0)));
  }

  @SuppressWarnings("unchecked")
  private Map<ChunkRef, List<Chunk.DecodedPoint>> fetchChunks(PlanNode n, Map<String, Object> values)
      throws IOException {
    Set<ChunkRef> refs = (Set<ChunkRef>) values.get(n.inputs.get(0));
    Map<ChunkRef, List<Chunk.DecodedPoint>> out =
        new LinkedHashMap<ChunkRef, List<Chunk.DecodedPoint>>();
    List<ChunkRef> need = new ArrayList<ChunkRef>();
    for (ChunkRef r : refs) {
      List<Chunk.DecodedPoint> cached = chunkCache.get(r);
      if (cached != null) {
        out.put(r, cached);
      } else {
        need.add(r);
      }
    }
    if (need.isEmpty()) {
      return out;
    }
    // Group by RegionServer (or shard) for affinity-aware parallel Gets.
    Map<String, List<ChunkRef>> byRs = new LinkedHashMap<String, List<ChunkRef>>();
    for (ChunkRef r : need) {
      int shard = RowKeyCodec.shardOf(r.tid, layout.shardCount) & 0xFF;
      byte[] key = RowKeyCodec.encodeRaw(shard, r.tid, r.chunkId);
      String rs = regionMapping.locate(layout.tableRaw, key);
      if (rs == null || rs.isEmpty()) {
        rs = "shard:" + shard;
      }
      List<ChunkRef> list = byRs.get(rs);
      if (list == null) {
        list = new ArrayList<ChunkRef>();
        byRs.put(rs, list);
      }
      list.add(r);
    }
    int parallelism = Math.max(1, limits.fetchParallelism);
    if (byRs.size() == 1 || parallelism <= 1) {
      for (List<ChunkRef> group : byRs.values()) {
        fetchChunkBatch(group, out);
      }
      return out;
    }
    ExecutorService pool = Executors.newFixedThreadPool(Math.min(parallelism, byRs.size()));
    List<Future<Void>> futures = new ArrayList<Future<Void>>();
    final Map<ChunkRef, List<Chunk.DecodedPoint>> shared = out;
    try {
      for (final List<ChunkRef> group : byRs.values()) {
        futures.add(pool.submit(new Callable<Void>() {
          @Override
          public Void call() throws Exception {
            Map<ChunkRef, List<Chunk.DecodedPoint>> local =
                new LinkedHashMap<ChunkRef, List<Chunk.DecodedPoint>>();
            fetchChunkBatch(group, local);
            synchronized (shared) {
              shared.putAll(local);
            }
            return null;
          }
        }));
      }
      awaitAll(futures, "fetch");
    } finally {
      pool.shutdownNow();
    }
    return out;
  }

  private void fetchChunkBatch(List<ChunkRef> need, Map<ChunkRef, List<Chunk.DecodedPoint>> out)
      throws IOException {
    int batch = Math.max(1, limits.fetchBatch);
    for (int i = 0; i < need.size(); i += batch) {
      int end = Math.min(need.size(), i + batch);
      List<ChunkRef> slice = need.subList(i, end);
      List<byte[]> keys = new ArrayList<byte[]>(slice.size());
      for (ChunkRef r : slice) {
        int shard = RowKeyCodec.shardOf(r.tid, layout.shardCount) & 0xFF;
        keys.add(RowKeyCodec.encodeRaw(shard, r.tid, r.chunkId));
      }
      noteRpc(1);
      List<KvBackend.Row> rows = kv.get(layout.tableRaw, keys);
      Map<ChunkRef, byte[]> found = new HashMap<ChunkRef, byte[]>();
      for (KvBackend.Row row : rows) {
        RowKeyCodec.RawKey rk = RowKeyCodec.decodeRaw(row.key);
        ChunkRef ref = new ChunkRef(rk.tid, rk.chunkId);
        byte[] payload = row.columns.get("d:p");
        if (payload != null) {
          found.put(ref, payload);
        }
      }
      for (ChunkRef r : slice) {
        byte[] payload = found.get(r);
        if (payload == null) {
          throw new ExecException(ExecException.Code.DATA_INTEGRITY_ERROR,
              "missing raw chunk for " + r);
        }
        accountBytes(payload.length);
        rememberPayload(r, payload.length);
        List<Chunk.DecodedPoint> pts = Chunk.decodePayload(payload);
        synchronized (chunkCache) {
          chunkCache.put(r, pts);
        }
        out.put(r, pts);
      }
    }
  }

  private void awaitAll(List<Future<Void>> futures, String label) throws IOException {
    for (Future<Void> f : futures) {
      try {
        f.get();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new ExecException(ExecException.Code.FAILED, label + " interrupted");
      } catch (ExecutionException e) {
        Throwable c = e.getCause() == null ? e : e.getCause();
        if (c instanceof ExecException) {
          throw (ExecException) c;
        }
        if (c instanceof IOException) {
          throw (IOException) c;
        }
        if (c instanceof RuntimeException) {
          throw (RuntimeException) c;
        }
        throw new ExecException(ExecException.Code.FAILED,
            c.getMessage() == null ? c.getClass().getSimpleName() : c.getMessage());
      }
    }
  }

  @SuppressWarnings("unchecked")
  private Set<ChunkRef> exactFilter(PlanNode n, Map<String, Object> values) throws IOException {
    Object in = values.get(n.inputs.get(0));
    Map<ChunkRef, List<Chunk.DecodedPoint>> batch;
    if (in instanceof Map) {
      batch = (Map<ChunkRef, List<Chunk.DecodedPoint>>) in;
    } else {
      throw new ExecException(ExecException.Code.FAILED,
          "EXACT_ST_FILTER expects ChunkBatch, got " + (in == null ? "null" : in.getClass()));
    }
    boolean needVehicle = false;
    if (ir.predicates != null) {
      for (BoundIr.Predicate p : ir.predicates) {
        if (p != null && "vehicle_id".equals(p.field) && "EQ".equals(p.op)) {
          needVehicle = true;
          break;
        }
      }
    }
    Set<ChunkRef> matched = new LinkedHashSet<ChunkRef>();
    for (Map.Entry<ChunkRef, List<Chunk.DecodedPoint>> e : batch.entrySet()) {
      String vehicleId = null;
      if (needVehicle) {
        vehicleId = loadMeta(e.getKey().tid).vehicleId;
      }
      if (ExactSTFilter.matches(ir, e.getValue(), vehicleId)) {
        matched.add(e.getKey());
      }
    }
    return matched;
  }

  @SuppressWarnings("unchecked")
  private Set<Long> projectTids(PlanNode n, Map<String, Object> values) {
    Set<ChunkRef> matched = (Set<ChunkRef>) values.get(n.inputs.get(0));
    Set<Long> tids = new TreeSet<Long>();
    for (ChunkRef r : matched) {
      tids.add(r.tid);
    }
    return tids;
  }

  @SuppressWarnings("unchecked")
  private Set<Long> excludeRef(PlanNode n, Map<String, Object> values) {
    Set<Long> tids = new TreeSet<Long>((Set<Long>) values.get(n.inputs.get(0)));
    long refTid = ir.similarity != null ? ir.similarity.reference_tid : -1L;
    if (n.params != null && n.params.get("reference_tid") instanceof Number) {
      refTid = ((Number) n.params.get("reference_tid")).longValue();
    }
    tids.remove(refTid);
    return tids;
  }

  @SuppressWarnings("unchecked")
  private Map<Long, List<Chunk.DecodedPoint>> batchGetTrajectories(PlanNode n,
                                                                   Map<String, Object> values)
      throws IOException {
    Set<Long> tids = (Set<Long>) values.get(n.inputs.get(0));
    Map<Long, List<Chunk.DecodedPoint>> out = new LinkedHashMap<Long, List<Chunk.DecodedPoint>>();
    List<Long> tidList = new ArrayList<Long>(tids);
    int parallelism = Math.max(1, limits.fetchParallelism);
    if (tidList.size() <= 1 || parallelism <= 1) {
      for (Long tid : tidList) {
        out.put(tid, loadFullTrajectory(tid));
      }
      return out;
    }
    // Bucket tids by RS of first chunk key.
    Map<String, List<Long>> byRs = new LinkedHashMap<String, List<Long>>();
    for (Long tid : tidList) {
      int shard = RowKeyCodec.shardOf(tid.longValue(), layout.shardCount) & 0xFF;
      byte[] key = RowKeyCodec.encodeRaw(shard, tid.longValue(), 0);
      String rs = regionMapping.locate(layout.tableRaw, key);
      if (rs == null || rs.isEmpty()) {
        rs = "shard:" + shard;
      }
      List<Long> list = byRs.get(rs);
      if (list == null) {
        list = new ArrayList<Long>();
        byRs.put(rs, list);
      }
      list.add(tid);
    }
    ExecutorService pool = Executors.newFixedThreadPool(Math.min(parallelism, byRs.size()));
    List<Future<Void>> futures = new ArrayList<Future<Void>>();
    final Map<Long, List<Chunk.DecodedPoint>> shared = out;
    try {
      for (final List<Long> group : byRs.values()) {
        futures.add(pool.submit(new Callable<Void>() {
          @Override
          public Void call() throws Exception {
            Map<Long, List<Chunk.DecodedPoint>> local =
                new LinkedHashMap<Long, List<Chunk.DecodedPoint>>();
            for (Long tid : group) {
              local.put(tid, loadFullTrajectory(tid.longValue()));
            }
            synchronized (shared) {
              shared.putAll(local);
            }
            return null;
          }
        }));
      }
      awaitAll(futures, "batch_get");
    } finally {
      pool.shutdownNow();
    }
    // Preserve deterministic tid order in output map.
    Map<Long, List<Chunk.DecodedPoint>> ordered = new LinkedHashMap<Long, List<Chunk.DecodedPoint>>();
    for (Long tid : tidList) {
      ordered.put(tid, out.get(tid));
    }
    return ordered;
  }

  private List<Chunk.DecodedPoint> loadFullTrajectory(long tid) throws IOException {
    MetaRow meta = loadMeta(tid);
    List<Chunk.DecodedPoint> all = new ArrayList<Chunk.DecodedPoint>();
    int shard = RowKeyCodec.shardOf(tid, layout.shardCount) & 0xFF;
    List<ChunkRef> need = new ArrayList<ChunkRef>();
    for (int c = 0; c < meta.chunkCount; c++) {
      ChunkRef ref = new ChunkRef(tid, c);
      List<Chunk.DecodedPoint> cached = chunkCache.get(ref);
      if (cached != null) {
        all.addAll(cached);
      } else {
        need.add(ref);
      }
    }
    if (!need.isEmpty()) {
      int batch = Math.max(1, limits.fetchBatch);
      for (int i = 0; i < need.size(); i += batch) {
        int end = Math.min(need.size(), i + batch);
        List<ChunkRef> slice = need.subList(i, end);
        List<byte[]> keys = new ArrayList<byte[]>(slice.size());
        for (ChunkRef r : slice) {
          keys.add(RowKeyCodec.encodeRaw(shard, r.tid, r.chunkId));
        }
        noteRpc(1);
        List<KvBackend.Row> rows = kv.get(layout.tableRaw, keys);
        Set<ChunkRef> got = new HashSet<ChunkRef>();
        for (KvBackend.Row row : rows) {
          RowKeyCodec.RawKey rk = RowKeyCodec.decodeRaw(row.key);
          ChunkRef ref = new ChunkRef(rk.tid, rk.chunkId);
          byte[] payload = row.columns.get("d:p");
          if (payload == null) {
            throw new ExecException(ExecException.Code.DATA_INTEGRITY_ERROR,
                "missing d:p for " + ref);
          }
          accountBytes(payload.length);
          rememberPayload(ref, payload.length);
          List<Chunk.DecodedPoint> pts = Chunk.decodePayload(payload);
          chunkCache.put(ref, pts);
          got.add(ref);
        }
        for (ChunkRef r : slice) {
          if (!got.contains(r)) {
            throw new ExecException(ExecException.Code.DATA_INTEGRITY_ERROR,
                "missing raw chunk for " + r);
          }
        }
      }
      // reassemble in chunk order
      all.clear();
      for (int c = 0; c < meta.chunkCount; c++) {
        ChunkRef ref = new ChunkRef(tid, c);
        List<Chunk.DecodedPoint> pts = chunkCache.get(ref);
        if (pts == null) {
          throw new ExecException(ExecException.Code.DATA_INTEGRITY_ERROR,
              "missing raw chunk for " + ref);
        }
        all.addAll(pts);
      }
    }
    return all;
  }

  @SuppressWarnings("unchecked")
  private List<QueryResult.Scored> similarity(PlanNode n, Map<String, Object> values,
                                              ExecutionTrace trace) throws IOException {
    Map<Long, List<Chunk.DecodedPoint>> trajs =
        (Map<Long, List<Chunk.DecodedPoint>>) values.get(n.inputs.get(0));
    long refTid = ir.similarity.reference_tid;
    String metric = resolveMetric(n);
    List<Chunk.DecodedPoint> refPts = loadFullTrajectory(refTid);
    Dtw.Point[] refArr = toDtw(refPts);
    List<QueryResult.Scored> scored = new ArrayList<QueryResult.Scored>();
    long cells = 0;
    for (Map.Entry<Long, List<Chunk.DecodedPoint>> e : trajs.entrySet()) {
      checkBudgets();
      Dtw.Point[] cand = toDtw(e.getValue());
      long pairCells = (long) cand.length * (long) refArr.length;
      if (TrajectorySimilarity.HAUSDORFF.equals(metric)) {
        pairCells *= 2L;
      }
      if (cells + pairCells > limits.maxDtwCells) {
        throw new ExecException(ExecException.Code.RESOURCE_EXHAUSTED,
            "similarity cells would exceed " + limits.maxDtwCells);
      }
      Dtw.Result d = TrajectorySimilarity.distance(metric, cand, refArr);
      cells += d.cells;
      if (cells > limits.maxDtwCells) {
        throw new ExecException(ExecException.Code.RESOURCE_EXHAUSTED,
            "similarity cells " + cells + " > max " + limits.maxDtwCells);
      }
      MetaRow meta = loadMeta(e.getKey());
      scored.add(new QueryResult.Scored(e.getKey(), meta.extId, d.distance));
    }
    trace.dtw_cells = Long.valueOf(cells);
    trace.dtwCells = cells;
    return scored;
  }

  private String resolveMetric(PlanNode n) {
    if (n != null && n.params != null && n.params.get("metric") instanceof String) {
      String m = (String) n.params.get("metric");
      if (m != null && !m.isEmpty()) {
        if (!TrajectorySimilarity.isSupported(m)) {
          throw new ExecException(ExecException.Code.FAILED,
              "unsupported similarity metric=" + m);
        }
        return m;
      }
    }
    if (ir.similarity != null && ir.similarity.metric != null && !ir.similarity.metric.isEmpty()) {
      String m = ir.similarity.metric;
      if (!TrajectorySimilarity.isSupported(m)) {
        throw new ExecException(ExecException.Code.FAILED,
            "unsupported similarity metric=" + m);
      }
      return m;
    }
    throw new ExecException(ExecException.Code.FAILED,
        "similarity.metric required (DTW|FRECHET|HAUSDORFF); not defaulted");
  }

  @SuppressWarnings("unchecked")
  private List<QueryResult.Scored> topK(PlanNode n, Map<String, Object> values) {
    List<QueryResult.Scored> scored =
        new ArrayList<QueryResult.Scored>((List<QueryResult.Scored>) values.get(n.inputs.get(0)));
    Collections.sort(scored, new Comparator<QueryResult.Scored>() {
      @Override
      public int compare(QueryResult.Scored a, QueryResult.Scored b) {
        int c = Double.compare(a.distance, b.distance);
        if (c != 0) {
          return c;
        }
        return Long.compare(a.tid, b.tid);
      }
    });
    int k = ir.result != null && ir.result.k != null ? ir.result.k : scored.size();
    if (n.params != null && n.params.get("k") instanceof Number) {
      k = ((Number) n.params.get("k")).intValue();
    }
    if (k < scored.size()) {
      scored = new ArrayList<QueryResult.Scored>(scored.subList(0, k));
    }
    return scored;
  }

  @SuppressWarnings("unchecked")
  private List<String> returnIds(PlanNode n, Map<String, Object> values) throws IOException {
    Set<Long> tids = (Set<Long>) values.get(n.inputs.get(0));
    List<Long> sorted = new ArrayList<Long>(tids);
    Collections.sort(sorted);
    List<String> ids = new ArrayList<String>(sorted.size());
    for (Long tid : sorted) {
      ids.add(loadMeta(tid).extId);
    }
    return ids;
  }

  private void applyRoot(QueryResult result, Object rootVal) {
    if (rootVal instanceof List) {
      List<?> list = (List<?>) rootVal;
      boolean topKMode = ir.result != null && "TOP_K".equals(ir.result.mode);
      if (topKMode || (!list.isEmpty() && list.get(0) instanceof QueryResult.Scored)) {
        @SuppressWarnings("unchecked")
        List<QueryResult.Scored> scored = list.isEmpty()
            ? new ArrayList<QueryResult.Scored>()
            : (List<QueryResult.Scored>) list;
        result.topK = scored;
        result.trajectoryIds = new ArrayList<String>();
        for (QueryResult.Scored s : scored) {
          result.trajectoryIds.add(s.trajectoryId);
        }
      } else {
        @SuppressWarnings("unchecked")
        List<String> ids = (List<String>) list;
        result.trajectoryIds = new ArrayList<String>(ids);
      }
    } else {
      throw new ExecException(ExecException.Code.FAILED,
          "unexpected root value type: " + (rootVal == null ? "null" : rootVal.getClass()));
    }
  }

  private MetaRow loadMeta(long tid) throws IOException {
    MetaRow cached = metaCache.get(tid);
    if (cached != null) {
      return cached;
    }
    int shard = RowKeyCodec.shardOf(tid, layout.shardCount) & 0xFF;
    Map<String, byte[]> cols = kv.get(layout.tableMeta, RowKeyCodec.encodeMeta(shard, tid));
    noteRpc(1);
    if (cols == null || cols.isEmpty()) {
      throw new ExecException(ExecException.Code.DATA_INTEGRITY_ERROR, "missing meta for tid=" + tid);
    }
    MetaRow m = new MetaRow();
    byte[] ext = cols.get("d:ext");
    byte[] veh = cols.get("d:veh");
    byte[] cc = cols.get("d:cc");
    m.extId = ext == null ? String.valueOf(tid) : new String(ext, StandardCharsets.UTF_8);
    m.vehicleId = veh == null ? null : new String(veh, StandardCharsets.UTF_8);
    m.chunkCount = cc == null ? 0 : ByteBuffer.wrap(cc).getInt();
    metaCache.put(tid, m);
    return m;
  }

  private static Dtw.Point[] toDtw(List<Chunk.DecodedPoint> pts) {
    Dtw.Point[] arr = new Dtw.Point[pts.size()];
    for (int i = 0; i < pts.size(); i++) {
      Chunk.DecodedPoint p = pts.get(i);
      arr[i] = new Dtw.Point(p.x, p.y);
    }
    return arr;
  }

  private static List<PlanNode> topoOrder(PlanEnvelope env) {
    Map<String, PlanNode> byId = new LinkedHashMap<String, PlanNode>();
    Map<String, Integer> indeg = new HashMap<String, Integer>();
    Map<String, List<String>> outs = new HashMap<String, List<String>>();
    for (PlanNode n : env.nodes) {
      byId.put(n.id, n);
      indeg.put(n.id, 0);
      outs.put(n.id, new ArrayList<String>());
    }
    for (PlanNode n : env.nodes) {
      for (String in : n.inputs) {
        outs.get(in).add(n.id);
        indeg.put(n.id, indeg.get(n.id) + 1);
      }
    }
    Queue<String> q = new ArrayDeque<String>();
    for (Map.Entry<String, Integer> e : indeg.entrySet()) {
      if (e.getValue() == 0) {
        q.add(e.getKey());
      }
    }
    List<PlanNode> order = new ArrayList<PlanNode>();
    while (!q.isEmpty()) {
      String id = q.remove();
      order.add(byId.get(id));
      for (String o : outs.get(id)) {
        int d = indeg.get(o) - 1;
        indeg.put(o, d);
        if (d == 0) {
          q.add(o);
        }
      }
    }
    if (order.size() != env.nodes.size()) {
      throw new ExecException(ExecException.Code.FAILED, "plan graph has a cycle");
    }
    return order;
  }

  private static long estimateRows(Object out) {
    if (out instanceof Set) {
      return ((Set<?>) out).size();
    }
    if (out instanceof Map) {
      return ((Map<?, ?>) out).size();
    }
    if (out instanceof List) {
      return ((List<?>) out).size();
    }
    return 0;
  }

  private long estimateChunkMapEntry(ChunkRef ref, Object value) {
    Long wire = payloadBytesByRef.get(ref);
    if (wire != null && wire.longValue() > 0) {
      return wire.longValue();
    }
    if (value instanceof List) {
      return ((List<?>) value).size() * 24L;
    }
    return 4096L;
  }

  private long estimateBytes(Object out) {
    if (out == null) {
      return 0L;
    }
    if (out instanceof Map) {
      Map<?, ?> m = (Map<?, ?>) out;
      if (m.isEmpty()) {
        return 0L;
      }
      Object firstKey = m.keySet().iterator().next();
      if (firstKey instanceof ChunkRef) {
        long sum = 0L;
        for (Map.Entry<?, ?> e : m.entrySet()) {
          sum += estimateChunkMapEntry((ChunkRef) e.getKey(), e.getValue());
        }
        return sum;
      }
      if (firstKey instanceof Long) {
        long sum = 0L;
        for (Object v : m.values()) {
          if (v instanceof List) {
            sum += ((List<?>) v).size() * 24L;
          }
        }
        return sum;
      }
      return m.size() * 64L;
    }
    if (out instanceof Set) {
      return ((Set<?>) out).size() * 48L;
    }
    if (out instanceof List) {
      return ((List<?>) out).size() * 32L;
    }
    return 0L;
  }

  private static final class MetaRow {
    String extId;
    String vehicleId;
    int chunkCount;
  }
}
