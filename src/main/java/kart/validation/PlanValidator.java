package kart.validation;

import kart.codec.Bytes;
import kart.codec.PrefixSuccessor;
import kart.codec.RowKeyCodec;
import kart.codec.TimeBucket;
import kart.codec.VehicleHash;
import kart.codec.ZOrder;
import kart.compile.LayoutContext;
import kart.compile.PhysicalPlan;
import kart.compile.QueryCompiler;
import kart.compile.ScanTask;
import kart.geo.Rect;
import kart.ir.BoundIr;
import kart.ir.IrSchemaValidator;
import kart.plan.DataType;
import kart.plan.Op;
import kart.plan.PlanEnvelope;
import kart.plan.PlanNode;
import com.networknt.schema.ValidationMessage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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
 * Validates a candidate plan (design.md §9): StructureCheck (incl. plan.schema.json),
 * SemanticCheck, CoverageCheck (+ CoverageCertificate), PhysicalSafetyCheck.
 * External {@code safe=true} is ignored — only SafePlanHandle proves safety.
 */
public final class PlanValidator {

  /** Hard cap on emitted scan tasks (PhysicalSafetyCheck). */
  public static final int MAX_SCAN_TASKS = 100_000;

  private final LayoutContext layout;
  private final IrSchemaValidator schemas;

  public PlanValidator(LayoutContext layout) {
    this(layout, loadSchemasQuietly());
  }

  public PlanValidator(LayoutContext layout, IrSchemaValidator schemas) {
    this.layout = layout;
    this.schemas = schemas;
  }

  private static IrSchemaValidator loadSchemasQuietly() {
    try {
      Path root = Paths.get(System.getProperty("kart.root", ".")).toAbsolutePath().normalize();
      Path dir = root.resolve("schemas");
      if (!Files.isDirectory(dir)) {
        dir = Paths.get("schemas").toAbsolutePath().normalize();
      }
      if (Files.isDirectory(dir)) {
        return new IrSchemaValidator(dir);
      }
    } catch (Exception ignored) {
      // structureCheck will fail closed if schema unavailable when required
    }
    return null;
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
    return Optional.of(new SafePlanHandle(env, phys, report));
  }

  // ---------------------------------------------------------------- structure

  boolean structureCheck(PlanEnvelope env, ValidationReport report) {
    final String C = "StructureCheck";
    // FR-4.1: Plan JSON must conform to plan.schema.json
    if (schemas == null) {
      report.fail(C, "plan.schema.json validator unavailable");
      return false;
    }
    try {
      String json = env.toJson();
      Set<ValidationMessage> errs = schemas.validatePlan(json);
      if (errs != null && !errs.isEmpty()) {
        report.fail(C, "plan.schema.json: " + errs.iterator().next().getMessage());
        return false;
      }
    } catch (Exception e) {
      report.fail(C, "plan.schema.json validate failed: " + e.getMessage());
      return false;
    }
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
        if (n.params != null && Boolean.TRUE.equals(n.params.get("provisional_merge"))) {
          report.fail(C, "INTERSECT node " + n.id
              + " has provisional_merge; CHOOSE_MERGE required before SafePlan");
          return false;
        }
        if (n.params == null || !(n.params.get("merge") instanceof String)) {
          report.fail(C, "INTERSECT node " + n.id + " missing explicit merge implementation");
          return false;
        }
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
    String queryHash = phys != null && phys.queryHash != null ? phys.queryHash : env.query_id;
    String manifestId = phys != null && phys.manifestId != null ? phys.manifestId : env.manifest_id;
    String layoutHash = phys != null && phys.layoutHash != null
        ? phys.layoutHash
        : (layout != null ? sha256Hex(layout.canonicalString()) : "none");
    String compilerVersion = phys != null && phys.compilerVersion != null
        ? phys.compilerVersion
        : QueryCompiler.COMPILER_VERSION;

    for (PlanNode n : env.nodes) {
      if (n.op == null || !n.op.isAccess()) {
        continue;
      }
      List<ScanTask> tasks = phys.tasksForNode(n.id);
      CoverageCertificate cert = new CoverageCertificate();
      cert.query_hash = queryHash;
      cert.manifest_id = manifestId;
      cert.layout_hash = layoutHash;
      cert.compiler_version = compilerVersion;
      cert.index_id = indexIdFor(n.op);
      cert.predicate_binding = predicateBinding(n);
      for (ScanTask t : tasks) {
        cert.emitted_physical_ranges.add(t.table + " shard=" + t.shard
            + " [" + t.startHex + "," + t.stopHex + ")");
        if (!cert.required_shards.contains(Integer.valueOf(t.shard))) {
          // shards filled from required set below
        }
      }

      if (tasks.isEmpty() && n.op != Op.FULL_SCAN_CHUNKS) {
        if (n.op == Op.TIME_RANGE_SCAN && ir.temporal != null
            && ir.temporal.end_ms <= ir.temporal.start_ms) {
          report.addCertificate(cert);
          continue;
        }
        cert.unresolved_obligations.add("no_scan_tasks");
        report.addCertificate(cert);
        report.fail(C, "access node " + n.id + " (" + n.op + ") has no scan tasks");
        return false;
      }
      switch (n.op) {
        case TIME_RANGE_SCAN: {
          if (ir.temporal == null) {
            cert.unresolved_obligations.add("missing_temporal");
            report.addCertificate(cert);
            report.fail(C, "TIME_RANGE_SCAN without IR temporal");
            return false;
          }
          long winStart = ir.temporal.start_ms;
          long winEnd = ir.temporal.end_ms;
          if (n.params != null) {
            if (n.params.get("start_ms") instanceof Number) {
              winStart = ((Number) n.params.get("start_ms")).longValue();
            }
            if (n.params.get("end_ms") instanceof Number) {
              winEnd = ((Number) n.params.get("end_ms")).longValue();
            }
          }
          if (winStart < ir.temporal.start_ms || winEnd > ir.temporal.end_ms) {
            cert.unresolved_obligations.add("partition_outside_temporal");
            report.addCertificate(cert);
            report.fail(C, "TIME partition window outside IR temporal for node " + n.id);
            return false;
          }
          long[] buckets = TimeBucket.bucketsCovering(
              winStart, winEnd, layout.epochMs, layout.bucketMs);
          for (int shard = 0; shard < layout.shardCount; shard++) {
            cert.required_shards.add(Integer.valueOf(shard));
            for (long b : buckets) {
              cert.required_bucket_or_cell_cover.add("time:shard=" + shard + ":bucket=" + b);
              byte[] probe = RowKeyCodec.encodeTime(shard, b, 0, 0);
              if (!coveredBy(probe, tasks, layout.tableTime)) {
                cert.unresolved_obligations.add("time:shard=" + shard + ":bucket=" + b);
                report.addCertificate(cert);
                report.fail(C, "time bucket " + b + " shard " + shard + " not covered by node " + n.id);
                return false;
              }
            }
          }
          break;
        }
        case ZORDER_RANGE_SCAN: {
          if (ir.spatial == null) {
            cert.unresolved_obligations.add("missing_spatial");
            report.addCertificate(cert);
            report.fail(C, "ZORDER_RANGE_SCAN without IR spatial");
            return false;
          }
          double minX = ir.spatial.min_x;
          double minY = ir.spatial.min_y;
          double maxX = ir.spatial.max_x;
          double maxY = ir.spatial.max_y;
          if (n.params != null) {
            if (n.params.get("min_x") instanceof Number) {
              minX = ((Number) n.params.get("min_x")).doubleValue();
            }
            if (n.params.get("min_y") instanceof Number) {
              minY = ((Number) n.params.get("min_y")).doubleValue();
            }
            if (n.params.get("max_x") instanceof Number) {
              maxX = ((Number) n.params.get("max_x")).doubleValue();
            }
            if (n.params.get("max_y") instanceof Number) {
              maxY = ((Number) n.params.get("max_y")).doubleValue();
            }
          }
          if (minX < ir.spatial.min_x || minY < ir.spatial.min_y
              || maxX > ir.spatial.max_x || maxY > ir.spatial.max_y) {
            cert.unresolved_obligations.add("partition_outside_spatial");
            report.addCertificate(cert);
            report.fail(C, "Z partition rect outside IR spatial for node " + n.id);
            return false;
          }
          Rect d = layout.domain;
          ZOrder.CellRect cells = ZOrder.metersToCells(
              minX, minY, maxX, maxY,
              d.minX, d.minY, d.maxX, d.maxY, layout.zorderLevel);
          for (int cx = cells.cxMin; cx <= cells.cxMax; cx++) {
            for (int cy = cells.cyMin; cy <= cells.cyMax; cy++) {
              long z = ZOrder.interleave(cx, cy, layout.zorderLevel);
              for (int shard = 0; shard < layout.shardCount; shard++) {
                if (!cert.required_shards.contains(Integer.valueOf(shard))) {
                  cert.required_shards.add(Integer.valueOf(shard));
                }
                cert.required_bucket_or_cell_cover.add("z:shard=" + shard + ":cell=" + z);
                byte[] probe = RowKeyCodec.encodeZorder(shard, z, 0, 0);
                if (!coveredBy(probe, tasks, layout.tableZorder)) {
                  cert.unresolved_obligations.add("z:shard=" + shard + ":cell=" + z);
                  report.addCertificate(cert);
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
            cert.unresolved_obligations.add(e.getMessage());
            report.addCertificate(cert);
            report.fail(C, e.getMessage());
            return false;
          }
          byte[] hash = VehicleHash.hash128(p.value);
          for (int shard = 0; shard < layout.shardCount; shard++) {
            cert.required_shards.add(Integer.valueOf(shard));
            cert.required_bucket_or_cell_cover.add("hash:shard=" + shard);
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
              cert.unresolved_obligations.add("hash:shard=" + shard);
              report.addCertificate(cert);
              report.fail(C, "hash prefix scan missing for shard " + shard + " node " + n.id);
              return false;
            }
          }
          break;
        }
        case FULL_SCAN_CHUNKS: {
          for (int shard = 0; shard < layout.shardCount; shard++) {
            cert.required_shards.add(Integer.valueOf(shard));
            cert.required_bucket_or_cell_cover.add("full:shard=" + shard);
            byte[] probe = Bytes.u8(shard);
            if (!coveredBy(probe, tasks, layout.tableRaw)) {
              cert.unresolved_obligations.add("full:shard=" + shard);
              report.addCertificate(cert);
              report.fail(C, "full scan missing shard " + shard + " node " + n.id);
              return false;
            }
          }
          break;
        }
        default:
          break;
      }
      if (!cert.complete()) {
        report.addCertificate(cert);
        report.fail(C, "unresolved coverage obligations for node " + n.id);
        return false;
      }
      report.addCertificate(cert);
    }
    report.pass(C, "all access nodes covered; certificates=" + report.certificates().size());
    return true;
  }

  private static String indexIdFor(Op op) {
    if (op == Op.TIME_RANGE_SCAN) {
      return "idx_time";
    }
    if (op == Op.ZORDER_RANGE_SCAN) {
      return "idx_zorder";
    }
    if (op == Op.EQUALITY_LOOKUP) {
      return "idx_hash";
    }
    if (op == Op.FULL_SCAN_CHUNKS) {
      return "traj_raw";
    }
    return String.valueOf(op);
  }

  private static String predicateBinding(PlanNode n) {
    if (n.params == null) {
      return n.id;
    }
    Object ref = n.params.get("predicate_ref");
    if (ref == null) {
      ref = n.params.get("predicate_refs");
    }
    return ref == null ? n.id : String.valueOf(ref);
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
      if (t.truncated) {
        report.fail(C, "scan task truncated=true (node " + t.sourceNodeId + ")");
        return false;
      }
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
      if (!stopWithinShard(stop, t.shard)) {
        report.fail(C, "scan stop not in shard " + t.shard
            + " (or exclusive shard upper bound) for node " + t.sourceNodeId
            + " stop=" + t.stopHex);
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

  /**
   * Stop is in-shard ({@code stop[0]==shard}) or the exclusive end of the shard prefix
   * ({@code PrefixSuccessor([shard])} == typically {@code [shard+1]}).
   */
  static boolean stopWithinShard(byte[] stop, int shard) {
    if (stop == null || stop.length == 0) {
      return false;
    }
    int stop0 = stop[0] & 0xFF;
    if (stop0 == shard) {
      return true;
    }
    java.util.Optional<byte[]> succ =
        PrefixSuccessor.of(new byte[] {(byte) shard});
    if (!succ.isPresent()) {
      return false;
    }
    byte[] bound = succ.get();
    if (stop.length != bound.length) {
      return false;
    }
    for (int i = 0; i < bound.length; i++) {
      if (stop[i] != bound[i]) {
        return false;
      }
    }
    return true;
  }

  private static String sha256Hex(String s) {
    try {
      java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
      byte[] dig = md.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      char[] hex = "0123456789abcdef".toCharArray();
      char[] out = new char[dig.length * 2];
      for (int i = 0; i < dig.length; i++) {
        int v = dig[i] & 0xFF;
        out[i * 2] = hex[v >>> 4];
        out[i * 2 + 1] = hex[v & 0x0F];
      }
      return new String(out);
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
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
