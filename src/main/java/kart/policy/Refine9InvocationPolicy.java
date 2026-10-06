package kart.policy;

import java.util.*;

/** CALL is reachable for confident opportunities; SKIP never changes the CBO plan. */
public final class Refine9InvocationPolicy {
  public InvocationDecision decide(InvocationDecision g0,String action,Double gain,
      Double conservative,List<String> admissible,Map<String,Object> stability) {
    Map<String,Object> ev=new LinkedHashMap<String,Object>(g0.evidence);ev.putAll(stability);
    ev.put("g2_decision","not_needed");
    long L=g0.estLlmMs,H=g0.estOverheadMs,min=SelectiveInvocationPolicy.minNetMs();
    String skip=null,call=null;
    if(g0.g0==InvocationDecision.Gate.SKIP)skip=g0.skipReason;
    else if(admissible==null || admissible.isEmpty())skip="no_supported_action";
    else if(conservative==null || !Double.isFinite(conservative))skip="insufficient_evidence";
    else if(Boolean.TRUE.equals(stability.get("cbo_stable")))skip="stable_cbo";
    else if(conservative<L+H+min)skip="budget_exhausted";
    else if(!"available".equals(stability.get("stability_status")))skip="insufficient_scenario_evidence";
    else {
      double low=((Number)stability.get("saving_lower_ms")).doubleValue();
      double mean=((Number)stability.get("saving_mean_ms")).doubleValue();
      if(low>=L+H+min)call="stable_opportunity";
      else { // G2 reuses existing conservative estimate, no added data access.
        ev.put("g2_decision","reuse_conservative_evidence");
        if(mean>=L+H+min)call="sensitive_opportunity";else skip="boundary_budget_exhausted";
      }
    }
    long budget=skip==null?Math.min(8000L,Math.max(L,(long)Math.floor(conservative-H-min))):0;
    return new InvocationDecision("v13-selective",g0.g0,skip==null?InvocationDecision.Gate.CALL:InvocationDecision.Gate.SKIP,
        skip,call,action,g0.cboPlanId,budget,L,H,gain,conservative,ev);
  }
}
