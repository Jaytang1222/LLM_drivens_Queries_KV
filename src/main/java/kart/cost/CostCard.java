package kart.cost;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cost estimate card (design.md §10 / IMPLEMENTATION_PLAN §13.5).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class CostCard {

  public String plan_id;
  public String stage = "FAST";
  public String model_version = CostModel.MODEL_VERSION;
  public boolean calibrated = false;
  public double estimated_ms;
  public Map<String, Object> features = new LinkedHashMap<String, Object>();
  public List<String> main_cost_drivers = new ArrayList<String>();
  public Uncertainty uncertainty = new Uncertainty();

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static final class Uncertainty {
    public String method = "SAMPLE";
    public int sample_size;
    public String label = "HIGH";
  }

  private static final ObjectMapper MAPPER = new ObjectMapper()
      .enable(SerializationFeature.INDENT_OUTPUT);

  public String toJson() throws IOException {
    return MAPPER.writeValueAsString(this);
  }
}
