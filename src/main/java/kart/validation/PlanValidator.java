package kart.validation;

import kart.codec.Bytes;
import kart.codec.RowKeyCodec;
import kart.codec.TimeBucket;
import kart.codec.VehicleHash;
import kart.codec.ZOrder;
import kart.compile.LayoutContext;
import kart.compile.PhysicalPlan;
import kart.compile.ScanTask;
import kart.geo.Rect;
import kart.ir.BoundIr;
import kart.plan.DataType;
import kart.plan.Op;
import kart.plan.PlanEnvelope;
import kart.plan.PlanNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Validates a candidate plan (design.md §9): StructureCheck, SemanticCheck,
 * CoverageCheck, PhysicalSafetyCheck. Any externally supplied "safe" flags are
 * ignored — the only proof of safety is the returned SafePlanHandle.
 */
public final class PlanValidator {

  /** Hard cap on emitted scan tasks (PhysicalSafetyCheck). */
  public static final int MAX_SCAN_TASKS = 100_000;

  private final LayoutContext layout;

  public PlanValidator(LayoutContext layout) {
    this.layout = layout;
  }

  /**
   * Runs all checks in order, filling the report. Returns a SafePlanHandle
   * only if every check passed.
   */
  public Optional<SafePlanHandle> validate(PlanEnvelope env, BoundIr ir,
                                           PhysicalPlan phys, ValidationReport report) {
    boolean ok = structureCheck(env, report);
    if (ok) {
      ok = semanticCheck(env, ir, report);
    }
    if (ok) {
      ok = coverageCheck(env, ir, phys, report);
    }
    if (ok) {
      ok = physicalSafetyCheck(env, phys, report);
    }
    if (!ok) {
      return Optional.empty();
    }
    return Optional.of(new SafePlanHandle(env, phys, report.hashHex()));
  }

  // ---------------------------------------------------------------- structure

  boolean structureCheck(PlanEnvelope env, ValidationReport report) {
    final String C = "StructureCheck";
    if (env.plan_id == null || env.query_id == null || env.manifest_id == null
        || env.root == null || env.nodes == null || env.nodes.isEmpty()) {
      report.fail(C, "missing required envelope fields");
      return false;
    }
    Map<String, PlanNode> byId = new HashMap<String, PlanNode>();
    for (PlanNode n : env.nodes) {
      if (n.id == null || n.op == null || n.inputs == null) {
        report.fail(C, "node missing id/op/inputs");
        return false;
      }
      if (byId.put(n.id, n) != null) {
        report.fail(C, "duplicate node id: " + n.id);
        return false;
      }
    }
    if (!byId.containsKey(env.root)) {
      report.fail(C, "root not found: " + env.root);
      return false;
    }
    // input references exist; arity
    for (PlanNode n : env.nodes) {
      if (n.inputs.size() < n.op.minInputs() || n.inputs.size() > n.op.maxInputs()) {
        report.fail(C, "node " + n.id + " (" + n.op + ") has bad arity " + n.inputs.size());
        return false;
      }
      for (String in : n.inputs) {
        if (!byId.containsKey(in)) {
          report.fail(C, "node " + n.id + " references missing input " + in);
          return false;
        }
      }
    }
    // acyclicity via Kahn's algorithm
    Map<String, Integer> indeg = new HashMap<String, Integer>();
    Map<String, List<String>> consumers = new HashMap<String, List<String>>();
    for (PlanNode n : env.nodes) {
      indeg.put(n.id, Integer.valueOf(n.inputs.size()));
      for (String in : n.inputs) {
        List<String> c = consumers.get(in);
        if (c == null) {
          c = new ArrayList<String>();
          consumers.put(in, c);
        }
        c.add(n.id);
      }
    }
    Deque<String> queue = new ArrayDeque<String>();
    for (Map.Entry<String, Integer> e : indeg.entrySet()) {
      if (e.getValue().intValue() == 0) {
        queue.add(e.getKey());
      }
    }
    int visited = 0;
    while (!queue.isEmpty()) {
      String id = queue.removeFirst();
      visited++;
      List<String> cons = consumers.get(id);
      if (cons != null) {
        for (String c : cons) {
          int d = indeg.get(c).intValue() - 1;
          indeg.put(c, Integer.valueOf(d));
          if (d == 0) {
            queue.add(c);
          }
        }
      }
    }
    if (visited != env.nodes.size()) {
      report.fail(C, "plan graph has a cycle");
      return false;
    }
    // single root: exactly one node with no consumer, and it is env.root
    List<String> sinks = new ArrayList<String>();
    for (PlanNode n : env.nodes) {
      if (!consumers.containsKey(n.id)) {
        sinks.add(n.id);
      }
    }
    if (sinks.size() != 1 || !sinks.get(0).equals(env.root)) {
      report.fail(C, "plan must have a single root; sinks=" + sinks + " root=" + env.root);
      return false;
    }
    // input data types match operator signatures
    for (PlanNode n : env.nodes) {
      DataType expected = n.op.inputType();
      for (String in : n.inputs) {
        DataType got = byId.get(in).op.outputType();
        if (got != expected) {
          report.fail(C, "node " + n.id + " (" + n.op + ") expects " + expected
              + " but input " + in + " produces " + got);
          return false;
        }
      }
    }
    report.pass(C, "schema/topology/types ok, " + env.nodes.size() + " nodes");
    return true;
  }

  // ----------------------------------------------------------------- semantic

  boolean semanticCheck(PlanEnvelope env, BoundIr ir, ValidationReport report) {
    final String C = "SemanticCheck";
    Map<String, PlanNode> byId = new HashMap<String, PlanNode>();
    for (PlanNode n : env.nodes) {
      byId.put(n.id, n);
    }
    // EXACT_ST_FILTER must reference all IR predicates
    Set<String> required = new HashSet<String>();
    if (ir.temporal != null) {
      required.add("/temporal");
    }
    if (ir.spatial != null) {
      required.add("/spatial");
    }
    if (ir.predicates != null) {
      for (int i = 0; i < ir.predicates.size(); i++) {
        required.add("/predicates/" + i);
      }
    }
    PlanNode filter = firstOf(env, Op.EXACT_ST_FILTER);
    if (!required.isEmpty()) {
      if (filter == null) {
        report.fail(C, "IR has predicates but plan has no EXACT_ST_FILTER");
        return false;
      }
      Object refsObj = filter.params == null ? null : filter.params.get("predicate_refs");
      Set<String> refs = new HashSet<String>();
      if (refsObj instanceof List) {
        for (Object o : (List<?>) refsObj) {
          refs.add(String.valueOf(o));
        }
      }
      if (!refs.containsAll(required)) {
        Set<String> missing = new HashSet<String>(required);
        missing.removeAll(refs);
        report.fail(C, "EXACT_ST_FILTER missing predicate_refs: " + missing);
        return false;
      }
    }
    boolean topK = ir.result != null && "TOP_K".equals(ir.result.mode);
    if (topK) {
      // chain: TOP_K ← SIMILARITY ← BATCH_GET_TRAJECTORY ← [EXCLUDE_REFERENCE] ← PROJECT_TRAJECTORY_IDS
      PlanNode rootNode = byId.get(env.root);
      if (rootNode == null || rootNode.op != Op.TOP_K) {
        report.fail(C, "TOP_K mode requires TOP_K root, got " + (rootNode == null ? "null" : rootNode.op));
        return false;
      }
      PlanNode sim = soleInput(rootNode, byId);
      if (sim == null || sim.op != Op.SIMILARITY) {
        report.fail(C, "TOP_K input must be SIMILARITY");
        return false;
      }
      PlanNode batch = soleInput(sim, byId);
      if (batch == null || batch.op != Op.BATCH_GET_TRAJECTORY) {
        report.fail(C, "SIMILARITY input must be BATCH_GET_TRAJECTORY");
        return false;
      }
      PlanNode below = soleInput(batch, byId);
      boolean requireExclude = ir.similarity != null && ir.similarity.exclude_reference;
      if (requireExclude) {
        if (below == null || below.op != Op.EXCLUDE_REFERENCE) {
          report.fail(C, "IR requires exclude_reference but plan lacks EXCLUDE_REFERENCE before BATCH_GET_TRAJECTORY");
          return false;
        }
        below = soleInput(below, byId);
      }
      if (below == null || below.op != Op.PROJECT_TRAJECTORY_IDS) {
        report.fail(C, "TOP_K chain must terminate in PROJECT_TRAJECTORY_IDS");
        return false;
      }
    } else {
      // TRAJECTORY_IDS mode: no similarity operators allowed
      for (PlanNode n : env.nodes) {
        if (n.op == Op.SIMILARITY || n.op == Op.TOP_K
            || n.op == Op.BATCH_GET_TRAJECTORY || n.op == Op.EXCLUDE_REFERENCE) {
          report.fail(C, "TRAJECTORY_IDS mode forbids " + n.op + " (node " + n.id + ")");
          return false;
        }
      }
    }
    // INTERSECT inputs must all be CHUNK_REF_SET (also enforced in structure; explicit per spec)
    for (PlanNode n : env.nodes) {
      if (n.op == Op.INTERSECT) {
        for (String in : n.inputs) {
          if (byId.get(in).op.outputType() != DataType.CHUNK_REF_SET) {
            report.fail(C, "INTERSECT input " + in + " is not a ChunkRefSet");
            return false;
          }
        }
      }
    }
    report.pass(C, "semantic constraints ok (mode=" + (topK ? "TOP_K" : "TRAJECTORY_IDS") + ")");
    return true;
  }

  // ----------------------------------------------------------------- coverage

  boolean coverageCheck(PlanEnvelope env, BoundIr ir, PhysicalPlan phys, ValidationReport report) {
    final String C = "CoverageCheck";
    for (PlanNode n : env.nodes) {
      if (n.op == null || !n.op.isAccess()) {
        continue;
      }
      List<ScanTask> tasks = phys.tasksForNode(n.id);
      if (tasks.isEmpty() && n.op != Op.FULL_SCAN_CHUNKS) {
        // Empty is legitimate only if the predicate domain is empty (e.g. empty time range)
        if (n.op == Op.TIME_RANGE_SCAN && ir.temporal != null
            && ir.temporal.end_ms <= ir.temporal.start_ms) {
          continue;
        }
        report.fail(C, "access node " + n.id + " (" + n.op + ") has no scan tasks");
        return false;
      }
      switch (n.op) {
        case TIME_RANGE_SCAN: {
          if (ir.temporal == null) {
            report.fail(C, "TIME_RANGE_SCAN without IR temporal");
            return false;
          }
          long[] buckets = TimeBucket.bucketsCovering(
              ir.temporal.start_ms, ir.temporal.end_ms, layout.epochMs, layout.bucketMs);
          for (int shard = 0; shard < layout.shardCount; shard++) {
            for (long b : buckets) {
              byte[] probe = RowKeyCodec.encodeTime(shard, b, 0, 0);
              if (!coveredBy(probe, tasks, layout.tableTime)) {
                report.fail(C, "time bucket " + b + " shard " + shard + " not covered by node " + n.id);
                return false;
              }
            }
          }
          break;
        }
        case ZORDER_RANGE_SCAN: {
          if (ir.spatial == null) {
            report.fail(C, "ZORDER_RANGE_SCAN without IR spatial");
            return false;
          }
          Rect d = layout.domain;
          ZOrder.CellRect cells = ZOrder.metersToCells(
              ir.spatial.min_x, ir.spatial.min_y, ir.spatial.max_x, ir.spatial.max_y,
              d.minX, d.minY, d.maxX, d.maxY, layout.zorderLevel);
          for (int cx = cells.cxMin; cx <= cells.cxMax; cx++) {
            for (int cy = cells.cyMin; cy <= cells.cyMax; cy++) {
              long z = ZOrder.interleave(cx, cy, layout.zorderLevel);
              for (int shard = 0; shard < layout.shardCount; shard++) {
                byte[] probe = RowKeyCodec.encodeZorder(shard, z, 0, 0);
                if (!coveredBy(probe, tasks, layout.tableZorder)) {
                  report.fail(C, "z-cell " + z + " shard " + shard + " not covered by node " + n.id);
                  return false;
                }
              }
            }
          }
          break;
        }
        case EQUALITY_LOOKUP: {
          BoundIr.Predicate p;
          try {
            p = kart.compile.QueryCompiler.resolvePredicate(ir, n);
          } catch (IllegalArgumentException e) {
            report.fail(C, e.getMessage());
            return false;
          }
          byte[] hash = VehicleHash.hash128(p.value);
          for (int shard = 0; shard < layout.shardCount; shard++) {
            byte[] expectedPrefix = Arrays.copyOf(
                RowKeyCodec.encodeHash(shard, RowKeyCodec.HASH_FIELD_VEHICLE, hash, 0, 0), 18);
            boolean found = false;
            for (ScanTask t : tasks) {
              if (layout.tableHash.equals(t.table) && t.shard == shard
                  && Arrays.equals(t.startBytes(), expectedPrefix)) {
                found = true;
                break;
              }
            }
            if (!found) {
              report.fail(C, "hash prefix scan missing for shard " + shard + " node " + n.id);
              return false;
            }
          }
          break;
        }
        case FULL_SCAN_CHUNKS: {
          for (int shard = 0; shard < layout.shardCount; shard++) {
            byte[] probe = Bytes.u8(shard);
            if (!coveredBy(probe, tasks, layout.tableRaw)) {
              report.fail(C, "full scan missing shard " + shard + " node " + n.id);
              return false;
            }
          }
          break;
        }
        default:
          break;
      }
    }
    report.pass(C, "all access nodes cover their predicates");
    return true;
  }

  private static boolean coveredBy(byte[] probe, List<ScanTask> tasks, String table) {
    for (ScanTask t : tasks) {
      if (!table.equals(t.table)) {
        continue;
      }
      byte[] start = t.startBytes();
      byte[] stop = t.stopBytes();
      if (Bytes.compareUnsigned(start, probe) <= 0 && Bytes.compareUnsigned(probe, stop) < 0) {
        return true;
      }
    }
    return false;
  }

  // ---------------------------------------------------------- physical safety

  boolean physicalSafetyCheck(PlanEnvelope env, PhysicalPlan phys, ValidationReport report) {
    final String C = "PhysicalSafetyCheck";
    if (phys.scanTasks.size() > MAX_SCAN_TASKS) {
      report.fail(C, "too many scan tasks: " + phys.scanTasks.size());
      return false;
    }
    for (ScanTask t : phys.scanTasks) {
      byte[] start = t.startBytes();
      byte[] stop = t.stopBytes();
      if (start == null || stop == null || start.length == 0 || stop.length == 0) {
        report.fail(C, "scan task with empty start/stop (node " + t.sourceNodeId + ")");
        return false;
      }
      if (Bytes.compareUnsigned(start, stop) >= 0) {
        report.fail(C, "scan start >= stop for node " + t.sourceNodeId
            + " start=" + t.startHex + " stop=" + t.stopHex);
        return false;
      }
      if ((start[0] & 0xFF) != t.shard) {
        report.fail(C, "scan start first byte " + (start[0] & 0xFF)
            + " != shard " + t.shard + " (node " + t.sourceNodeId + ")");
        return false;
      }
      if (!layout.knownTable(t.table)) {
        report.fail(C, "unknown table in scan task: " + t.table);
        return false;
      }
    }
    report.pass(C, phys.scanTasks.size() + " scan tasks safe");
    return true;
  }

  // ------------------------------------------------------------------ helpers

  private static PlanNode firstOf(PlanEnvelope env, Op op) {
    for (PlanNode n : env.nodes) {
      if (n.op == op) {
        return n;
      }
    }
    return null;
  }

  private static PlanNode soleInput(PlanNode n, Map<String, PlanNode> byId) {
    if (n.inputs == null || n.inputs.size() != 1) {
      return null;
    }
    return byId.get(n.inputs.get(0));
  }
}
