package kart.ir;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Derives clarification questions from DraftIR missing fields (T3.5).
 * Also strips LLM-invented fields that are not grounded in the user utterance / context.
 */
public final class ClarificationDetector {

  private static final Pattern METRIC_TOKEN = Pattern.compile(
      "(?i)\\b(DTW|FRECHET|HAUSDORFF)\\b|离散\\s*弗雷歇|豪斯多夫|弗雷歇");

  private static final Pattern SPATIAL_CUE = Pattern.compile(
      "(?i)\\b(around|near|intersect|inside|within|box|rectangle|region|window|"
          + "spatiotemporal|spatial|lon|lat|beijing|tdrive)\\b|"
          + "附近|区域|矩形|相交|范围内|时空");

  /** Chinese / English aliases that map to registered region_name values. */
  private static final String[][] REGION_ALIASES = {
      {"beijing_core", "beijing_core", "北京核心", "内城"},
      {"zhongguancun", "zhongguancun", "中关村"},
      {"wangjing", "wangjing", "望京"},
      {"guomao", "guomao", "国贸"},
      {"beijing_cbd", "beijing_cbd", "cbd"},
      {"tiananmen", "tiananmen", "天安门"},
      {"capital_airport", "capital_airport", "首都机场", "机场"},
      {"haidian_central", "haidian_central", "海淀"},
      {"chaoyang_central", "chaoyang_central", "朝阳"},
      {"tdrive_smoke_anchor", "tdrive_smoke_anchor", "smoke_anchor", "锚点区"},
      {"tdrive_topk_box", "tdrive_topk_box", "topk_box"},
      {"tdrive_topk_wide", "tdrive_topk_wide", "topk_wide"},
      {"tdrive_topk_s1", "tdrive_topk_s1", "topk_s1"},
  };

  public static final class Question {
    public final String field;
    public final String prompt;

    public Question(String field, String prompt) {
      this.field = field;
      this.prompt = prompt;
    }
  }

  /**
   * Returns questions for still-missing required fields given result mode and known context keys.
   */
  public List<Question> detect(DraftIr draft, Set<String> knownContextKeys) {
    List<Question> out = new ArrayList<Question>();
    if (draft == null) {
      return out;
    }
    Set<String> known = knownContextKeys == null
        ? new LinkedHashSet<String>()
        : new LinkedHashSet<String>(knownContextKeys);

    Set<String> missing = new LinkedHashSet<String>();
    if (draft.missing != null) {
      for (String m : draft.missing) {
        if (m != null && !m.trim().isEmpty()) {
          missing.add(normalize(m));
        }
      }
    }

    String mode = draft.result == null ? null : draft.result.mode;
    boolean needTemporal = needsTemporal(draft, missing);
    boolean needSpatial = needsSpatial(draft, missing);
    boolean needRef = "TOP_K".equals(mode) && needsReference(draft, missing);
    boolean needK = "TOP_K".equals(mode) && needsK(draft, missing);
    boolean needMetric = "TOP_K".equals(mode) && needsMetric(draft, missing);

    if (needTemporal && !knownContains(known, "temporal", "date", "start", "end")) {
      out.add(new Question("temporal", "What date/time range should I use? (start and end, Asia/Shanghai)"));
    }
    if (needSpatial && !knownContains(known, "spatial", "region", "region_name", "geometry")) {
      out.add(new Question("spatial", "Which region or lon/lat rectangle should I use?"));
    }
    if (needRef && !knownContains(known, "reference", "reference_trajectory_id", "similarity")) {
      out.add(new Question("similarity.reference_trajectory_id",
          "Which trajectory id is the similarity reference?"));
    }
    if (needMetric && !knownContains(known, "metric", "similarity.metric")) {
      out.add(new Question("similarity.metric",
          "Which similarity metric? (DTW, FRECHET, or HAUSDORFF)"));
    }
    if (needK && !knownContains(known, "k", "result.k")) {
      out.add(new Question("result.k", "What K (number of nearest trajectories) should I return?"));
    }
    return out;
  }

  /** Merge LLM missing[] with structural gaps. */
  public void enrichMissing(DraftIr draft) {
    if (draft == null) {
      return;
    }
    if (draft.missing == null) {
      draft.missing = new ArrayList<String>();
    }
    Set<String> m = new LinkedHashSet<String>(draft.missing);
    if (draft.temporal != null) {
      boolean startBlank = isBlank(draft.temporal.start);
      boolean endBlank = isBlank(draft.temporal.end);
      if (startBlank != endBlank) {
        m.add("temporal");
      }
    }
    String mode = draft.result == null ? null : draft.result.mode;
    if ("TOP_K".equals(mode)) {
      if (draft.similarity == null || isBlank(draft.similarity.reference_trajectory_id)) {
        m.add("similarity.reference_trajectory_id");
      }
      if (draft.similarity == null || isBlank(draft.similarity.metric)) {
        m.add("similarity.metric");
      }
      if (draft.result.k == null || draft.result.k < 1) {
        m.add("result.k");
      }
    }
    draft.missing = new ArrayList<String>(m);
  }

  /**
   * Strip LLM-invented metric/region and mark missing when not grounded in utterance or context.
   * Call before {@link #enrichMissing(DraftIr)} / {@link #detect}.
   */
  public void applyUtteranceGrounding(DraftIr draft, String utterance,
                                      Map<String, String> context) {
    if (draft == null) {
      return;
    }
    if (draft.missing == null) {
      draft.missing = new ArrayList<String>();
    }
    Set<String> m = new LinkedHashSet<String>(draft.missing);
    String utt = utterance == null ? "" : utterance;
    Set<String> knownKeys = context == null
        ? new LinkedHashSet<String>()
        : new LinkedHashSet<String>(context.keySet());
    String mode = draft.result == null ? null : draft.result.mode;

    if ("TOP_K".equals(mode)) {
      boolean metricGrounded = utteranceMentionsMetric(utt)
          || contextMentionsMetric(context)
          || knownContains(knownKeys, "metric", "similarity.metric");
      if (!metricGrounded) {
        if (draft.similarity != null) {
          draft.similarity.metric = null;
        }
        m.add("similarity.metric");
      }
      boolean hasSpatial = hasSpatialSpec(draft);
      boolean temporalOnly = draft.temporal != null && !hasSpatial;
      if (!hasSpatial && (utteranceHasSpatialCue(utt) || temporalOnly
          || knownContains(knownKeys, "spatial", "region", "region_name", "geometry"))) {
        m.add("spatial");
      }
      // Top-K neighbor queries: vehicle numbers often appear only inside the reference
      // tid (e.g. 8857-8857_14). LLMs wrongly add vehicle_id EQ and empty the candidate set.
      stripUngroundedTopKVehiclePredicates(draft, utt);
    }

    if (draft.spatial != null && !isBlank(draft.spatial.region_name)) {
      String rn = draft.spatial.region_name.trim();
      if (!regionGroundedInUtteranceOrContext(rn, utt, context)) {
        draft.spatial.region_name = null;
        if (draft.spatial.geometry == null) {
          draft.spatial = null;
        }
        m.add("spatial");
      }
    }

    draft.missing = new ArrayList<String>(m);
  }

  /**
   * Drop vehicle_id EQ predicates on TOP_K unless the utterance clearly restricts the
   * candidate set to that vehicle (not merely naming it inside a reference trajectory id).
   */
  static void stripUngroundedTopKVehiclePredicates(DraftIr draft, String utterance) {
    if (draft == null || draft.predicates == null || draft.predicates.isEmpty()) {
      return;
    }
    if (utteranceExplicitlyFiltersCandidatesByVehicle(utterance)) {
      return;
    }
    List<DraftIr.Predicate> kept = new ArrayList<DraftIr.Predicate>();
    for (DraftIr.Predicate p : draft.predicates) {
      if (p != null && "vehicle_id".equals(p.field) && "EQ".equals(p.op)
          && vehicleIdOnlyInsideTrajectoryIds(utterance, p.value)) {
        continue;
      }
      kept.add(p);
    }
    draft.predicates = kept;
  }

  /** True when user asks to limit neighbors to one taxi's trajectories. */
  static boolean utteranceExplicitlyFiltersCandidatesByVehicle(String utterance) {
    if (utterance == null) {
      return false;
    }
    String u = utterance.toLowerCase(Locale.ROOT);
    return u.matches(".*\\b(among|only)\\b.*\\b(taxi|vehicle)\\b.*")
        || u.matches(".*\\bbelonging to\\b.*\\b(taxi|vehicle)\\b.*")
        || u.matches(".*\\b(taxi|vehicle)\\s+\\d+\\s+(trajectories|trips)\\b.*")
        || u.contains("仅限车辆")
        || u.contains("只查车辆");
  }

  /**
   * After removing {@code NNNN-NNNN_M} tokens that contain {@code vehicleId}, the id
   * no longer appears as a free token (or as {@code taxi NNNN}).
   */
  static boolean vehicleIdOnlyInsideTrajectoryIds(String utterance, String vehicleId) {
    if (isBlank(vehicleId)) {
      return true;
    }
    String vid = vehicleId.trim();
    String u = utterance == null ? "" : utterance;
    String stripped = u.replaceAll(
        "\\b" + Pattern.quote(vid) + "-" + Pattern.quote(vid) + "_\\d+\\b", " ");
    stripped = stripped.replaceAll("\\b\\d+-" + Pattern.quote(vid) + "_\\d+\\b", " ");
    stripped = stripped.replaceAll("\\b" + Pattern.quote(vid) + "-\\d+_\\d+\\b", " ");
    String lower = stripped.toLowerCase(Locale.ROOT);
    if (Pattern.compile("\\btaxi\\s+" + Pattern.quote(vid) + "\\b").matcher(lower).find()) {
      return false;
    }
    if (Pattern.compile("\\bvehicle\\s+" + Pattern.quote(vid) + "\\b").matcher(lower).find()) {
      return false;
    }
    return !Pattern.compile("\\b" + Pattern.quote(vid) + "\\b").matcher(stripped).find();
  }

  public static boolean utteranceMentionsMetric(String utterance) {
    return utterance != null && METRIC_TOKEN.matcher(utterance).find();
  }

  public static boolean utteranceHasSpatialCue(String utterance) {
    return utterance != null && SPATIAL_CUE.matcher(utterance).find();
  }

  static boolean regionGroundedInUtteranceOrContext(String regionName, String utterance,
                                                    Map<String, String> context) {
    if (isBlank(regionName)) {
      return false;
    }
    String rn = regionName.trim().toLowerCase(Locale.ROOT);
    String u = utterance == null ? "" : utterance.toLowerCase(Locale.ROOT);
    if (u.contains(rn)) {
      return true;
    }
    if (context != null) {
      for (String val : context.values()) {
        if (val != null && val.toLowerCase(Locale.ROOT).contains(rn)) {
          return true;
        }
      }
    }
    for (String[] row : REGION_ALIASES) {
      if (!rn.equals(row[0])) {
        continue;
      }
      for (int i = 1; i < row.length; i++) {
        String alias = row[i].toLowerCase(Locale.ROOT);
        if (u.contains(alias)) {
          return true;
        }
        if (context != null) {
          for (String val : context.values()) {
            if (val != null && val.toLowerCase(Locale.ROOT).contains(alias)) {
              return true;
            }
          }
        }
      }
    }
    return false;
  }

  private static boolean contextMentionsMetric(Map<String, String> context) {
    if (context == null) {
      return false;
    }
    for (String val : context.values()) {
      if (utteranceMentionsMetric(val)) {
        return true;
      }
    }
    return false;
  }

  private static boolean hasSpatialSpec(DraftIr d) {
    if (d.spatial == null) {
      return false;
    }
    if (!isBlank(d.spatial.region_name)) {
      return true;
    }
    if (d.spatial.geometry == null) {
      return false;
    }
    return d.spatial.geometry.min_lon != null && d.spatial.geometry.min_lat != null
        && d.spatial.geometry.max_lon != null && d.spatial.geometry.max_lat != null;
  }

  private static boolean needsTemporal(DraftIr d, Set<String> missing) {
    if (missingContains(missing, "temporal", "date", "start", "end")) {
      return true;
    }
    if (d.temporal == null) {
      return false;
    }
    boolean startBlank = isBlank(d.temporal.start);
    boolean endBlank = isBlank(d.temporal.end);
    return startBlank != endBlank;
  }

  private static boolean needsSpatial(DraftIr d, Set<String> missing) {
    if (missingContains(missing, "spatial", "region", "region_name", "geometry")) {
      return true;
    }
    return false;
  }

  private static boolean needsReference(DraftIr d, Set<String> missing) {
    if (missingContains(missing, "similarity", "reference", "reference_trajectory_id")) {
      return true;
    }
    return d.similarity == null || isBlank(d.similarity.reference_trajectory_id);
  }

  private static boolean needsK(DraftIr d, Set<String> missing) {
    if (missingContains(missing, "k", "result.k")) {
      return true;
    }
    return d.result == null || d.result.k == null || d.result.k < 1;
  }

  private static boolean needsMetric(DraftIr d, Set<String> missing) {
    if (missingContains(missing, "metric", "similarity.metric")) {
      return true;
    }
    return d.similarity == null || isBlank(d.similarity.metric);
  }

  private static boolean missingContains(Set<String> missing, String... keys) {
    for (String k : keys) {
      for (String m : missing) {
        if (m.equals(k) || m.endsWith("." + k) || m.contains(k)) {
          return true;
        }
      }
    }
    return false;
  }

  private static boolean knownContains(Set<String> known, String... keys) {
    for (String k : keys) {
      for (String x : known) {
        String n = normalize(x);
        if (n.equals(k) || n.contains(k)) {
          return true;
        }
      }
    }
    return false;
  }

  private static String normalize(String s) {
    return s.trim().toLowerCase(Locale.ROOT);
  }

  private static boolean isBlank(String s) {
    return s == null || s.trim().isEmpty();
  }
}
