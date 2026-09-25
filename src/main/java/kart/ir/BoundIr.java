package kart.ir;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * BoundIR POJO used by Oracle and (later) Binder. Schema-validated separately.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class BoundIr {

  public String ir_version = "1.0";
  public String query_id;
  public Source source;
  public Temporal temporal;
  public Spatial spatial;
  public List<Predicate> predicates = new ArrayList<Predicate>();
  public Semantics semantics = new Semantics();
  public Similarity similarity;
  public Result result;
  public Snapshot snapshot;

  public static BoundIr fromJson(String json) throws IOException {
    return new ObjectMapper().readValue(json, BoundIr.class);
  }

  @JsonIgnoreProperties(ignoreUnknown = false)
  public static class Source {
    public String dataset_id;
    public String entity;
  }

  @JsonIgnoreProperties(ignoreUnknown = false)
  public static class Temporal {
    public long start_ms;
    public long end_ms;
  }

  @JsonIgnoreProperties(ignoreUnknown = false)
  public static class Spatial {
    public double min_x;
    public double min_y;
    public double max_x;
    public double max_y;
    public String relation = "INTERSECTS";
    public String boundary = "INCLUDED";
    /** Present when spatial came from a registered region_name (confirm UX). */
    public String region_name;
  }

  @JsonIgnoreProperties(ignoreUnknown = false)
  public static class Predicate {
    public String field;
    public String op;
    public String value;
  }

  @JsonIgnoreProperties(ignoreUnknown = false)
  public static class Semantics {
    public String mode = "OBSERVED_POINT";
    public String coupling = "SAME_POINT";
  }

  @JsonIgnoreProperties(ignoreUnknown = false)
  public static class Similarity {
    public String metric;
    public long reference_tid;
    public String scope = "FULL_TRAJECTORY";
    public boolean exclude_reference = true;
    public String local_distance = "EUCLIDEAN";
    public String normalization = "NONE";
    /** Catalog meta chunk count for reference trajectory (cost ref_len). */
    public Integer reference_chunk_count;
  }

  @JsonIgnoreProperties(ignoreUnknown = false)
  public static class Result {
    public String mode; // TRAJECTORY_IDS | TOP_K
    public Integer k;
    public String tie_breaker = "TID_ASC";
  }

  @JsonIgnoreProperties(ignoreUnknown = false)
  public static class Snapshot {
    public String manifest_id;
    public String semantics_version;
  }
}
