package kart.compile;

import kart.codec.Bytes;
import kart.codec.PrefixSuccessor;
import kart.geo.Rect;
import kart.ir.BoundIr;
import kart.plan.Op;
import kart.plan.PlanEnvelope;
import kart.plan.PlanNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Compiles a logical PlanEnvelope + BoundIr against a LayoutContext into a PhysicalPlan.
 */
public final class QueryCompiler {

  public static final String COMPILER_VERSION = "kart-compiler-1.0";

  private final LayoutContext layout;

  public QueryCompiler(LayoutContext layout) {
    this.layout = layout;
  }

  public PhysicalPlan compile(PlanEnvelope env, BoundIr ir) {
    PhysicalPlan phys = new PhysicalPlan();
    phys.manifestId = env.manifest_id;
    phys.compilerVersion = COMPILER_VERSION;
    phys.localDag = env.plan_id;
    phys.layoutHash = sha256Hex(layout.canonicalString());
    phys.fetchSpec.put("meta_table", layout.tableMeta);
    phys.fetchSpec.put("raw_table", layout.tableRaw);

    TimeIndexAdapter time = new TimeIndexAdapter(layout);
    ZOrderIndexAdapter zorder = new ZOrderIndexAdapter(layout);
    XzIndexAdapter xz = new XzIndexAdapter(layout);
    TShapeIndexAdapter tshape = new TShapeIndexAdapter(layout);
    XztIndexAdapter xzt = new XztIndexAdapter(layout);
    HashIndexAdapter hash = new HashIndexAdapter(layout);

    for (PlanNode n : env.nodes) {
      if (n.op == null || !n.op.isAccess()) {
        continue;
      }
      switch (n.op) {
        case TIME_RANGE_SCAN: {
          if (!BoundIr.hasTemporal(ir)) {
            throw new IllegalArgumentException("plan has TIME_RANGE_SCAN but IR has no temporal");
          }
          if (n.params != null
              && (n.params.get("start_ms") instanceof Number
                  || n.params.get("end_ms") instanceof Number)) {
            List<BoundIr.Temporal> temps = BoundIr.temporalsOf(ir);
            long startMs = temps.get(0).start_ms;
            long endMs = temps.get(0).end_ms;
            Object s = n.params.get("start_ms");
            Object e = n.params.get("end_ms");
            if (s instanceof Number) {
              startMs = ((Number) s).longValue();
            }
            if (e instanceof Number) {
              endMs = ((Number) e).longValue();
            }
            phys.scanTasks.addAll(time.scanTasks(startMs, endMs, n.id));
          } else {
            for (BoundIr.Temporal w : BoundIr.temporalsOf(ir)) {
              phys.scanTasks.addAll(time.scanTasks(w.start_ms, w.end_ms, n.id));
            }
          }
          break;
        }
        case ZORDER_RANGE_SCAN: {
          if (!BoundIr.hasSpatial(ir)) {
            throw new IllegalArgumentException("plan has ZORDER_RANGE_SCAN but IR has no spatial");
          }
          List<Rect> zRects = spatialRects(ir, n);
          int maxRanges = n.params != null && n.params.get("max_ranges") instanceof Number
              ? ((Number) n.params.get("max_ranges")).intValue() : layout.maxZorderRanges;
          int per = Math.max(1, maxRanges / Math.max(1, zRects.size()));
          for (Rect rect : zRects) {
            phys.scanTasks.addAll(zorder.scanTasks(rect, n.id, per));
          }
          break;
        }
        case XZ_RANGE_SCAN: {
          if (!BoundIr.hasSpatial(ir)) {
            throw new IllegalArgumentException("plan has XZ_RANGE_SCAN but IR has no spatial");
          }
          List<Rect> xzRects = spatialRects(ir, n);
          int xzMax = n.params != null && n.params.get("max_ranges") instanceof Number
              ? ((Number) n.params.get("max_ranges")).intValue() : layout.maxXzRanges;
          phys.scanTasks.addAll(xz.scanTasks(xzRects, n.id, xzMax));
          break;
        }
        case TSHAPE_RANGE_SCAN: {
          if (!BoundIr.hasSpatial(ir)) {
            throw new IllegalArgumentException("plan has TSHAPE_RANGE_SCAN but IR has no spatial");
          }
          List<Rect> tsRects = spatialRects(ir, n);
          int tsMax = n.params != null && n.params.get("max_ranges") instanceof Number
              ? ((Number) n.params.get("max_ranges")).intValue() : layout.maxXzRanges;
          tsMax = TShapeIndexAdapter.configuredMaxRanges(tsMax);
          phys.scanTasks.addAll(tshape.scanTasks(tsRects, n.id, tsMax));
          break;
        }
        case XZT_RANGE_SCAN: {
          if (!BoundIr.hasTemporal(ir)) {
            throw new IllegalArgumentException("plan has XZT_RANGE_SCAN but IR has no temporal");
          }
          int xztMax = n.params != null && n.params.get("max_ranges") instanceof Number
              ? ((Number) n.params.get("max_ranges")).intValue() : layout.maxXzRanges;
          if (n.params != null
              && (n.params.get("start_ms") instanceof Number
                  || n.params.get("end_ms") instanceof Number)) {
            List<BoundIr.Temporal> temps = BoundIr.temporalsOf(ir);
            long xztStart = temps.get(0).start_ms;
            long xztEnd = temps.get(0).end_ms;
            if (n.params.get("start_ms") instanceof Number) {
              xztStart = ((Number) n.params.get("start_ms")).longValue();
            }
            if (n.params.get("end_ms") instanceof Number) {
              xztEnd = ((Number) n.params.get("end_ms")).longValue();
            }
            phys.scanTasks.addAll(xzt.scanTasks(xztStart, xztEnd, n.id, xztMax));
          } else {
            List<BoundIr.Temporal> temps = BoundIr.temporalsOf(ir);
            int per = Math.max(1, xztMax / Math.max(1, temps.size()));
            for (BoundIr.Temporal w : temps) {
              phys.scanTasks.addAll(xzt.scanTasks(w.start_ms, w.end_ms, n.id, per));
            }
          }
          break;
        }
        case EQUALITY_LOOKUP: {
          BoundIr.Predicate p = resolvePredicate(ir, n);
          phys.scanTasks.addAll(hash.scanTasks(p.value, n.id));
          break;
        }
        case FULL_SCAN_CHUNKS: {
          addFullScanTasks(phys, n.id);
          break;
        }
        default:
          break;
      }
    }

    phys.queryHash = sha256Hex(nullSafe(ir.query_id) + "|" + env.signature()
        + "|" + phys.layoutHash + "|" + COMPILER_VERSION);
    return phys;
  }

  private static List<Rect> spatialRects(BoundIr ir, PlanNode n) {
    if (n.params != null
        && (n.params.get("min_x") instanceof Number || n.params.get("min_y") instanceof Number
            || n.params.get("max_x") instanceof Number || n.params.get("max_y") instanceof Number)) {
      BoundIr.Spatial u = BoundIr.spatialUnion(ir);
      double minX = u.min_x;
      double minY = u.min_y;
      double maxX = u.max_x;
      double maxY = u.max_y;
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
      List<Rect> one = new ArrayList<Rect>(1);
      one.add(new Rect(minX, minY, maxX, maxY));
      return one;
    }
    List<BoundIr.Spatial> parts = BoundIr.spatialsOf(ir);
    List<Rect> out = new ArrayList<Rect>(parts.size());
    for (BoundIr.Spatial s : parts) {
      out.add(new Rect(s.min_x, s.min_y, s.max_x, s.max_y));
    }
    return out;
  }

  private void addFullScanTasks(PhysicalPlan phys, String nodeId) {
    for (int shard = 0; shard < layout.shardCount; shard++) {
      byte[] start = Bytes.u8(shard);
      byte[] stop;
      if (shard + 1 <= 0xFF) {
        stop = Bytes.u8(shard + 1);
      } else {
        Optional<byte[]> succ = PrefixSuccessor.of(start);
        if (!succ.isPresent()) {
          throw new IllegalStateException("no successor for shard " + shard);
        }
        stop = succ.get();
      }
      phys.scanTasks.add(new ScanTask(layout.tableRaw, start, stop, nodeId, shard, "full:shard=" + shard));
    }
  }

  /** Resolve params.predicate_ref of form "/predicates/N" to the IR predicate. */
  public static BoundIr.Predicate resolvePredicate(BoundIr ir, PlanNode n) {
    Object ref = n.params == null ? null : n.params.get("predicate_ref");
    if (!(ref instanceof String) || !((String) ref).startsWith("/predicates/")) {
      throw new IllegalArgumentException(
          "EQUALITY_LOOKUP node " + n.id + " missing /predicates/N predicate_ref");
    }
    String idxStr = ((String) ref).substring("/predicates/".length());
    int idx;
    try {
      idx = Integer.parseInt(idxStr);
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("bad predicate_ref index: " + ref);
    }
    List<BoundIr.Predicate> preds = ir.predicates;
    if (preds == null || idx < 0 || idx >= preds.size()) {
      throw new IllegalArgumentException("predicate_ref out of range: " + ref);
    }
    return preds.get(idx);
  }

  public static String sha256Hex(String s) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      return ScanTask.toHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  private static String nullSafe(String s) {
    return s == null ? "" : s;
  }
}
