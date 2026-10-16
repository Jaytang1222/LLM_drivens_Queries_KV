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
import kart.compile.CodeRangeBudget;
import kart.codec.CodeRange;
import kart.codec.XzStarSfc;
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
  private final boolean skipCoverageCheck;
  /** Exact SFC ranges reused while validating several plans for the same query. */
  private final Map<String, List<CodeRange>> codeRangeCache =
      new HashMap<String, List<CodeRange>>();

  public PlanValidator(LayoutContext layout) {
    this(layout, loadSchemasQuietly(), false);
  }

  public PlanValidator(LayoutContext layout, boolean skipCoverageCheck) {
    this(layout, loadSchemasQuietly(), skipCoverageCheck);
  }

  public PlanValidator(LayoutContext layout, IrSchemaValidator schemas) {
    this(layout, schemas, false);
  }

  public PlanValidator(LayoutContext layout, IrSchemaValidator schemas, boolean skipCoverageCheck) {
    this.layout = layout;
    this.schemas = schemas;
    this.skipCoverageCheck = skipCoverageCheck;
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
   * Coverage lookup algorithm: default {@code indexed} (decode-once + prefix-max
   * index). Set {@code KART_VALIDATOR_COVERAGE=legacy} for old O(M×N) scan.
   */
  static boolean useIndexedCoverage() {
    String raw = System.getenv("KART_VALIDATOR_COVERAGE");
    if (raw == null || raw.trim().isEmpty()) {
      return true;
    }
    String v = raw.trim().toLowerCase();
    return !(v.equals("legacy") || v.equals("scan") || v.equals("old"));
  }

  /**
   * Runs all checks in order, filling the report. Returns a SafePlanHandle
   * only if every check passed.
   */
  public Optional<SafePlanHandle> validate(PlanEnvelope env, BoundIr ir,
                                           PhysicalPlan phys, ValidationReport report) {
    ValidatorTiming timing = report.timing != null ? report.timing : new ValidatorTiming();
    report.timing = timing;
    timing.coverageAlgo = useIndexedCoverage() ? "indexed" : "legacy";

    long t0 = System.nanoTime();
    boolean ok = structureCheck(env, report);
    timing.t_structure_ms += Math.max(0L, (System.nanoTime() - t0) / 1_000_000L);

    if (ok) {
      long t1 = System.nanoTime();
      ok = semanticCheck(env, ir, report);
      timing.t_semantic_ms += Math.max(0L, (System.nanoTime() - t1) / 1_000_000L);
    }
    if (ok) {
      if (skipCoverageCheck) {
        report.pass("CoverageCheck", "SKIPPED_UNSAFE");
      } else {
        long t2 = System.nanoTime();
        ok = coverageCheck(env, ir, phys, report);
        timing.t_coverage_ms += Math.max(0L, (System.nanoTime() - t2) / 1_000_000L);
      }
    }
    if (ok) {
      long t3 = System.nanoTime();
      ok = physicalSafetyCheck(env, phys, report);
      timing.t_physical_safety_ms += Math.max(0L, (System.nanoTime() - t3) / 1_000_000L);
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
    if (BoundIr.hasTemporal(ir)) {
      required.add("/temporal");
    }
    if (BoundIr.hasSpatial(ir)) {
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
    for (PlanNode n : env.nodes) {
      if (n.op == Op.CHUNK_META_FILTER) {
        if (!BoundIr.hasTemporal(ir) && !BoundIr.hasSpatial(ir)) {
          report.fail(C, "CHUNK_META_FILTER " + n.id + " without ST predicates");
          return false;
        }
        for (PlanNode m : env.nodes) {
          if (m.inputs.contains(n.id) && m.op != Op.FETCH_TRAJECTORY_CHUNK) {
            report.fail(C, "CHUNK_META_FILTER " + n.id + " must feed only FETCH_TRAJECTORY_CHUNK");
            return false;
          }
        }
      }
      String bad = badParam(n, "max_ranges", Op.ZORDER_RANGE_SCAN, 1, 4096);
      bad = bad != null ? bad : badParam(n, "fetch_batch", Op.FETCH_TRAJECTORY_CHUNK, 1, 10000);
      bad = bad != null ? bad : badParam(n, "topk_lb_b0", Op.BATCH_GET_TRAJECTORY, 1, Integer.MAX_VALUE);
      bad = bad != null ? bad : badParam(n, "topk_lb_growth", Op.BATCH_GET_TRAJECTORY, 2, 64);
      if (bad == null && n.params != null && n.params.get("topk_lb_b0") != null) {
        PlanNode sim = null;
        for (PlanNode m : env.nodes) {
          if (m.inputs.contains(n.id)) {
            sim = m;
          }
        }
        PlanNode top = sim == null ? null : byId.get(env.root);
        Object k = n.params.get("topk_lb_k");
        Object topKParam = top == null || top.params == null ? null : top.params.get("k");
        Integer irK = ir.result == null ? null : ir.result.k;
        int effective = topKParam instanceof Number ? ((Number) topKParam).intValue() : (irK == null ? -1 : irK);
        if (!topK || sim == null || sim.op != Op.SIMILARITY || !(k instanceof Number)
            || ((Number) k).intValue() != effective) {
          bad = "topk_lb params require TOP_K chain with topk_lb_k equal to the result k";
        }
      }
      if (bad != null) {
        report.fail(C, bad + " (node " + n.id + ")");
        return false;
      }
    }
    report.pass(C, "semantic constraints ok (mode=" + (topK ? "TOP_K" : "TRAJECTORY_IDS") + ")");
    return true;
  }

  private static String badParam(PlanNode n, String key, Op allowed, int min, int max) {
    Object v = n.params == null ? null : n.params.get(key);
    if (v == null) {
      return null;
    }
    if (n.op != allowed) {
      return key + " only allowed on " + allowed;
    }
    if (!(v instanceof Number) || ((Number) v).intValue() < min || ((Number) v).intValue() > max) {
      return key + " out of range [" + min + "," + max + "]";
    }
    return null;
  }

  // ----------------------------------------------------------------- coverage

  boolean coverageCheck(PlanEnvelope env, BoundIr ir, PhysicalPlan phys, ValidationReport report) {
    final String C = "CoverageCheck";
    ValidatorTiming timing = report != null ? report.timing : null;
    boolean indexed = useIndexedCoverage();
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
      long tCert0 = System.nanoTime();
      for (ScanTask t : tasks) {
        cert.emitted_physical_ranges.add(t.table + " shard=" + t.shard
            + " [" + t.startHex + "," + t.stopHex + ")");
      }
      if (timing != null) {
        timing.t_coverage_cert_ms += Math.max(0L, (System.nanoTime() - tCert0) / 1_000_000L);
      }

      if (tasks.isEmpty() && n.op != Op.FULL_SCAN_CHUNKS) {
        if (n.op == Op.TIME_RANGE_SCAN && BoundIr.hasTemporal(ir)) {
          boolean allEmpty = true;
          for (BoundIr.Temporal _w : BoundIr.temporalsOf(ir)) {
            if (_w != null && _w.end_ms > _w.start_ms) { allEmpty = false; break; }
          }
          if (allEmpty) {
            report.addCertificate(cert);
            continue;
          }
        }
        cert.unresolved_obligations.add("no_scan_tasks");
        report.addCertificate(cert);
        report.fail(C, "access node " + n.id + " (" + n.op + ") has no scan tasks");
        return false;
      }

      List<RangeCoverageIndex.DecodedRange> decoded = null;
      RangeCoverageIndex index = null;
      if (indexed) {
        decoded = RangeCoverageIndex.decodeAll(tasks, timing);
        for (RangeCoverageIndex.DecodedRange dr : decoded) {
          if (dr != null && !dr.ok()) {
            cert.unresolved_obligations.add("bad_range:" + dr.error);
            report.addCertificate(cert);
            report.fail(C, "invalid scan range for node " + n.id + ": " + dr.error);
            return false;
          }
        }
      }

      switch (n.op) {
        case TIME_RANGE_SCAN: {
          if (!BoundIr.hasTemporal(ir)) {
            cert.unresolved_obligations.add("missing_temporal");
            report.addCertificate(cert);
            report.fail(C, "TIME_RANGE_SCAN without IR temporal");
            return false;
          }
          java.util.List<BoundIr.Temporal> timeWins = new java.util.ArrayList<BoundIr.Temporal>();
          if (n.params != null
              && (n.params.get("start_ms") instanceof Number
                  || n.params.get("end_ms") instanceof Number)) {
            BoundIr.Temporal u = BoundIr.temporalsOf(ir).get(0);
            long winStart = u.start_ms;
            long winEnd = u.end_ms;
            if (n.params.get("start_ms") instanceof Number) {
              winStart = ((Number) n.params.get("start_ms")).longValue();
            }
            if (n.params.get("end_ms") instanceof Number) {
              winEnd = ((Number) n.params.get("end_ms")).longValue();
            }
            long coverLo = Long.MAX_VALUE;
            long coverHi = Long.MIN_VALUE;
            for (BoundIr.Temporal w : BoundIr.temporalsOf(ir)) {
              if (w.start_ms < coverLo) coverLo = w.start_ms;
              if (w.end_ms > coverHi) coverHi = w.end_ms;
            }
            if (winStart < coverLo || winEnd > coverHi) {
              cert.unresolved_obligations.add("partition_outside_temporal");
              report.addCertificate(cert);
              report.fail(C, "TIME partition window outside IR temporal for node " + n.id);
              return false;
            }
            BoundIr.Temporal one = new BoundIr.Temporal();
            one.start_ms = winStart;
            one.end_ms = winEnd;
            timeWins.add(one);
          } else {
            timeWins.addAll(BoundIr.temporalsOf(ir));
          }
          if (indexed) {
            index = RangeCoverageIndex.buildForTable(decoded, layout.tableTime, timing);
          }
          long tProbe0 = System.nanoTime();
          for (BoundIr.Temporal tw : timeWins) {
            long[] buckets = TimeBucket.bucketsCovering(
                tw.start_ms, tw.end_ms, layout.epochMs, layout.bucketMs);
            for (int shard = 0; shard < layout.shardCount; shard++) {
              cert.required_shards.add(Integer.valueOf(shard));
              for (long b : buckets) {
                cert.required_bucket_or_cell_cover.add("time:shard=" + shard + ":bucket=" + b);
                byte[] probe = RowKeyCodec.encodeTime(shard, b, 0, 0);
                if (timing != null) {
                  timing.coverage_probe_count++;
                }
                boolean hit = indexed
                    ? index.covers(probe)
                    : RangeCoverageIndex.coveredByLegacy(probe, tasks, layout.tableTime, timing);
                if (!hit) {
                  if (timing != null) {
                    timing.t_coverage_probe_ms +=
                        Math.max(0L, (System.nanoTime() - tProbe0) / 1_000_000L);
                  }
                  cert.unresolved_obligations.add("time:shard=" + shard + ":bucket=" + b);
                  report.addCertificate(cert);
                  report.fail(C, "time bucket " + b + " shard " + shard + " not covered by node " + n.id);
                  return false;
                }
              }
            }
          }
          if (timing != null) {
            timing.t_coverage_probe_ms += Math.max(0L, (System.nanoTime() - tProbe0) / 1_000_000L);
          }
          break;
        }
        case ZORDER_RANGE_SCAN: {
          if (!BoundIr.hasSpatial(ir)) {
            cert.unresolved_obligations.add("missing_spatial");
            report.addCertificate(cert);
            report.fail(C, "ZORDER_RANGE_SCAN without IR spatial");
            return false;
          }
          java.util.List<BoundIr.Spatial> zParts = new java.util.ArrayList<BoundIr.Spatial>();
          BoundIr.Spatial zUnion = BoundIr.spatialUnion(ir);
          if (n.params != null
              && (n.params.get("min_x") instanceof Number || n.params.get("min_y") instanceof Number
                  || n.params.get("max_x") instanceof Number || n.params.get("max_y") instanceof Number)) {
            double minX = zUnion.min_x;
            double minY = zUnion.min_y;
            double maxX = zUnion.max_x;
            double maxY = zUnion.max_y;
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
            if (minX < zUnion.min_x || minY < zUnion.min_y
                || maxX > zUnion.max_x || maxY > zUnion.max_y) {
              cert.unresolved_obligations.add("partition_outside_spatial");
              report.addCertificate(cert);
              report.fail(C, "Z partition rect outside IR spatial for node " + n.id);
              return false;
            }
            BoundIr.Spatial one = new BoundIr.Spatial();
            one.min_x = minX; one.min_y = minY; one.max_x = maxX; one.max_y = maxY;
            zParts.add(one);
          } else {
            zParts.addAll(BoundIr.spatialsOf(ir));
          }
          if (indexed) {
            index = RangeCoverageIndex.buildForTable(decoded, layout.tableZorder, timing);
          }
          Rect d = layout.domain;
          long tProbe0 = System.nanoTime();
          for (BoundIr.Spatial zs : zParts) {
            ZOrder.CellRect cells = ZOrder.metersToCells(
                zs.min_x, zs.min_y, zs.max_x, zs.max_y,
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
                  if (timing != null) {
                    timing.coverage_probe_count++;
                  }
                  boolean hit = indexed
                      ? index.covers(probe)
                      : RangeCoverageIndex.coveredByLegacy(probe, tasks, layout.tableZorder, timing);
                  if (!hit) {
                    if (timing != null) {
                      timing.t_coverage_probe_ms +=
                          Math.max(0L, (System.nanoTime() - tProbe0) / 1_000_000L);
                    }
                    cert.unresolved_obligations.add("z:shard=" + shard + ":cell=" + z);
                    report.addCertificate(cert);
                    report.fail(C, "z-cell " + z + " shard " + shard + " not covered by node " + n.id);
                    return false;
                  }
                }
              }
            }
          }
          if (timing != null) {
            timing.t_coverage_probe_ms += Math.max(0L, (System.nanoTime() - tProbe0) / 1_000_000L);
          }
          break;
        }
        case XZ_RANGE_SCAN:
        case TSHAPE_RANGE_SCAN: {
          if (!BoundIr.hasSpatial(ir)) {
            cert.unresolved_obligations.add("missing_spatial");
            report.addCertificate(cert);
            report.fail(C, n.op + " without IR spatial");
            return false;
          }
          java.util.List<BoundIr.Spatial> xzParts = new java.util.ArrayList<BoundIr.Spatial>();
          BoundIr.Spatial xzUnion = BoundIr.spatialUnion(ir);
          if (n.params != null
              && (n.params.get("min_x") instanceof Number || n.params.get("min_y") instanceof Number
                  || n.params.get("max_x") instanceof Number || n.params.get("max_y") instanceof Number)) {
            BoundIr.Spatial one = new BoundIr.Spatial();
            one.min_x = xzUnion.min_x; one.min_y = xzUnion.min_y;
            one.max_x = xzUnion.max_x; one.max_y = xzUnion.max_y;
            if (n.params.get("min_x") instanceof Number) {
              one.min_x = ((Number) n.params.get("min_x")).doubleValue();
            }
            if (n.params.get("min_y") instanceof Number) {
              one.min_y = ((Number) n.params.get("min_y")).doubleValue();
            }
            if (n.params.get("max_x") instanceof Number) {
              one.max_x = ((Number) n.params.get("max_x")).doubleValue();
            }
            if (n.params.get("max_y") instanceof Number) {
              one.max_y = ((Number) n.params.get("max_y")).doubleValue();
            }
            xzParts.add(one);
          } else {
            xzParts.addAll(BoundIr.spatialsOf(ir));
          }
          String table = n.op == Op.XZ_RANGE_SCAN ? layout.tableXz : layout.tableTshape;
          if (indexed) {
            index = RangeCoverageIndex.buildForTable(decoded, table, timing);
          }
          long tProbe0 = System.nanoTime();
          for (BoundIr.Spatial xs : xzParts) {
            List<CodeRange> codeRanges = spatialCodeRanges(
                n.op, xs.min_x, xs.min_y, xs.max_x, xs.max_y);
            if (!spatialOverlapSound(n.op, xs.min_x, xs.min_y, xs.max_x, xs.max_y,
                codeRanges, cert, report)) {
              return false;
            }
            for (kart.codec.CodeRange cr : codeRanges) {
              for (int shard = 0; shard < layout.shardCount; shard++) {
                if (!cert.required_shards.contains(Integer.valueOf(shard))) {
                  cert.required_shards.add(Integer.valueOf(shard));
                }
                cert.required_bucket_or_cell_cover.add(n.op + ":shard=" + shard + ":lo=" + cr.lo);
                byte[] probeLo = n.op == Op.XZ_RANGE_SCAN
                    ? RowKeyCodec.encodeXz(shard, cr.lo, 0, 0)
                    : RowKeyCodec.encodeTShape(shard, cr.lo, 0, 0);
                byte[] probeHi = n.op == Op.XZ_RANGE_SCAN
                    ? RowKeyCodec.encodeXz(shard, cr.hi, 0, 0)
                    : RowKeyCodec.encodeTShape(shard, cr.hi, 0, 0);
                if (timing != null) {
                  timing.coverage_probe_count += 2;
                }
                boolean hitLo = indexed ? index.covers(probeLo)
                    : RangeCoverageIndex.coveredByLegacy(probeLo, tasks, table, timing);
                boolean hitHi = indexed ? index.covers(probeHi)
                    : RangeCoverageIndex.coveredByLegacy(probeHi, tasks, table, timing);
                if (!hitLo || !hitHi) {
                  if (timing != null) {
                    timing.t_coverage_probe_ms +=
                        Math.max(0L, (System.nanoTime() - tProbe0) / 1_000_000L);
                  }
                  cert.unresolved_obligations.add(n.op + ":shard=" + shard + ":[" + cr.lo + "," + cr.hi + "]");
                  report.addCertificate(cert);
                  report.fail(C, n.op + " range [" + cr.lo + "," + cr.hi + "] shard " + shard
                      + " not covered by node " + n.id);
                  return false;
                }
              }
            }
          }
          if (timing != null) {
            timing.t_coverage_probe_ms += Math.max(0L, (System.nanoTime() - tProbe0) / 1_000_000L);
          }
          break;
        }
        case XZT_RANGE_SCAN: {
          if (!BoundIr.hasTemporal(ir)) {
            cert.unresolved_obligations.add("missing_temporal");
            report.addCertificate(cert);
            report.fail(C, "XZT_RANGE_SCAN without IR temporal");
            return false;
          }
          java.util.List<BoundIr.Temporal> xztWins = new java.util.ArrayList<BoundIr.Temporal>();
          if (n.params != null
              && (n.params.get("start_ms") instanceof Number
                  || n.params.get("end_ms") instanceof Number)) {
            BoundIr.Temporal u = BoundIr.temporalsOf(ir).get(0);
            BoundIr.Temporal one = new BoundIr.Temporal();
            one.start_ms = u.start_ms;
            one.end_ms = u.end_ms;
            if (n.params.get("start_ms") instanceof Number) {
              one.start_ms = ((Number) n.params.get("start_ms")).longValue();
            }
            if (n.params.get("end_ms") instanceof Number) {
              one.end_ms = ((Number) n.params.get("end_ms")).longValue();
            }
            xztWins.add(one);
          } else {
            xztWins.addAll(BoundIr.temporalsOf(ir));
          }
          if (indexed) {
            index = RangeCoverageIndex.buildForTable(decoded, layout.tableXzt, timing);
          }
          long tProbe0 = System.nanoTime();
          for (BoundIr.Temporal xw : xztWins) {
            List<CodeRange> xztRanges = temporalCodeRanges(xw.start_ms, xw.end_ms);
            if (!xztOverlapSound(xw.start_ms, xw.end_ms, xztRanges, cert, report)) {
              return false;
            }
            for (kart.codec.CodeRange cr : xztRanges) {
              for (int shard = 0; shard < layout.shardCount; shard++) {
                if (!cert.required_shards.contains(Integer.valueOf(shard))) {
                  cert.required_shards.add(Integer.valueOf(shard));
                }
                cert.required_bucket_or_cell_cover.add("xzt:shard=" + shard + ":lo=" + cr.lo);
                byte[] probeLo = RowKeyCodec.encodeXzt(shard, cr.lo, 0, 0);
                byte[] probeHi = RowKeyCodec.encodeXzt(shard, cr.hi, 0, 0);
                if (timing != null) {
                  timing.coverage_probe_count += 2;
                }
                boolean hitLo = indexed ? index.covers(probeLo)
                    : RangeCoverageIndex.coveredByLegacy(probeLo, tasks, layout.tableXzt, timing);
                boolean hitHi = indexed ? index.covers(probeHi)
                    : RangeCoverageIndex.coveredByLegacy(probeHi, tasks, layout.tableXzt, timing);
                if (!hitLo || !hitHi) {
                  if (timing != null) {
                    timing.t_coverage_probe_ms +=
                        Math.max(0L, (System.nanoTime() - tProbe0) / 1_000_000L);
                  }
                  cert.unresolved_obligations.add("xzt:shard=" + shard + ":[" + cr.lo + "," + cr.hi + "]");
                  report.addCertificate(cert);
                  report.fail(C, "xzt range [" + cr.lo + "," + cr.hi + "] shard " + shard
                      + " not covered by node " + n.id);
                  return false;
                }
              }
            }
          }
          if (timing != null) {
            timing.t_coverage_probe_ms += Math.max(0L, (System.nanoTime() - tProbe0) / 1_000_000L);
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
          if (decoded == null) {
            decoded = RangeCoverageIndex.decodeAll(tasks, timing);
          }
          byte[] hash = VehicleHash.hash128(p.value);
          for (int shard = 0; shard < layout.shardCount; shard++) {
            cert.required_shards.add(Integer.valueOf(shard));
            cert.required_bucket_or_cell_cover.add("hash:shard=" + shard);
            byte[] expectedPrefix = Arrays.copyOf(
                RowKeyCodec.encodeHash(shard, RowKeyCodec.HASH_FIELD_VEHICLE, hash, 0, 0), 18);
            boolean found = false;
            for (RangeCoverageIndex.DecodedRange dr : decoded) {
              if (dr.ok() && layout.tableHash.equals(dr.table) && dr.shard == shard
                  && Arrays.equals(dr.start, expectedPrefix)) {
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
          if (indexed) {
            index = RangeCoverageIndex.buildForTable(decoded, layout.tableRaw, timing);
          }
          long tProbe0 = System.nanoTime();
          for (int shard = 0; shard < layout.shardCount; shard++) {
            cert.required_shards.add(Integer.valueOf(shard));
            cert.required_bucket_or_cell_cover.add("full:shard=" + shard);
            byte[] probe = Bytes.u8(shard);
            if (timing != null) {
              timing.coverage_probe_count++;
            }
            boolean hit = indexed
                ? index.covers(probe)
                : RangeCoverageIndex.coveredByLegacy(probe, tasks, layout.tableRaw, timing);
            if (!hit) {
              if (timing != null) {
                timing.t_coverage_probe_ms +=
                    Math.max(0L, (System.nanoTime() - tProbe0) / 1_000_000L);
              }
              cert.unresolved_obligations.add("full:shard=" + shard);
              report.addCertificate(cert);
              report.fail(C, "full scan missing shard " + shard + " node " + n.id);
              return false;
            }
          }
          if (timing != null) {
            timing.t_coverage_probe_ms += Math.max(0L, (System.nanoTime() - tProbe0) / 1_000_000L);
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
    report.pass(C, "all access nodes covered; certificates=" + report.certificates().size()
        + "; algo=" + (indexed ? "indexed" : "legacy"));
    return true;
  }

  /** Independent soundness: overlapping sample intervals must land in SRQ ranges. */
  private boolean xztOverlapSound(long winStart, long winEnd,
                                  java.util.List<kart.codec.CodeRange> ranges,
                                  CoverageCertificate cert, ValidationReport report) {
    if (layout == null || layout.xztCodec() == null || ranges == null) {
      return true;
    }
    long span = Math.max(1L, winEnd - winStart);
    long[][] samples = new long[][] {
        {winStart, winEnd},
        {winStart, winStart + Math.max(1L, span / 4)},
        {winEnd - Math.max(1L, span / 4), winEnd},
        {winStart - span / 10, winStart + span / 10},
        {winEnd - span / 10, winEnd + span / 10},
        {winStart + span / 3, winStart + 2 * span / 3}
    };
    for (long[] s : samples) {
      long a = Math.min(s[0], s[1]);
      long b = Math.max(s[0], s[1]);
      if (b < winStart || a > winEnd) {
        continue; // non-overlapping with query
      }
      long code = layout.xztCodec().indexInterval(a, b);
      // Adapters span-fill for recall; accept unfilled or filled cover.
      if (!codeInRanges(code, ranges)
          && !codeInRanges(code, XzStarSfc.fillInternalGaps(ranges))) {
        cert.unresolved_obligations.add("xzt_overlap_miss:code=" + code);
        report.addCertificate(cert);
        report.fail("CoverageCheck", "XZT SRQ misses overlapping interval code=" + code
            + " for window [" + winStart + "," + winEnd + "]");
        return false;
      }
    }
    return true;
  }

  private boolean spatialOverlapSound(Op op, double minX, double minY, double maxX, double maxY,
                                      java.util.List<kart.codec.CodeRange> ranges,
                                      CoverageCertificate cert, ValidationReport report) {
    if (layout == null || ranges == null) {
      return true;
    }
    double cx = (minX + maxX) / 2.0;
    double cy = (minY + maxY) / 2.0;
    double hx = Math.max(1e-6, (maxX - minX) / 4.0);
    double hy = Math.max(1e-6, (maxY - minY) / 4.0);
    double[][] boxes = new double[][] {
        {minX, minY, maxX, maxY},
        {cx - hx, cy - hy, cx + hx, cy + hy},
        {minX, minY, minX + hx, minY + hy},
        {maxX - hx, maxY - hy, maxX, maxY}
    };
    for (double[] b : boxes) {
      long code = op == Op.XZ_RANGE_SCAN
          ? layout.xzSfc().indexMbr(b[0], b[1], b[2], b[3], true)
          : layout.tshapeSfc().indexMbr(b[0], b[1], b[2], b[3]);
      if (!codeInRanges(code, ranges)) {
        cert.unresolved_obligations.add(op + "_overlap_miss:code=" + code);
        report.addCertificate(cert);
        report.fail("CoverageCheck", op + " SRQ misses overlapping MBR code=" + code);
        return false;
      }
    }
    return true;
  }

  /**
   * Return the exact inclusive SFC cover for this predicate. Sorting and merging
   * overlap/adjacency preserves the represented integer-code set and lets the
   * validator reuse one result across all candidate plans for the same query.
   */
  private List<CodeRange> spatialCodeRanges(Op op, double minX, double minY,
                                            double maxX, double maxY) {
    String key = op.name() + "|" + Double.doubleToLongBits(minX) + "|"
        + Double.doubleToLongBits(minY) + "|" + Double.doubleToLongBits(maxX) + "|"
        + Double.doubleToLongBits(maxY);
    List<CodeRange> cached = codeRangeCache.get(key);
    if (cached != null) {
      return cached;
    }
    List<CodeRange> raw = op == Op.XZ_RANGE_SCAN
        ? layout.xzSfc().ranges(minX, minY, maxX, maxY)
        : layout.tshapeSfc().ranges(minX, minY, maxX, maxY);
    List<CodeRange> normalized = CodeRangeBudget.mergeOverlapping(raw);
    codeRangeCache.put(key, normalized);
    return normalized;
  }

  private List<CodeRange> temporalCodeRanges(long startMs, long endMs) {
    String key = "XZT|" + startMs + "|" + endMs;
    List<CodeRange> cached = codeRangeCache.get(key);
    if (cached != null) {
      return cached;
    }
    List<CodeRange> normalized = CodeRangeBudget.mergeOverlapping(
        layout.xztCodec().rangesCovering(startMs, endMs));
    codeRangeCache.put(key, normalized);
    return normalized;
  }

  /** Binary search over the sorted, disjoint ranges returned by the two helpers above. */
  static boolean codeInRanges(long code, List<CodeRange> ranges) {
    if (ranges == null || ranges.isEmpty()) {
      return false;
    }
    int lo = 0;
    int hi = ranges.size() - 1;
    while (lo <= hi) {
      int mid = (lo + hi) >>> 1;
      CodeRange range = ranges.get(mid);
      if (code < range.lo) {
        hi = mid - 1;
      } else if (code > range.hi) {
        lo = mid + 1;
      } else {
        return true;
      }
    }
    return false;
  }

  private static String indexIdFor(Op op) {
    if (op == Op.TIME_RANGE_SCAN) {
      return "idx_time";
    }
    if (op == Op.ZORDER_RANGE_SCAN) {
      return "idx_zorder";
    }
    if (op == Op.XZ_RANGE_SCAN) {
      return "idx_xz";
    }
    if (op == Op.TSHAPE_RANGE_SCAN) {
      return "idx_tshape";
    }
    if (op == Op.XZT_RANGE_SCAN) {
      return "idx_xzt";
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

  /** @deprecated use {@link RangeCoverageIndex}; kept for tests. */
  static boolean coveredBy(byte[] probe, List<ScanTask> tasks, String table) {
    return RangeCoverageIndex.coveredByLegacy(probe, tasks, table, null);
  }

  // ---------------------------------------------------------- physical safety

  boolean physicalSafetyCheck(PlanEnvelope env, PhysicalPlan phys, ValidationReport report) {
    final String C = "PhysicalSafetyCheck";
    ValidatorTiming timing = report != null ? report.timing : null;
    if (phys.scanTasks.size() > MAX_SCAN_TASKS) {
      report.fail(C, "too many scan tasks: " + phys.scanTasks.size());
      return false;
    }
    // Decode once for physical checks (B1); do not mutate ScanTask.
    List<RangeCoverageIndex.DecodedRange> decoded =
        RangeCoverageIndex.decodeAll(phys.scanTasks, timing);
    for (RangeCoverageIndex.DecodedRange dr : decoded) {
      ScanTask t = dr.task;
      if (t.truncated) {
        report.fail(C, "scan task truncated=true (node " + t.sourceNodeId + ")");
        return false;
      }
      if (!dr.ok()) {
        report.fail(C, "scan task decode/bounds failed (node " + t.sourceNodeId
            + "): " + dr.error);
        return false;
      }
      byte[] start = dr.start;
      byte[] stop = dr.stop;
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
