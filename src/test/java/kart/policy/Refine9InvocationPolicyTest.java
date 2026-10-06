package kart.policy;

import kart.cost.BenefitCalibrator;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class Refine9InvocationPolicyTest {
  private InvocationDecision upstream() {
    return new InvocationDecision("test",InvocationDecision.Gate.CALL,InvocationDecision.Gate.CALL,
        null,"test","KEEP","P_TZ",0,900,50,null,null,Collections.emptyMap());
  }
  private Map<String,Object> evaluate(double gain,double... errors) {
    List<Map<String,Double>> rows=new ArrayList<>();
    for(double error:errors){Map<String,Double> r=new LinkedHashMap<>();r.put("P_T",0d);r.put("P_TZ",error);rows.add(r);}
    return DecisionStabilityEvaluator.evaluate("P_TZ",Collections.singletonMap("P_T",gain),rows);
  }
  @Test void actualConfidenceChainHasReachableCall() {
    double gain=2400;double conservative=BenefitCalibrator.conservativeNetS(gain,333d);
    List<String> confident=conservative>=BenefitCalibrator.resolveMinAdoptSMs()?Arrays.asList("P_T"):Collections.emptyList();
    String action=confident.isEmpty()?"KEEP":confident.get(0);
    // Exactly the upstream D(q) chain which formerly short-circuited to SKIP.
    InvocationDecision d=new Refine9InvocationPolicy().decide(upstream(),action,gain,conservative,
        Arrays.asList("P_T"),evaluate(gain,0,100,200));
    assertTrue(d.shouldCallLlm());assertEquals("P_T",d.deterministicAction);assertTrue(d.budgetLlmMs>=900);
  }
  @Test void unstableButSmallBudgetSkips() {
    InvocationDecision d=new Refine9InvocationPolicy().decide(upstream(),"KEEP",300d,-33d,
        Arrays.asList("P_T"),evaluate(300,0,400,-100));
    assertFalse(d.shouldCallLlm());assertEquals("budget_exhausted",d.skipReason);
  }
  @Test void stableCboSkipsEvenWithNoisyPointEstimate() {
    InvocationDecision d=new Refine9InvocationPolicy().decide(upstream(),"P_T",2000d,1667d,
        Arrays.asList("P_T"),evaluate(2000,3000,4000,5000));
    assertFalse(d.shouldCallLlm());assertEquals("stable_cbo",d.skipReason);
  }
  @Test void missingScenariosNeverInventConfidence() {
    InvocationDecision d=new Refine9InvocationPolicy().decide(upstream(),"P_T",2400d,2067d,
        Arrays.asList("P_T"),evaluate(2400));
    assertFalse(d.shouldCallLlm());assertEquals("insufficient_scenario_evidence",d.skipReason);
  }
  @Test void preservesJointResidualsAndCapsCandidates() {
    Map<String,Double> gains=new LinkedHashMap<>();gains.put("P_T",2000d);gains.put("P_Z",1000d);
    List<Map<String,Double>> rows=Arrays.asList(new LinkedHashMap<String,Double>(){{put("P_TZ",500d);put("P_T",500d);put("P_Z",500d);}});
    Map<String,Object> result=DecisionStabilityEvaluator.evaluate("P_TZ",gains,rows);
    assertEquals(2000d,result.get("saving_lower_ms"));
  }
}
