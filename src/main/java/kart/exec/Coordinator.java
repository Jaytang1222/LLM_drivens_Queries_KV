package kart.exec;

import kart.codec.RowKeyCodec;
import kart.compile.LayoutContext;
import kart.compile.PhysicalPlan;
import kart.compile.ScanTask;
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

/**
 * Topological executor for a validated SafePlanHandle against a KvBackend.
 */
public final class Coordinator {

  private final KvBackend kv;
  private final BoundIr ir;
  private final LayoutContext layout;
  private final ExecLimits limits;

  /** Reused raw chunk payloads across FETCH / BATCH_GET. */
  private final Map<ChunkRef, List<Chunk.DecodedPoint>> chunkCache =
      new HashMap<ChunkRef, List<Chunk.DecodedPoint>>();
  private final Map<Long, MetaRow> metaCache = new HashMap<Long, MetaRow>();

  public Coordinator(KvBackend kv, BoundIr ir, LayoutContext layout, ExecLimits limits) {
    this.kv = kv;
    this.ir = ir;
    this.layout = layout;
    this.limits = limits == null ? ExecLimits.defaults() : limits;
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
    // Unavailable client metrics stay null (NFR-3).
    trace.llm_calls = null;
    trace.llm_tokens = null;
    trace.rpc_count = null;

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
    trace.elapsedMs = System.currentTimeMillis() - t0;
    trace.finalizeMetrics();
    return result;
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
    long nt0 = System.currentTimeMillis();
    Object out = eval(n, values, phys, trace);
    values.put(n.id, out);
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
            // Index leaves do not read {@code values}; safe to eval concurrently.
            Object out = eval(n, values, phys, trace);
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
    for (ScanTask task : phys.tasksForNode(n.id)) {
      final String veh = expectedVehicle;
      kv.scanConsume(task.table, task.startBytes(), task.stopBytes(), null, new KvBackend.RowConsumer() {
        @Override
        public void accept(KvBackend.Row row) throws IOException {
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
          out.add(ref);
        }
      });
    }
    return out;
  }

  private Map<ChunkRef, List<Chunk.DecodedPoint>> fullScan(PlanNode n, PhysicalPlan phys)
      throws IOException {
    Map<ChunkRef, List<Chunk.DecodedPoint>> out =
        new LinkedHashMap<ChunkRef, List<Chunk.DecodedPoint>>();
    for (ScanTask task : phys.tasksForNode(n.id)) {
      kv.scanConsume(task.table, task.startBytes(), task.stopBytes(), null, new KvBackend.RowConsumer() {
        @Override
        public void accept(KvBackend.Row row) throws IOException {
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
          List<Chunk.DecodedPoint> pts = Chunk.decodePayload(payload);
          chunkCache.put(ref, pts);
          out.put(ref, pts);
        }
      });
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
    Set<ChunkRef> acc = null;
    for (String in : n.inputs) {
      Set<ChunkRef> s = (Set<ChunkRef>) values.get(in);
      if (acc == null) {
        acc = new LinkedHashSet<ChunkRef>(s);
      } else {
        acc.retainAll(s);
      }
    }
    return acc == null ? new LinkedHashSet<ChunkRef>() : acc;
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
    int batch = Math.max(1, limits.fetchBatch);
    for (int i = 0; i < need.size(); i += batch) {
      int end = Math.min(need.size(), i + batch);
      List<byte[]> keys = new ArrayList<byte[]>(end - i);
      List<ChunkRef> slice = need.subList(i, end);
      for (ChunkRef r : slice) {
        int shard = RowKeyCodec.shardOf(r.tid, layout.shardCount) & 0xFF;
        keys.add(RowKeyCodec.encodeRaw(shard, r.tid, r.chunkId));
      }
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
        List<Chunk.DecodedPoint> pts = Chunk.decodePayload(payload);
        chunkCache.put(r, pts);
        out.put(r, pts);
      }
    }
    return out;
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
    for (Long tid : tids) {
      out.put(tid, loadFullTrajectory(tid));
    }
    return out;
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
    List<Chunk.DecodedPoint> refPts = loadFullTrajectory(refTid);
    Dtw.Point[] refArr = toDtw(refPts);
    List<QueryResult.Scored> scored = new ArrayList<QueryResult.Scored>();
    long cells = 0;
    for (Map.Entry<Long, List<Chunk.DecodedPoint>> e : trajs.entrySet()) {
      Dtw.Point[] cand = toDtw(e.getValue());
      long pairCells = (long) cand.length * (long) refArr.length;
      if (cells + pairCells > limits.maxDtwCells) {
        throw new ExecException(ExecException.Code.RESOURCE_EXHAUSTED,
            "dtw cells would exceed " + limits.maxDtwCells);
      }
      Dtw.Result d = Dtw.distance(cand, refArr);
      cells += d.cells;
      if (cells > limits.maxDtwCells) {
        throw new ExecException(ExecException.Code.RESOURCE_EXHAUSTED,
            "dtw cells " + cells + " > max " + limits.maxDtwCells);
      }
      MetaRow meta = loadMeta(e.getKey());
      scored.add(new QueryResult.Scored(e.getKey(), meta.extId, d.distance));
    }
    trace.dtw_cells = Long.valueOf(cells);
    trace.dtwCells = cells;
    return scored;
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

  private static long estimateBytes(Object out) {
    // coarse estimate for tracing only
    return estimateRows(out) * 32L;
  }

  private static final class MetaRow {
    String extId;
    String vehicleId;
    int chunkCount;
  }
}
