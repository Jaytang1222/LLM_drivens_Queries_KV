package kart.bench;

import kart.exec.QueryResult;
import kart.ir.BoundIr;

import java.util.LinkedHashMap;
import java.util.Map;

/** Outcome of one E1 parse arm. */
public final class ParseTrialResult {
  public boolean ok;
  public Boolean ok_ex;
  public Boolean ok_ir_valid;
  public Boolean reject_expected;
  public Boolean reject_actual;
  public Boolean clarify_expected;
  public Boolean clarify_actual;
  public Long t_parse_ms;
  public Long t_parse_total_ms;
  public Long t_parse_model_ms;
  public Long t_adapter_startup_ms;
  public Long llm_calls;
  public Long tokens_in;
  public Long tokens_out;
  public String error;
  public String draft_ir_json;
  public BoundIr bound;
  public QueryResult queryResult;
  public Map<String, Object> extras = new LinkedHashMap<String, Object>();
}
