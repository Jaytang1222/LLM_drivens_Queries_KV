package kart.cost;

import kart.ir.BoundIr;
import kart.plan.PlanBuilder;
import kart.search.IndexId;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Query-shape router for ADV inject + Final preference (solution B).
 * Fat temporal windows → spatial XZ; short temporal / vehicle EQ → classic only.
 * Injects at most two templates to keep ADV {@code t_plan} small.
 * TShape inject defaults on for fat spatial route ({@code KART_ADV_INJECT_TS=0} to disable).
 */
public final class AdvQueryRouter {

  /** ≥ this temporal span (ms) → treat as fat window (prefer P_XZ). Default 12h. */
  static final long DEFAULT_FAT_TEMPORAL_MS = 12L * 3600L * 1000L;
  /** Below this temporal span (ms) → short window (force classic). Default 6h. */
  static final long DEFAULT_SHORT_TEMPORAL_MS = 6L * 3600L * 1000L;

  public enum Route {
    /** Prefer spatial-only / TIME+XZ. */
    SPATIAL_XZ,
    /** Short window / vehicle: no ADV inject. */
    CLASSIC_ONLY,
    /** Medium span: keep one XZ + one XZT∩XZ. */
    HYBRID
  }

  private AdvQueryRouter() {}

  public static boolean forceClassic(BoundIr ir) {
    if (ir == null) {
      return false;
    }
    if (PlanBuilder.vehicleEqPredicateIndex(ir) >= 0) {
      return true;
    }
    // Multi-AOI / multi-window OR: force-plan on frag showed classic P_Z wall-clock
    // beats P_XZ/TS; keep ADV inject for single-rect fat shapes only.
    if (ir.spatial_parts != null && ir.spatial_parts.size() > 1) {
      return true;
    }
    if (ir.temporal_parts != null && ir.temporal_parts.size() > 1) {
      return true;
    }
    Long dt = temporalSpanMs(ir);
    if (dt == null) {
      return false;
    }
    long sh = envLong("KART_ADV_SHORT_TEMPORAL_MS", DEFAULT_SHORT_TEMPORAL_MS);
    return dt.longValue() < sh;
  }

  public static Route route(BoundIr ir) {
    if (ir == null) {
      return Route.HYBRID;
    }
    if (forceClassic(ir)) {
      return Route.CLASSIC_ONLY;
    }
    Long dt = temporalSpanMs(ir);
    if (dt == null) {
      return BoundIr.hasSpatial(ir) ? Route.SPATIAL_XZ : Route.HYBRID;
    }
    long fat = envLong("KART_ADV_FAT_TEMPORAL_MS", DEFAULT_FAT_TEMPORAL_MS);
    if (dt.longValue() >= fat) {
      return Route.SPATIAL_XZ;
    }
    return Route.HYBRID;
  }

  /** At most two ADV index-sets for BeamSearch inject; empty when classic-only. */
  public static List<List<String>> injectSpecs(BoundIr ir) {
    List<List<String>> out = new ArrayList<List<String>>(2);
    if (ir == null || forceClassic(ir)) {
      return out;
    }
    Route r = route(ir);
    boolean hasT = BoundIr.hasTemporal(ir);
    boolean hasS = BoundIr.hasSpatial(ir);
    // Default ON so fat queries can admit P_TS when force wall-clock favors it.
    boolean injectTs = !envFlagOff("KART_ADV_INJECT_TS");
    if (r == Route.SPATIAL_XZ) {
      // Fat: pick one ADV by bbox area so mix coverage keeps both XZ and TS.
      // Mega-scale (force: XZ beats TS) → XZ; wide-scale (force: TS beats XZ) → TS.
      double area = spatialArea(ir);
      // T-Drive: wide≈1.2e8–1.6e8, mega≈3.9e8–4.3e8. force_mix8: XZTXZ wins mega;
      // wide_1 (≥1.5e8) keeps a pure-XZ pocket; smaller wides keep TS.
      double mega = envDouble("KART_ADV_MEGA_AREA", 250_000_000.0d);
      double xzWide = envDouble("KART_ADV_XZ_WIDE_AREA", 150_000_000.0d);
      if (hasT && hasS && area >= mega) {
        out.add(Arrays.asList(IndexId.XZT, IndexId.XZ));
      } else if (hasS && area >= xzWide) {
        out.add(Collections.singletonList(IndexId.XZ));
      } else if (hasS && injectTs) {
        out.add(Collections.singletonList(IndexId.TSHAPE));
      } else if (hasS) {
        out.add(Collections.singletonList(IndexId.XZ));
      }
    } else if (r == Route.HYBRID) {
      // Single XZT∩XZ inject: cuts Final-cost t_plan (xzt_2 was losing to FULL on plan).
      if (hasT && hasS) {
        out.add(Arrays.asList(IndexId.XZT, IndexId.XZ));
      } else if (hasT) {
        out.add(Collections.singletonList(IndexId.XZT));
      } else if (hasS) {
        out.add(Collections.singletonList(IndexId.XZ));
      }
    }
    return out;
  }

  /**
   * Lower is better. Used to break ties among range-admissible ADV plans.
   */
  public static int preferenceRank(String planId, Route route) {
    if (planId == null) {
      return 100;
    }
    if (route == Route.SPATIAL_XZ) {
      // Inject already splits mega→XZ / wide→TS; ties prefer the injected primary.
      if ("P_XZ".equals(planId)) {
        return 0;
      }
      if (planId.equals("P_TS") || planId.startsWith("P_TS")) {
        return 1;
      }
    }
    if (planId.equals("P_TS") || planId.startsWith("P_TS")) {
      return 5;
    }
    if (route == Route.SPATIAL_XZ || route == Route.HYBRID || route == Route.CLASSIC_ONLY) {
      if ("P_XZ".equals(planId)) {
        return 0;
      }
      if ("P_TXZ".equals(planId) || planId.equals("P_TXZ")) {
        return 1;
      }
      if (planId.contains("XZ") && !planId.contains("XZT") && planId.startsWith("P_T")) {
        return 1;
      }
      if ("P_XZTZ".equals(planId)) {
        return 2;
      }
      if ("P_XZTXZ".equals(planId)) {
        return 3;
      }
      if ("P_XZT".equals(planId)) {
        return 4;
      }
      return 10;
    }
    return 10;
  }

  static Long temporalSpanMs(BoundIr ir) {
    if (ir == null) {
      return null;
    }
    if (ir.temporal_parts != null && !ir.temporal_parts.isEmpty()) {
      long min = Long.MAX_VALUE;
      long max = Long.MIN_VALUE;
      for (BoundIr.Temporal w : ir.temporal_parts) {
        if (w == null) {
          continue;
        }
        if (w.start_ms < min) {
          min = w.start_ms;
        }
        if (w.end_ms > max) {
          max = w.end_ms;
        }
      }
      if (min == Long.MAX_VALUE || max < min) {
        return null;
      }
      return Long.valueOf(max - min);
    }
    if (ir.temporal == null) {
      return null;
    }
    long dt = ir.temporal.end_ms - ir.temporal.start_ms;
    return dt >= 0L ? Long.valueOf(dt) : null;
  }

  static double spatialArea(BoundIr ir) {
    if (ir == null) {
      return 0.0d;
    }
    BoundIr.Spatial s = BoundIr.spatialUnion(ir);
    if (s == null) {
      return 0.0d;
    }
    double w = s.max_x - s.min_x;
    double h = s.max_y - s.min_y;
    if (!(w > 0.0d) || !(h > 0.0d)) {
      return 0.0d;
    }
    return w * h;
  }

  private static double envDouble(String key, double def) {
    String v = System.getenv(key);
    if (v == null || v.trim().isEmpty()) {
      return def;
    }
    try {
      double d = Double.parseDouble(v.trim());
      return d >= 0.0d ? d : def;
    } catch (NumberFormatException e) {
      return def;
    }
  }

  private static boolean envFlag(String key) {
    String v = System.getenv(key);
    if (v == null || v.trim().isEmpty()) {
      return false;
    }
    v = v.trim().toLowerCase();
    return v.equals("1") || v.equals("true") || v.equals("yes") || v.equals("on");
  }

  /** True only when explicitly set to 0/false/off (unset → not off). */
  private static boolean envFlagOff(String key) {
    String v = System.getenv(key);
    if (v == null || v.trim().isEmpty()) {
      return false;
    }
    v = v.trim().toLowerCase();
    return v.equals("0") || v.equals("false") || v.equals("no") || v.equals("off");
  }

  private static long envLong(String key, long def) {
    String v = System.getenv(key);
    if (v == null || v.trim().isEmpty()) {
      return def;
    }
    try {
      long d = Long.parseLong(v.trim());
      return d > 0L ? d : def;
    } catch (NumberFormatException e) {
      return def;
    }
  }
}
