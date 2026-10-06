package kart.rewrite;

import kart.ir.BoundIr;
import kart.plan.Op;
import kart.plan.PlanBuilder;
import kart.plan.PlanEnvelope;
import kart.plan.PlanNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** REMOVE_ACCESS_BRANCH: drop one candidate-only input of an INTERSECT, keeping every exact predicate. */
public final class SafeRewriteBuilder {

  public static final class Rejected extends Exception {
    public Rejected(String reason) {
      super(reason);
    }
  }

  private SafeRewriteBuilder() {}

  /** Stable, order-independent id for an access branch node. */
  public static String branchId(PlanNode n) {
    switch (n.op) {
      case TIME_RANGE_SCAN: return "temporal_branch";
      case ZORDER_RANGE_SCAN: return "spatial_branch";
      case EQUALITY_LOOKUP: return "vehicle_branch";
      default: return null;
    }
  }

  /** Branch id → node id for every INTERSECT input that passes the structural checks. */
  public static Map<String, String> removableBranches(PlanEnvelope p) {
    Map<String, String> out = new LinkedHashMap<String, String>();
    PlanNode x = soleIntersect(p);
    if (x == null || x.inputs.size() < 2) {
      return out;
    }
    List<String> exactRefs = exactRefsBelowRoot(p, x.id);
    for (String in : x.inputs) {
      PlanNode n = p.node(in);
      String bid = n == null ? null : branchId(n);
      if (bid != null && consumers(p, in).size() == 1 && n.inputs.isEmpty()
          && exactRefs.contains(predicateRef(n))) {
        out.put(bid, in);
      }
    }
    return out;
  }

  public static PlanEnvelope removeAccessBranch(PlanEnvelope p, String branch, BoundIr ir) throws Rejected {
    String target = removableBranches(p).get(branch);
    if (target == null) {
      throw new Rejected("unknown_or_unsafe_target");
    }
    PlanNode x = soleIntersect(p);
    List<String> before = exactRefsBelowRoot(p, x.id);
    PlanEnvelope out = new PlanEnvelope();
    out.query_id = p.query_id;
    out.manifest_id = p.manifest_id;
    out.root = p.root;
    List<String> kept = new ArrayList<String>(x.inputs);
    kept.remove(target);
    String splice = kept.size() == 1 ? kept.get(0) : null;
    for (PlanNode n : p.nodes) {
      if (n.id.equals(target) || (splice != null && n.id.equals(x.id))) {
        continue;
      }
      List<String> inputs = new ArrayList<String>();
      for (String in : n.id.equals(x.id) ? kept : n.inputs) {
        inputs.add(splice != null && in.equals(x.id) ? splice : in);
      }
      out.nodes.add(new PlanNode(n.id, n.op, inputs,
          n.params == null ? null : new LinkedHashMap<String, Object>(n.params)));
    }
    if (!exactRefsBelowRoot(out, kept.get(0)).equals(before)) {
      throw new Rejected("exact_predicates_changed");
    }
    boolean t = false, z = false, h = false;
    for (String k : kept) {
      Op op = p.node(k).op;
      t |= op == Op.TIME_RANGE_SCAN;
      z |= op == Op.ZORDER_RANGE_SCAN;
      h |= op == Op.EQUALITY_LOOKUP;
    }
    PlanEnvelope template = PlanBuilder.buildForAccess(ir, t, z, h);
    if (!template.signature().equals(out.signature())) {
      throw new Rejected("no_supported_template");
    }
    out.plan_id = template.plan_id;
    return out;
  }

  /** Inserts CHUNK_META_FILTER directly before the sole FETCH_TRAJECTORY_CHUNK. */
  public static PlanEnvelope withMetaProbe(PlanEnvelope p) throws Rejected {
    PlanNode fetch = null;
    for (PlanNode n : p.nodes) {
      if (n.op == Op.FETCH_TRAJECTORY_CHUNK) {
        if (fetch != null) {
          throw new Rejected("multiple_fetch");
        }
        fetch = n;
      }
    }
    if (fetch == null || p.node("meta_probe") != null) {
      throw new Rejected("no_fetch_or_already_probed");
    }
    PlanEnvelope out = copy(p, p.plan_id + "+MP");
    for (PlanNode n : out.nodes) {
      if (n.id.equals(fetch.id)) {
        out.nodes.add(out.nodes.indexOf(n), new PlanNode("meta_probe", Op.CHUNK_META_FILTER,
            new ArrayList<String>(n.inputs), null));
        n.inputs.clear();
        n.inputs.add("meta_probe");
        break;
      }
    }
    return out;
  }

  /** Sets one param on every node of {@code op}; rejects when the plan has no such node. */
  public static PlanEnvelope withParam(PlanEnvelope p, Op op, String key, Object value, String tag) throws Rejected {
    PlanEnvelope out = copy(p, tag == null ? p.plan_id : p.plan_id + "+" + tag);
    boolean hit = false;
    for (PlanNode n : out.nodes) {
      if (n.op == op) {
        if (n.params == null) {
          n.params = new LinkedHashMap<String, Object>();
        }
        n.params.put(key, value);
        hit = true;
      }
    }
    if (!hit) {
      throw new Rejected("no_" + op.name().toLowerCase());
    }
    return out;
  }

  private static PlanEnvelope copy(PlanEnvelope p, String planId) {
    PlanEnvelope out = new PlanEnvelope();
    out.plan_id = planId;
    out.query_id = p.query_id;
    out.manifest_id = p.manifest_id;
    out.root = p.root;
    for (PlanNode n : p.nodes) {
      out.nodes.add(new PlanNode(n.id, n.op, new ArrayList<String>(n.inputs),
          n.params == null ? null : new LinkedHashMap<String, Object>(n.params)));
    }
    return out;
  }

  private static PlanNode soleIntersect(PlanEnvelope p) {
    PlanNode found = null;
    for (PlanNode n : p.nodes) {
      if (n.op == Op.UNION) {
        return null;
      }
      if (n.op == Op.INTERSECT) {
        if (found != null) {
          return null;
        }
        found = n;
      }
    }
    return found;
  }

  private static List<String> consumers(PlanEnvelope p, String id) {
    List<String> out = new ArrayList<String>();
    for (PlanNode n : p.nodes) {
      if (n.inputs.contains(id)) {
        out.add(n.id);
      }
    }
    return out;
  }

  /** predicate_refs of the EXACT_ST_FILTER on the single path from {@code from} to the root. */
  @SuppressWarnings("unchecked")
  private static List<String> exactRefsBelowRoot(PlanEnvelope p, String from) {
    String cur = from;
    while (cur != null) {
      List<String> next = consumers(p, cur);
      if (next.size() != 1) {
        return new ArrayList<String>();
      }
      PlanNode n = p.node(next.get(0));
      if (n.op == Op.EXACT_ST_FILTER) {
        Object refs = n.params == null ? null : n.params.get("predicate_refs");
        return refs instanceof List ? new ArrayList<String>((List<String>) refs) : new ArrayList<String>();
      }
      cur = n.id;
    }
    return new ArrayList<String>();
  }

  private static String predicateRef(PlanNode n) {
    Object r = n.params == null ? null : n.params.get("predicate_ref");
    return r == null ? null : r.toString();
  }
}
