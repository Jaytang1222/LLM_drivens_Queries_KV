package kart.cost;

import kart.compile.PhysicalPlan;
import kart.validation.SafePlanHandle;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Select lowest {@code estimated_ms} SafePlan; ties → fewer scan ranges → plan_id (T5.3).
 */
public final class PlanSelector {

  private PlanSelector() {}

  public static final class Scored {
    public final SafePlanHandle handle;
    public final CostCard card;

    public Scored(SafePlanHandle handle, CostCard card) {
      this.handle = handle;
      this.card = card;
    }
  }

  /**
   * @return null if scored is empty
   */
  public static SafePlanHandle select(List<Scored> scored) {
    Scored best = selectScored(scored);
    return best == null ? null : best.handle;
  }

  public static Scored selectScored(List<Scored> scored) {
    if (scored == null || scored.isEmpty()) {
      return null;
    }
    List<Scored> copy = new ArrayList<Scored>(scored);
    Collections.sort(copy, new Comparator<Scored>() {
      @Override
      public int compare(Scored a, Scored b) {
        double ma = a.card != null ? a.card.estimated_ms : Double.POSITIVE_INFINITY;
        double mb = b.card != null ? b.card.estimated_ms : Double.POSITIVE_INFINITY;
        int c = Double.compare(ma, mb);
        if (c != 0) {
          return c;
        }
        int ra = ranges(a);
        int rb = ranges(b);
        c = Integer.compare(ra, rb);
        if (c != 0) {
          return c;
        }
        String ida = planId(a);
        String idb = planId(b);
        return ida.compareTo(idb);
      }
    });
    return copy.get(0);
  }

  private static int ranges(Scored s) {
    if (s.card != null && s.card.features != null && s.card.features.get("scan_ranges") != null) {
      Object v = s.card.features.get("scan_ranges");
      if (v instanceof Number) {
        return ((Number) v).intValue();
      }
    }
    if (s.handle != null) {
      PhysicalPlan p = s.handle.physicalPlan();
      return CostFeaturesExtractor.rangeCount(p);
    }
    return Integer.MAX_VALUE;
  }

  private static String planId(Scored s) {
    if (s.handle != null && s.handle.plan() != null && s.handle.plan().plan_id != null) {
      return s.handle.plan().plan_id;
    }
    if (s.card != null && s.card.plan_id != null) {
      return s.card.plan_id;
    }
    return "";
  }
}
