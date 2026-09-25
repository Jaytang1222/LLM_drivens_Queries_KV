package kart.bench;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * NL workload row for E1 parse suites ({@code nl_ir_v1.json}).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class NlItem {
  public String query_id;
  public String utterance;
  public boolean reject_expected;
  public boolean clarify_expected;
  public String layer;
  /** exact | contains (default exact when gold ids present). */
  public String oracle_mode;
  public List<String> gold_trajectory_ids = new ArrayList<String>();
  public List<String> gold_must_contain = new ArrayList<String>();
  public List<GoldScored> gold_top_k;
  /** Optional gold BoundIR JSON path or inline marker. */
  public String gold_bound_ir_ref;

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static final class GoldScored {
    public long tid;
    public String trajectory_id;
    public double distance;
  }
}
