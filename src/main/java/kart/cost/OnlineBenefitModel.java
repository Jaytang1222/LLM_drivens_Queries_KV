package kart.cost;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Paths;

/** Optional development model. Same FastCost features offline and online. */
public final class OnlineBenefitModel {
  private static final OnlineBenefitModel INSTANCE = load();
  private final double[] coefficients;
  private final boolean linear;
  public final double marginMs;
  private OnlineBenefitModel(double[] c, double m, boolean linear) { coefficients=c; marginMs=m; this.linear=linear; }
  public static OnlineBenefitModel get() { return INSTANCE; }
  private static OnlineBenefitModel load() {
    String path=System.getenv("KART_ONLINE_BENEFIT_MODEL");
    if(path==null || path.trim().isEmpty()) return null;
    try {
      JsonNode j=new ObjectMapper().readTree(Paths.get(path).toFile());
      if(j.path("training_queries").asInt()<20) throw new IllegalArgumentException("insufficient training queries");
      double[] c=new double[3];
      for(int i=0;i<3;i++){c[i]=j.path("coefficients").get(i).asDouble();if(!Double.isFinite(c[i]) || c[i]<0)throw new IllegalArgumentException("invalid coefficient");}
      double margin=j.path("margin_ms").asDouble(Double.NaN);
      if(!Double.isFinite(margin)||margin<0)throw new IllegalArgumentException("invalid margin");
      String basis=j.path("basis").asText("log");
      if(!"linear".equals(basis) && !"log".equals(basis))throw new IllegalArgumentException("unknown basis");
      return new OnlineBenefitModel(c,margin,"linear".equals(basis));
    } catch(Exception e){throw new IllegalStateException("Invalid online benefit model: "+path,e);}
  }
  public double predict(CostCard card) {
    String[][] keys={{"scan_ranges","seek_ranges"},{"fetch_gets","estimated_candidate_chunks"},{"estimated_index_rows","decode_rows"}};
    double result=0;
    if(card==null || card.features==null)return Double.NaN;
    for(int i=0;i<3;i++){
      Object v=card.features.get(keys[i][0]);if(v==null)v=card.features.get(keys[i][1]);
      if(!(v instanceof Number))return Double.NaN;
      double d=((Number)v).doubleValue();if(!Double.isFinite(d)||d<0)return Double.NaN;
      result+=coefficients[i]*(linear?d/1000.0:Math.log1p(d));
    }
    return result;
  }
}
