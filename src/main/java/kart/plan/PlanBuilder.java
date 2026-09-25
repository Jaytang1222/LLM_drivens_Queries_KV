package kart.plan;

import kart.ir.BoundIr;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the deterministic candidate plan family from a BoundIr:
 * one plan per non-empty subset of available index dimensions
 * (T = temporal, Z = spatial, H = vehicle_id equality), plus P_FULL.
 */
public final class PlanBuilder {

  private PlanBuilder() {}

  /** Returns candidate plans in deterministic order (HASH_SET merges + P_FULL). */
  public static List<PlanEnvelope> buildCandidates(BoundIr ir) {
    boolean hasT = ir.temporal != null;
    boolean hasZ = ir.spatial != null;
    int vehicleIdx = vehicleEqPredicateIndex(ir);
    boolean hasH = vehicleIdx >= 0;

    List<PlanEnvelope> out = new ArrayList<PlanEnvelope>();
    // single-index plans
    if (hasT) {
      out.add(buildIndexed(ir, "P_T", true, false, false, vehicleIdx));
    }
    if (hasZ) {
      out.add(buildIndexed(ir, "P_Z", false, true, false, vehicleIdx));
    }
    if (hasH) {
      out.add(buildIndexed(ir, "P_H", false, false, true, vehicleIdx));
    }
    // intersections
    if (hasT && hasZ) {
      out.add(buildIndexed(ir, "P_TZ", true, true, false, vehicleIdx));
    }
    if (hasT && hasH) {
      out.add(buildIndexed(ir, "P_TH", true, false, true, vehicleIdx));
    }
    if (hasZ && hasH) {
      out.add(buildIndexed(ir, "P_ZH", false, true, true, vehicleIdx));
    }
    if (hasT && hasZ && hasH) {
      out.add(buildIndexed(ir, "P_TZH", true, true, true, vehicleIdx));
    }
    // full scan fallback, always present
    out.add(buildFull(ir, vehicleIdx));
    return out;
  }

  /**
   * Constructor family plus {@code SORT_MERGE} twins for every multi-index plan
   * (used by LLM-direct baseline / merge-arm coverage; does not change default beam families).
   */
  public static List<PlanEnvelope> buildCandidatesWithMergeVariants(BoundIr ir) {
    List<PlanEnvelope> out = new ArrayList<PlanEnvelope>(buildCandidates(ir));
    boolean hasT = ir.temporal != null;
    boolean hasZ = ir.spatial != null;
    boolean hasH = vehicleEqPredicateIndex(ir) >= 0;
    if (hasT && hasZ) {
      out.add(buildForAccess(ir, true, true, false, "SORT_MERGE", null));
    }
    if (hasT && hasH) {
      out.add(buildForAccess(ir, true, false, true, "SORT_MERGE", null));
    }
    if (hasZ && hasH) {
      out.add(buildForAccess(ir, false, true, true, "SORT_MERGE", null));
    }
    if (hasT && hasZ && hasH) {
      out.add(buildForAccess(ir, true, true, true, "SORT_MERGE", null));
    }
    return out;
  }

  /** Index of the first vehicle_id EQ predicate, or -1. */
  public static int vehicleEqPredicateIndex(BoundIr ir) {
    if (ir.predicates == null) {
      return -1;
    }
    for (int i = 0; i < ir.predicates.size(); i++) {
      BoundIr.Predicate p = ir.predicates.get(i);
      if (p != null && "vehicle_id".equals(p.field) && "EQ".equals(p.op)) {
        return i;
      }
    }
    return -1;
  }

  /**
   * Build a plan for an access subgraph (used by plan search FINISH).
   * Empty access → {@code P_FULL}.
   */
  public static PlanEnvelope buildForAccess(BoundIr ir, boolean useT, boolean useZ, boolean useH) {
    return buildForAccess(ir, useT, useZ, useH, null, null);
  }

  public static PlanEnvelope buildForAccess(BoundIr ir, boolean useT, boolean useZ, boolean useH,
                                            String mergeImpl, String partitionKind) {
    int vehicleIdx = vehicleEqPredicateIndex(ir);
    if (!useT && !useZ && !useH) {
      return buildFull(ir, vehicleIdx);
    }
    String name = planName(useT, useZ, useH);
    if (partitionKind != null) {
      name = name + "_" + partitionKind;
    }
    if (mergeImpl != null
        && !"HASH_SET".equals(mergeImpl)
        && (useT ? 1 : 0) + (useZ ? 1 : 0) + (useH ? 1 : 0) >= 2) {
      // HASH_SET is the default family name (P_TZ); only suffix non-default merges.
      name = name + "_" + mergeImpl;
    }
    return buildIndexed(ir, name, useT, useZ, useH, vehicleIdx, mergeImpl, partitionKind);
  }

  /** Plan id for an index combination: P_T, P_Z, P_H, P_TZ, … */
  public static String planName(boolean useT, boolean useZ, boolean useH) {
    StringBuilder sb = new StringBuilder("P_");
    if (useT) {
      sb.append('T');
    }
    if (useZ) {
      sb.append('Z');
    }
    if (useH) {
      sb.append('H');
    }
    return sb.toString();
  }

  private static PlanEnvelope buildIndexed(BoundIr ir, String name,
                                           boolean useT, boolean useZ, boolean useH,
                                           int vehicleIdx) {
    // Published candidate families use an explicit merge (HASH_SET), never provisional.
    int dims = (useT ? 1 : 0) + (useZ ? 1 : 0) + (useH ? 1 : 0);
    String merge = dims >= 2 ? "HASH_SET" : null;
    return buildIndexed(ir, name, useT, useZ, useH, vehicleIdx, merge, null);
  }

  private static PlanEnvelope buildIndexed(BoundIr ir, String name,
                                           boolean useT, boolean useZ, boolean useH,
                                           int vehicleIdx, String mergeImpl, String partitionKind) {
    Builder b = new Builder(ir, name);
    List<String> accessIds = new ArrayList<String>();

    if (useT) {
      if ("TIME_BIPART".equals(partitionKind) && ir.temporal != null) {
        long mid = provedTimeMidpoint(ir.temporal.start_ms, ir.temporal.end_ms);
        Map<String, Object> p0 = params("predicate_ref", "/temporal");
        p0.put("start_ms", Long.valueOf(ir.temporal.start_ms));
        p0.put("end_ms", Long.valueOf(mid));
        p0.put("partition", "TIME_BIPART_0");
        Map<String, Object> p1 = params("predicate_ref", "/temporal");
        p1.put("start_ms", Long.valueOf(mid));
        p1.put("end_ms", Long.valueOf(ir.temporal.end_ms));
        p1.put("partition", "TIME_BIPART_1");
        String t0 = b.add(Op.TIME_RANGE_SCAN, noInputs(), p0);
        String t1 = b.add(Op.TIME_RANGE_SCAN, noInputs(), p1);
        accessIds.add(b.add(Op.UNION, two(t0, t1), params("partition_proof", "TIME_BIPART")));
      } else {
        accessIds.add(b.add(Op.TIME_RANGE_SCAN, noInputs(), params("predicate_ref", "/temporal")));
      }
    }
    if (useZ) {
      if ("Z_QUAD".equals(partitionKind) && ir.spatial != null) {
        double mx = (ir.spatial.min_x + ir.spatial.max_x) / 2.0;
        double my = (ir.spatial.min_y + ir.spatial.max_y) / 2.0;
        List<String> parts = new ArrayList<String>();
        parts.add(zPart(b, ir, ir.spatial.min_x, ir.spatial.min_y, mx, my, "Z_QUAD_0"));
        parts.add(zPart(b, ir, mx, ir.spatial.min_y, ir.spatial.max_x, my, "Z_QUAD_1"));
        parts.add(zPart(b, ir, ir.spatial.min_x, my, mx, ir.spatial.max_y, "Z_QUAD_2"));
        parts.add(zPart(b, ir, mx, my, ir.spatial.max_x, ir.spatial.max_y, "Z_QUAD_3"));
        accessIds.add(b.add(Op.UNION, parts, params("partition_proof", "Z_QUAD")));
      } else {
        accessIds.add(b.add(Op.ZORDER_RANGE_SCAN, noInputs(), params("predicate_ref", "/spatial")));
      }
    }
    if (useH) {
      accessIds.add(b.add(Op.EQUALITY_LOOKUP, noInputs(),
          params("predicate_ref", "/predicates/" + vehicleIdx)));
    }
    String cur;
    if (accessIds.size() > 1) {
      Map<String, Object> mergeParams = new LinkedHashMap<String, Object>();
      if (mergeImpl != null) {
        mergeParams.put("merge", mergeImpl);
        mergeParams.put("provisional_merge", Boolean.FALSE);
      } else {
        // FastCost of incomplete search states only; PlanValidator rejects provisional.
        mergeParams.put("merge", "HASH_SET");
        mergeParams.put("provisional_merge", Boolean.TRUE);
      }
      cur = b.add(Op.INTERSECT, accessIds, mergeParams);
    } else {
      cur = accessIds.get(0);
    }
    cur = b.add(Op.DEDUPLICATE, one(cur), null);
    cur = b.add(Op.FETCH_TRAJECTORY_CHUNK, one(cur), null);
    cur = addFilterAndSuffix(b, ir, cur, vehicleIdx);
    return b.finish(cur);
  }

  private static String zPart(Builder b, BoundIr ir,
                              double minX, double minY, double maxX, double maxY, String partId) {
    Map<String, Object> p = params("predicate_ref", "/spatial");
    p.put("min_x", Double.valueOf(minX));
    p.put("min_y", Double.valueOf(minY));
    p.put("max_x", Double.valueOf(maxX));
    p.put("max_y", Double.valueOf(maxY));
    p.put("partition", partId);
    return b.add(Op.ZORDER_RANGE_SCAN, noInputs(), p);
  }

  /** Midpoint for system-proved TIME_BIPART (half-open; mid in [start,end]). */
  public static long provedTimeMidpoint(long startMs, long endMs) {
    if (endMs <= startMs) {
      return startMs;
    }
    return startMs + (endMs - startMs) / 2L;
  }

  private static List<String> two(String a, String b) {
    List<String> l = new ArrayList<String>();
    l.add(a);
    l.add(b);
    return l;
  }

  private static PlanEnvelope buildFull(BoundIr ir, int vehicleIdx) {
    Builder b = new Builder(ir, "P_FULL");
    String cur = b.add(Op.FULL_SCAN_CHUNKS, noInputs(), null);
    cur = addFilterAndSuffix(b, ir, cur, vehicleIdx);
    return b.finish(cur);
  }

  /** EXACT_ST_FILTER → PROJECT_TRAJECTORY_IDS → (TOP_K chain | RETURN_TRAJECTORY_IDS). */
  private static String addFilterAndSuffix(Builder b, BoundIr ir, String cur, int vehicleIdx) {
    Map<String, Object> filterParams = new LinkedHashMap<String, Object>();
    List<String> refs = new ArrayList<String>();
    if (ir.temporal != null) {
      refs.add("/temporal");
    }
    if (ir.spatial != null) {
      refs.add("/spatial");
    }
    if (ir.predicates != null) {
      for (int i = 0; i < ir.predicates.size(); i++) {
        refs.add("/predicates/" + i);
      }
    }
    filterParams.put("predicate_refs", refs);
    cur = b.add(Op.EXACT_ST_FILTER, one(cur), filterParams);
    cur = b.add(Op.PROJECT_TRAJECTORY_IDS, one(cur), null);

    boolean topK = ir.result != null && "TOP_K".equals(ir.result.mode);
    if (topK) {
      if (ir.similarity != null && ir.similarity.exclude_reference) {
        cur = b.add(Op.EXCLUDE_REFERENCE, one(cur),
            params("reference_tid", Long.valueOf(ir.similarity.reference_tid)));
      }
      cur = b.add(Op.BATCH_GET_TRAJECTORY, one(cur), null);
      Map<String, Object> simParams = new LinkedHashMap<String, Object>();
      if (ir.similarity != null) {
        simParams.put("metric", ir.similarity.metric);
        simParams.put("reference_tid", Long.valueOf(ir.similarity.reference_tid));
        simParams.put("scope", ir.similarity.scope);
        simParams.put("local_distance", ir.similarity.local_distance);
        simParams.put("normalization", ir.similarity.normalization);
      }
      cur = b.add(Op.SIMILARITY, one(cur), simParams);
      Map<String, Object> topkParams = new LinkedHashMap<String, Object>();
      topkParams.put("k", ir.result.k);
      topkParams.put("tie_breaker", ir.result.tie_breaker);
      cur = b.add(Op.TOP_K, one(cur), topkParams);
    } else {
      cur = b.add(Op.RETURN_TRAJECTORY_IDS, one(cur), null);
    }
    return cur;
  }

  private static List<String> noInputs() {
    return new ArrayList<String>();
  }

  private static List<String> one(String id) {
    List<String> l = new ArrayList<String>();
    l.add(id);
    return l;
  }

  private static Map<String, Object> params(String k, Object v) {
    Map<String, Object> m = new LinkedHashMap<String, Object>();
    m.put(k, v);
    return m;
  }

  /** Small helper accumulating nodes with sequential ids. */
  private static final class Builder {
    private final PlanEnvelope env = new PlanEnvelope();
    private int seq = 0;

    Builder(BoundIr ir, String planId) {
      env.plan_id = planId;
      env.query_id = ir.query_id;
      env.manifest_id = ir.snapshot != null ? ir.snapshot.manifest_id : null;
    }

    String add(Op op, List<String> inputs, Map<String, Object> params) {
      seq++;
      String id = "n" + (seq < 10 ? "0" + seq : String.valueOf(seq));
      env.nodes.add(new PlanNode(id, op, inputs, params));
      return id;
    }

    PlanEnvelope finish(String rootId) {
      env.root = rootId;
      return env;
    }
  }
}
