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

  /** Returns candidate plans in deterministic order. */
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
    int vehicleIdx = vehicleEqPredicateIndex(ir);
    if (!useT && !useZ && !useH) {
      return buildFull(ir, vehicleIdx);
    }
    String name = planName(useT, useZ, useH);
    return buildIndexed(ir, name, useT, useZ, useH, vehicleIdx);
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
    Builder b = new Builder(ir, name);
    List<String> accessIds = new ArrayList<String>();
    if (useT) {
      accessIds.add(b.add(Op.TIME_RANGE_SCAN, noInputs(), params("predicate_ref", "/temporal")));
    }
    if (useZ) {
      accessIds.add(b.add(Op.ZORDER_RANGE_SCAN, noInputs(), params("predicate_ref", "/spatial")));
    }
    if (useH) {
      accessIds.add(b.add(Op.EQUALITY_LOOKUP, noInputs(),
          params("predicate_ref", "/predicates/" + vehicleIdx)));
    }
    String cur;
    if (accessIds.size() > 1) {
      cur = b.add(Op.INTERSECT, accessIds, null);
    } else {
      cur = accessIds.get(0);
    }
    cur = b.add(Op.DEDUPLICATE, one(cur), null);
    cur = b.add(Op.FETCH_TRAJECTORY_CHUNK, one(cur), null);
    cur = addFilterAndSuffix(b, ir, cur, vehicleIdx);
    return b.finish(cur);
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
