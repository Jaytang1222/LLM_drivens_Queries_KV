package kart.plan;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A single node of the logical plan DAG (schemas/plan.schema.json items).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class PlanNode {

  public String id;
  public Op op;
  public List<String> inputs = new ArrayList<String>();
  public Map<String, Object> params;

  public PlanNode() {}

  public PlanNode(String id, Op op, List<String> inputs, Map<String, Object> params) {
    this.id = id;
    this.op = op;
    this.inputs = inputs != null ? inputs : new ArrayList<String>();
    this.params = params;
  }

  public Map<String, Object> ensureParams() {
    if (params == null) {
      params = new LinkedHashMap<String, Object>();
    }
    return params;
  }
}
