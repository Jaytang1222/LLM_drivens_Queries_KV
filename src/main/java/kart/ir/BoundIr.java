package kart.ir;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
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
  /** OR of half-open time windows; when non-empty, overrides {@link #temporal}. */
  public List<Temporal> temporal_parts;
  public Spatial spatial;
  /** OR of AOIs; when non-empty, overrides {@link #spatial}. */
  public List<Spatial> spatial_parts;
  public List<Predicate> predicates = new ArrayList<Predicate>();
  public Semantics semantics = new Semantics();
  public Similarity similarity;
  public Result result;
  public Snapshot snapshot;

  public static BoundIr fromJson(String json) throws IOException {
    return new ObjectMapper().readValue(json, BoundIr.class);
  }

  public static boolean hasSpatial(BoundIr ir) {
    return ir != null && !spatialsOf(ir).isEmpty();
  }

  public static boolean hasTemporal(BoundIr ir) {
    return ir != null && !temporalsOf(ir).isEmpty();
  }

  public static List<Spatial> spatialsOf(BoundIr ir) {
    if (ir == null) {
      return Collections.emptyList();
    }
    if (ir.spatial_parts != null && !ir.spatial_parts.isEmpty()) {
      return ir.spatial_parts;
    }
    if (ir.spatial != null) {
      return Collections.singletonList(ir.spatial);
    }
    return Collections.emptyList();
  }

  public static List<Temporal> temporalsOf(BoundIr ir) {
    if (ir == null) {
      return Collections.emptyList();
    }
    if (ir.temporal_parts != null && !ir.temporal_parts.isEmpty()) {
      return ir.temporal_parts;
    }
    if (ir.temporal != null) {
      return Collections.singletonList(ir.temporal);
    }
    return Collections.emptyList();
  }

  /** Inclusive union bbox of all AOIs, or null. */
  public static Spatial spatialUnion(BoundIr ir) {
    List<Spatial> parts = spatialsOf(ir);
    if (parts.isEmpty()) {
      return null;
    }
    Spatial u = new Spatial();
    u.min_x = parts.get(0).min_x;
    u.min_y = parts.get(0).min_y;
    u.max_x = parts.get(0).max_x;
    u.max_y = parts.get(0).max_y;
    u.relation = parts.get(0).relation;
    u.boundary = parts.get(0).boundary;
    for (int i = 1; i < parts.size(); i++) {
      Spatial s = parts.get(i);
      if (s.min_x < u.min_x) {
        u.min_x = s.min_x;
      }
      if (s.min_y < u.min_y) {
        u.min_y = s.min_y;
      }
      if (s.max_x > u.max_x) {
        u.max_x = s.max_x;
      }
      if (s.max_y > u.max_y) {
        u.max_y = s.max_y;
      }
    }
    return u;
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
