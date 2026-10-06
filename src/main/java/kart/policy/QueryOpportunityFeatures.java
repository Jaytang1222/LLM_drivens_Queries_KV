package kart.policy;

import kart.ir.BoundIr;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Cheap / extended opportunity features for selective invocation
 * (comparative_refine_8 §3.2 / §11.2). Cheap layer must not scan HBase.
 */
public final class QueryOpportunityFeatures {
  public final String queryId;
  public final String cboPlanId;
  public final Double cboEstimatedMs;
  public final boolean topK;
  public final boolean similarity;
  public final String similarityMetric;
  public final long temporalSpanMs;
  public final double spatialArea;
  public final boolean hasTemporal;
  public final boolean hasSpatial;
  public final boolean jointSelected;
  public final boolean singleIndexSelected;
  public final String layer; // cheap | extended

  public QueryOpportunityFeatures(String queryId, String cboPlanId, Double cboEstimatedMs,
                                  boolean topK, boolean similarity, String similarityMetric,
                                  long temporalSpanMs, double spatialArea,
                                  boolean hasTemporal, boolean hasSpatial,
                                  boolean jointSelected, boolean singleIndexSelected,
                                  String layer) {
    this.queryId = queryId;
    this.cboPlanId = cboPlanId;
    this.cboEstimatedMs = cboEstimatedMs;
    this.topK = topK;
    this.similarity = similarity;
    this.similarityMetric = similarityMetric;
    this.temporalSpanMs = temporalSpanMs;
    this.spatialArea = spatialArea;
    this.hasTemporal = hasTemporal;
    this.hasSpatial = hasSpatial;
    this.jointSelected = jointSelected;
    this.singleIndexSelected = singleIndexSelected;
    this.layer = layer;
  }

  public static QueryOpportunityFeatures fromCheap(BoundIr ir, String cboPlanId,
                                                   Double cboEstimatedMs) {
    boolean topK = ir != null && ir.result != null && "TOP_K".equals(ir.result.mode);
    boolean sim = ir != null && ir.similarity != null && ir.similarity.metric != null;
    String metric = sim ? ir.similarity.metric : null;
    long span = 0L;
    boolean hasT = ir != null && ir.temporal != null;
    if (hasT) {
      span = Math.max(0L, ir.temporal.end_ms - ir.temporal.start_ms);
    }
    double area = 0.0d;
    boolean hasS = ir != null && ir.spatial != null;
    if (hasS) {
      double w = ir.spatial.max_x - ir.spatial.min_x;
      double h = ir.spatial.max_y - ir.spatial.min_y;
      if (w > 0 && h > 0) {
        area = w * h;
      }
    }
    String pid = cboPlanId == null ? "" : cboPlanId;
    boolean joint = pid.contains("TZ") || pid.contains("TH") || pid.contains("ZH")
        || "P_FULL".equals(pid);
    boolean single = "P_T".equals(pid) || "P_Z".equals(pid) || "P_H".equals(pid);
    String qid = ir != null ? ir.query_id : null;
    return new QueryOpportunityFeatures(qid, cboPlanId, cboEstimatedMs, topK, sim, metric,
        span, area, hasT, hasS, joint, single, "cheap");
  }

  public Map<String, Object> toMap() {
    Map<String, Object> m = new LinkedHashMap<String, Object>();
    m.put("query_id", queryId);
    m.put("cbo_plan_id", cboPlanId);
    m.put("cbo_estimated_ms", cboEstimatedMs);
    m.put("top_k", Boolean.valueOf(topK));
    m.put("similarity", Boolean.valueOf(similarity));
    m.put("similarity_metric", similarityMetric);
    m.put("temporal_span_ms", Long.valueOf(temporalSpanMs));
    m.put("spatial_area", Double.valueOf(spatialArea));
    m.put("has_temporal", Boolean.valueOf(hasTemporal));
    m.put("has_spatial", Boolean.valueOf(hasSpatial));
    m.put("joint_selected", Boolean.valueOf(jointSelected));
    m.put("single_index_selected", Boolean.valueOf(singleIndexSelected));
    m.put("feature_layer", layer);
    return m;
  }
}
