package kart.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.util.*;

/** Five frozen joint cost-residual scenarios; never scans data or calls a model. */
public final class DecisionStabilityEvaluator {
  private static final List<Map<String,Double>> SCENARIOS = load();
  private static List<Map<String,Double>> load() {
    String path=System.getenv("KART_STABILITY_SCENARIOS");
    if(path==null || path.isEmpty()) return Collections.emptyList();
    try {
      JsonNode root=new ObjectMapper().readTree(new File(path));
      List<Map<String,Double>> out=new ArrayList<Map<String,Double>>();
      if(root.path("scenarios").size()<3 || root.path("scenarios").size()>5)
        throw new IllegalArgumentException("need 3..5 scenarios");
      for(JsonNode row:root.path("scenarios")) {
        Map<String,Double> m=new LinkedHashMap<String,Double>();
        Iterator<Map.Entry<String,JsonNode>> it=row.path("cost_residual_ms").fields();
        while(it.hasNext()) {Map.Entry<String,JsonNode> e=it.next(); double v=e.getValue().asDouble(Double.NaN);
          if(!Double.isFinite(v))throw new IllegalArgumentException("nonfinite residual");m.put(e.getKey(),v);}
        out.add(m);
      }
      return out;
    } catch(Exception e) {throw new IllegalStateException("Invalid stability scenarios",e);}
  }
  public static Map<String,Object> evaluate(String cbo, Map<String,Double> gains) {
    return evaluate(cbo,gains,SCENARIOS);
  }
  public static Map<String,Object> evaluate(String cbo, Map<String,Double> gains,
      List<Map<String,Double>> scenarios) {
    Map<String,Object> out=new LinkedHashMap<String,Object>();
    List<Double> bests=new ArrayList<Double>(); Set<String> winners=new LinkedHashSet<String>();
    for(Map<String,Double> residual:scenarios) {
      if(!residual.containsKey(cbo))continue;
      double best=0;String winner=cbo;int n=0;
      for(Map.Entry<String,Double> e:gains.entrySet()) {
        if(n++==3)break;
        if(e.getValue()==null || !Double.isFinite(e.getValue()) || !residual.containsKey(e.getKey()))continue;
        double s=e.getValue()-(residual.get(cbo)-residual.get(e.getKey()));
        if(s>best){best=s;winner=e.getKey();}
      }
      bests.add(best);winners.add(winner);
    }
    out.put("scenario_count",bests.size()); out.put("scenario_source","frozen_query_joint_cost_residuals");
    out.put("stability_status",bests.size()>=3?"available":"unknown");
    out.put("scenario_winners",new ArrayList<String>(winners));
    if(!bests.isEmpty()) {
      out.put("saving_lower_ms",Collections.min(bests));out.put("saving_upper_ms",Collections.max(bests));
      out.put("saving_mean_ms",bests.stream().mapToDouble(Double::doubleValue).average().getAsDouble());
      out.put("ranking_sensitive",winners.size()>1);
      out.put("cbo_stable",winners.size()==1 && winners.contains(cbo));
    }
    return out;
  }
}
