package kart.ir;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Derives clarification questions from DraftIR missing fields (T3.5).
 */
public final class ClarificationDetector {

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
    // Do not auto-add temporal — spatial-only queries are valid; LLM lists missing.
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
      if (draft.result.k == null || draft.result.k < 1) {
        m.add("result.k");
      }
    }
    // spatial is optional unless explicitly listed missing
    draft.missing = new ArrayList<String>(m);
  }

  private static boolean needsTemporal(DraftIr d, Set<String> missing) {
    // Ask only when LLM marked temporal missing, or temporal is partially filled.
    if (missingContains(missing, "temporal", "date", "start", "end")) {
      return true;
    }
    if (d.temporal == null) {
      return false;
    }
    boolean startBlank = isBlank(d.temporal.start);
    boolean endBlank = isBlank(d.temporal.end);
    return startBlank != endBlank; // partial → clarify
  }

  private static boolean needsSpatial(DraftIr d, Set<String> missing) {
    if (missingContains(missing, "spatial", "region", "region_name", "geometry")) {
      return true;
    }
    // Only ask spatial if LLM marked it missing — spatial is optional for some queries
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
