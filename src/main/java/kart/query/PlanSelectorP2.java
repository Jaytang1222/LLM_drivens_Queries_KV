package kart.query;

import kart.plan.PlanEnvelope;
import kart.validation.SafePlanHandle;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Fixed preference order for P2 (before cost-model PlanSelector in P5):
 * P_TZ &gt; P_T &gt; P_Z &gt; P_H &gt; P_TH &gt; P_ZH &gt; P_TZH &gt; P_FULL.
 */
public final class PlanSelectorP2 {

  public static final List<String> PREFERENCE = CollectionsUnmodifiable.copy(Arrays.asList(
      "P_TZ", "P_T", "P_Z", "P_H", "P_TH", "P_ZH", "P_TZH", "P_FULL"
  ));

  private PlanSelectorP2() {}

  /**
   * Select the first preferred plan_id present among safe handles.
   * @return null if none match
   */
  public static SafePlanHandle select(List<SafePlanHandle> safe) {
    if (safe == null || safe.isEmpty()) {
      return null;
    }
    Map<String, SafePlanHandle> byId = new HashMap<String, SafePlanHandle>();
    for (SafePlanHandle h : safe) {
      PlanEnvelope p = h.plan();
      if (p != null && p.plan_id != null && !byId.containsKey(p.plan_id)) {
        byId.put(p.plan_id, h);
      }
    }
    for (String id : PREFERENCE) {
      SafePlanHandle h = byId.get(id);
      if (h != null) {
        return h;
      }
    }
    // fallback: first safe handle in input order
    return safe.get(0);
  }

  private static final class CollectionsUnmodifiable {
    static List<String> copy(List<String> in) {
      return java.util.Collections.unmodifiableList(new ArrayList<String>(in));
    }
  }
}
