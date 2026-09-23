package kart.ir;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DraftIR POJO — LLM output; fields may be missing (T3.4).
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public final class DraftIr {

  public String ir_version = "1.0";
  public String query_id;
  public Source source;
  public Temporal temporal;
  public Spatial spatial;
  public List<Predicate> predicates = new ArrayList<Predicate>();
  public Semantics semantics;
  public Similarity similarity;
  public Result result;
  public Map<String, Object> sources = new LinkedHashMap<String, Object>();
  public List<String> missing = new ArrayList<String>();

  public static DraftIr fromJson(String json) throws IOException {
    return new ObjectMapper().readValue(json, DraftIr.class);
  }

  @JsonIgnoreProperties(ignoreUnknown = false)
  public static class Source {
    public String dataset_id;
    public String entity;
  }

  @JsonIgnoreProperties(ignoreUnknown = false)
  public static class Temporal {
    public String start;
    public String end;
    public String boundary;
  }

  @JsonIgnoreProperties(ignoreUnknown = false)
  public static class Spatial {
    public Geometry geometry;
    public String region_name;
    public String relation;
    public String boundary;
  }

  @JsonIgnoreProperties(ignoreUnknown = false)
  public static class Geometry {
    public String type;
    public Double min_lon;
    public Double min_lat;
    public Double max_lon;
    public Double max_lat;
  }

  @JsonIgnoreProperties(ignoreUnknown = false)
  public static class Predicate {
    public String field;
    public String op;
    public String value;
  }

  @JsonIgnoreProperties(ignoreUnknown = false)
  public static class Semantics {
    public String mode;
    public String coupling;
  }

  @JsonIgnoreProperties(ignoreUnknown = false)
  public static class Similarity {
    public String metric;
    public String reference_trajectory_id;
    public Boolean exclude_reference;
  }

  @JsonIgnoreProperties(ignoreUnknown = false)
  public static class Result {
    public String mode;
    public Integer k;
  }
}
