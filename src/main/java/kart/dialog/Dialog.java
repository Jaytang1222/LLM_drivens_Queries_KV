package kart.dialog;

import kart.exec.QueryResult;
import kart.ir.BoundIr;
import kart.ir.ClarificationDetector;
import kart.ir.DraftIr;
import kart.ir.DraftIrParser;
import kart.ir.IrBinder;
import kart.ir.IrSchemaValidator;
import kart.llm.LlmClient;
import kart.llm.LlmMessage;
import kart.llm.PromptBuilder;
import kart.query.QueryEngine;
import kart.util.StatusLog;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * CLI dialog state machine (T3.7): Parsing / Clarify / Confirm / Planning / Unsupported / Failed.
 */
public final class Dialog {

  public enum State {
    Parsing, Clarify, Confirm, Planning, Unsupported, Failed
  }

  public interface Io {
    void println(String line);

    /** Blocking read; null means EOF. */
    String readLine();
  }

  public static final class Outcome {
    public State state = State.Failed;
    public String status;
    public String error;
    public BoundIr bound;
    public QueryResult queryResult;
    public List<String> clarificationQuestions = new ArrayList<String>();
    public int clarifyRounds;
  }

  private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
  private static final DateTimeFormatter ISO_SH =
      DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(SHANGHAI);

  private final LlmClient llm;
  private final PromptBuilder prompts;
  private final DraftIrParser parser;
  private final ClarificationDetector clarifier = new ClarificationDetector();
  private final IrBinder binder;
  private final QueryEngine engine;
  private final Path runsRoot;
  private final int maxClarifyRounds;

  public Dialog(LlmClient llm, PromptBuilder prompts, IrSchemaValidator validator,
                IrBinder binder, QueryEngine engine, Path runsRoot) {
    this(llm, prompts, validator, binder, engine, runsRoot, 3);
  }

  public Dialog(LlmClient llm, PromptBuilder prompts, IrSchemaValidator validator,
                IrBinder binder, QueryEngine engine, Path runsRoot, int maxClarifyRounds) {
    this.llm = llm;
    this.prompts = prompts;
    this.parser = new DraftIrParser(validator, llm);
    this.binder = binder;
    this.engine = engine;
    this.runsRoot = runsRoot;
    this.maxClarifyRounds = maxClarifyRounds;
    if (engine != null) {
      this.parser.setUsageAccumulator(engine.usageAccumulator());
    }
  }

  /**
   * Bench E1 path: single-round NL → BoundIR (no clarification, no confirm, no execute).
   * Clarification-needed utterances fail with {@code NEED_CLARIFICATION}.
   */
  public Outcome parseToBoundNoClarify(String initialUtterance) {
    Outcome out = new Outcome();
    StatusLog.info("DIALOG", "bench-parse utterance_len="
        + (initialUtterance == null ? 0 : initialUtterance.length()));
    String early = earlyUnsupportedReason(initialUtterance);
    if (early != null) {
      out.state = State.Unsupported;
      out.status = DraftIrParser.STATUS_UNSUPPORTED_QUERY;
      out.error = early;
      return out;
    }
    out.state = State.Parsing;
    List<LlmMessage> msgs = prompts.buildMessages(initialUtterance, "");
    DraftIrParser.ParseResult pr = parser.parseWithRepair(msgs);
    out.clarifyRounds = 0;
    if (DraftIrParser.STATUS_UNSUPPORTED_QUERY.equals(pr.status)) {
      out.state = State.Unsupported;
      out.status = pr.status;
      out.error = pr.error;
      return out;
    }
    if (!DraftIrParser.STATUS_OK.equals(pr.status) || pr.draft == null) {
      out.state = State.Failed;
      out.status = pr.status == null ? DraftIrParser.STATUS_INVALID_IR : pr.status;
      out.error = pr.error;
      return out;
    }
    DraftIr draft = pr.draft;
    clarifier.applyUtteranceGrounding(draft, initialUtterance,
        Collections.<String, String>emptyMap());
    clarifier.enrichMissing(draft);
    List<ClarificationDetector.Question> qs =
        clarifier.detect(draft, Collections.<String>emptySet());
    if (!qs.isEmpty()) {
      out.state = State.Failed;
      out.status = "NEED_CLARIFICATION";
      out.error = "clarification required (bench main table disallows clarify)";
      for (ClarificationDetector.Question q : qs) {
        out.clarificationQuestions.add(q.prompt);
      }
      return out;
    }
    IrBinder.BindResult br = binder.bind(draft, System.currentTimeMillis());
    if (IrBinder.STATUS_UNSUPPORTED_QUERY.equals(br.status)) {
      out.state = State.Unsupported;
      out.status = br.status;
      out.error = br.error;
      return out;
    }
    if (IrBinder.STATUS_NEED_CLARIFICATION.equals(br.status)) {
      out.state = State.Failed;
      out.status = "NEED_CLARIFICATION";
      out.error = br.error;
      return out;
    }
    if (!IrBinder.STATUS_OK.equals(br.status) || br.bound == null) {
      out.state = State.Failed;
      out.status = br.status;
      out.error = br.error;
      return out;
    }
    out.bound = br.bound;
    out.state = State.Confirm;
    out.status = IrBinder.STATUS_OK;
    return out;
  }

  public Outcome run(String initialUtterance, Io io) {
    Outcome out = new Outcome();
    StatusLog.info("DIALOG", "start utterance_len="
        + (initialUtterance == null ? 0 : initialUtterance.length()));
    String early = earlyUnsupportedReason(initialUtterance);
    if (early != null) {
      out.state = State.Unsupported;
      out.status = DraftIrParser.STATUS_UNSUPPORTED_QUERY;
      out.error = early;
      StatusLog.info("DIALOG", "reject early: " + early);
      io.println("UNSUPPORTED_QUERY: " + out.error);
      return out;
    }

    Map<String, String> context = new LinkedHashMap<String, String>();
    String utterance = initialUtterance;
    int clarifies = 0;

    while (true) {
      out.state = State.Parsing;
      StatusLog.info("PARSE", "calling LLM / DraftIR parser (clarify_round=" + clarifies + ")");
      List<LlmMessage> msgs = prompts.buildMessages(utterance, formatContext(context));
      DraftIrParser.ParseResult pr = parser.parseWithRepair(msgs);
      StatusLog.info("PARSE", "status=" + pr.status + " attempts=" + pr.attempts);
      if (DraftIrParser.STATUS_UNSUPPORTED_QUERY.equals(pr.status)) {
        out.state = State.Unsupported;
        out.status = pr.status;
        out.error = pr.error;
        io.println("UNSUPPORTED_QUERY: " + pr.error);
        return out;
      }
      if (!DraftIrParser.STATUS_OK.equals(pr.status) || pr.draft == null) {
        out.state = State.Failed;
        out.status = pr.status == null ? DraftIrParser.STATUS_INVALID_IR : pr.status;
        out.error = pr.error;
        io.println("FAILED: " + out.error);
        return out;
      }

      DraftIr draft = pr.draft;
      clarifier.applyUtteranceGrounding(draft, initialUtterance, context);
      clarifier.enrichMissing(draft);
      Set<String> known = new LinkedHashSet<String>(context.keySet());
      List<ClarificationDetector.Question> qs = clarifier.detect(draft, known);

      if (!qs.isEmpty()) {
        // Ask one field per round so answers are not written into every slot.
        ClarificationDetector.Question q = qs.get(0);
        if (clarifies >= maxClarifyRounds) {
          out.state = State.Failed;
          out.status = "NEED_CLARIFICATION";
          out.error = "clarification limit exceeded";
          for (ClarificationDetector.Question rem : qs) {
            out.clarificationQuestions.add(rem.prompt);
          }
          StatusLog.info("CLARIFY", "limit exceeded remaining=" + qs.size());
          io.println("NEED_CLARIFICATION: remaining questions:");
          for (ClarificationDetector.Question rem : qs) {
            io.println(" - " + rem.prompt);
          }
          return out;
        }
        out.state = State.Clarify;
        clarifies++;
        out.clarifyRounds = clarifies;
        StatusLog.info("CLARIFY", "asking field=" + q.field + " round=" + clarifies
            + " pending=" + qs.size());
        io.println("I need a bit more information:");
        io.println(" - " + q.prompt);
        out.clarificationQuestions.add(q.prompt);
        io.println("Please answer (one line) for " + q.field + ":");
        String answer = io.readLine();
        if (answer == null) {
          out.state = State.Failed;
          out.status = "FAILED";
          out.error = "EOF during clarification";
          return out;
        }
        applyClarificationAnswer(context, Collections.singletonList(q), answer);
        utterance = initialUtterance + "\nAdditional facts: " + formatContext(context);
        continue;
      }

      StatusLog.info("BIND", "binding DraftIR → BoundIR");
      IrBinder.BindResult br = binder.bind(draft, System.currentTimeMillis());
      if (IrBinder.STATUS_UNSUPPORTED_QUERY.equals(br.status)) {
        out.state = State.Unsupported;
        out.status = br.status;
        out.error = br.error;
        io.println("UNSUPPORTED_QUERY: " + br.error);
        return out;
      }
      if (IrBinder.STATUS_NEED_CLARIFICATION.equals(br.status)) {
        if (clarifies >= maxClarifyRounds) {
          out.state = State.Failed;
          out.status = "NEED_CLARIFICATION";
          out.error = br.error;
          io.println("NEED_CLARIFICATION: " + br.error);
          return out;
        }
        out.state = State.Clarify;
        clarifies++;
        out.clarifyRounds = clarifies;
        String field = br.clarifyField != null ? br.clarifyField : "spatial";
        ClarificationDetector.Question q = new ClarificationDetector.Question(field,
            br.error != null ? br.error : "Please clarify " + field);
        StatusLog.info("CLARIFY", "binder requested field=" + field + " round=" + clarifies);
        io.println("I need a bit more information:");
        io.println(" - " + q.prompt);
        out.clarificationQuestions.add(q.prompt);
        io.println("Please answer (one line) for " + q.field + ":");
        String answer = io.readLine();
        if (answer == null) {
          out.state = State.Failed;
          out.status = "FAILED";
          out.error = "EOF during clarification";
          return out;
        }
        context.remove("spatial");
        context.remove("region");
        context.remove("region_name");
        applyClarificationAnswer(context, Collections.singletonList(q), answer);
        utterance = initialUtterance + "\nAdditional facts: " + formatContext(context);
        continue;
      }
      if (!IrBinder.STATUS_OK.equals(br.status) || br.bound == null) {
        out.state = State.Failed;
        out.status = br.status;
        out.error = br.error;
        StatusLog.info("BIND", "failed status=" + br.status + " err=" + br.error);
        io.println("BIND_ERROR: " + br.error);
        return out;
      }
      StatusLog.info("BIND", "ok query_id=" + br.bound.query_id);

      out.bound = br.bound;
      out.state = State.Confirm;
      StatusLog.info("CONFIRM", "awaiting user y/n");
      io.println(semanticSummary(br.bound));
      String yn = null;
      for (int confirmTry = 0; confirmTry < 3; confirmTry++) {
        io.println("Confirm and plan? [y/n]");
        yn = io.readLine();
        if (yn == null) {
          break;
        }
        String t = yn.trim().toLowerCase(Locale.ROOT);
        if (t.startsWith("y") || t.startsWith("n")) {
          break;
        }
        io.println("Please answer y or n (got: " + yn.trim() + ")");
        StatusLog.info("CONFIRM", "ignored non y/n answer try=" + confirmTry);
      }
      if (yn == null) {
        out.state = State.Failed;
        out.status = "FAILED";
        out.error = "EOF during confirm";
        return out;
      }
      if (!yn.trim().toLowerCase(Locale.ROOT).startsWith("y")) {
        io.println("Not confirmed. Enter corrections (or empty line to cancel):");
        String refine = io.readLine();
        if (refine == null || refine.trim().isEmpty()) {
          out.state = State.Failed;
          out.status = "NOT_CONFIRMED";
          out.error = "user declined confirm";
          StatusLog.info("CONFIRM", "declined without correction");
          return out;
        }
        context.put("user_correction", refine.trim());
        applyClarificationAnswer(context,
            Collections.singletonList(
                new ClarificationDetector.Question("user_correction", refine.trim())),
            refine.trim());
        utterance = initialUtterance + "\nAdditional facts: " + formatContext(context);
        StatusLog.info("CONFIRM", "declined → re-parse with correction");
        continue;
      }

      out.state = State.Planning;
      StatusLog.info("PLAN", "user confirmed → QueryEngine");
      try {
        QueryEngine.RunResult rr = engine.run(br.bound, runsRoot);
        out.queryResult = rr.result;
        out.status = rr.result == null ? "FAILED" : rr.result.status;
        if (rr.result != null && !"OK".equals(rr.result.status)) {
          out.error = rr.result.error;
          out.state = State.Failed;
          StatusLog.info("PLAN", "query failed status=" + rr.result.status);
          io.println("Query status=" + rr.result.status + " error=" + rr.result.error);
          return out;
        }
        StatusLog.info("DONE", "status=" + out.status
            + (rr.runDir != null ? (" runDir=" + rr.runDir) : ""));
        io.println("status=" + out.status);
        if (rr.result != null) {
          io.println("trajectory_ids=" + rr.result.trajectoryIds);
        }
        return out;
      } catch (Exception e) {
        out.state = State.Failed;
        out.status = "FAILED";
        out.error = e.getMessage();
        StatusLog.info("PLAN", "exception " + e.getMessage());
        io.println("FAILED: " + out.error);
        return out;
      }
    }
  }

  /** Early utterance rejects before calling the LLM (shared by chat + bench parse arms). */
  public static String earlyUnsupportedReason(String utterance) {
    if (utterance == null) {
      return null;
    }
    if (looksLikeCountRequest(utterance)) {
      return "COUNT / aggregation queries are not supported";
    }
    if (looksLikeContinuousPathRequest(utterance)) {
      return "continuous path / LINEAR_SEGMENT semantics are not supported "
          + "(OBSERVED_POINT GPS samples only)";
    }
    if (looksLikeContinuousFrechetRequest(utterance)) {
      return "continuous Fréchet is not supported; use discrete FRECHET, DTW, or HAUSDORFF";
    }
    if (looksLikePairProximityRequest(utterance)) {
      return "pair / co-location / proximity-join queries are not supported";
    }
    if (looksLikeDerivedAttributeRequest(utterance)) {
      return "derived attributes (speed / dwell / stay-point) are not supported; "
          + "do not silently rewrite as vehicle+time filter";
    }
    String badMetric = looksLikeUnsupportedMetric(utterance);
    if (badMetric != null) {
      return "unsupported metric=" + badMetric + "; supported: DTW, FRECHET, HAUSDORFF";
    }
    return null;
  }

  /**
   * Speed / dwell / stay-point predicates are not in DraftIR; LLM must not strip them
   * into a plain vehicle+temporal IDS query.
   */
  static boolean looksLikeDerivedAttributeRequest(String utterance) {
    if (utterance == null) {
      return false;
    }
    String u = utterance.toLowerCase(Locale.ROOT);
    return u.contains("average speed")
        || u.contains("avg speed")
        || u.contains("mean speed")
        || u.matches(".*\\bspeed\\b.*")
        || u.contains("km/h")
        || u.contains("kmh")
        || u.contains("kmph")
        || u.contains("m/s")
        || u.contains("dwell")
        || u.contains("stay-point")
        || u.contains("stay point")
        || u.contains("staypoint")
        || u.contains("stopping time")
        || u.contains("平均速度")
        || u.contains("时速")
        || u.contains("停留点")
        || u.contains("驻留");
  }

  static boolean looksLikePairProximityRequest(String utterance) {
    if (utterance == null) {
      return false;
    }
    String u = utterance.toLowerCase(Locale.ROOT);
    return u.contains("pairs of")
        || u.contains("pair of taxis")
        || (u.contains("within") && (u.contains("of each other") || u.contains("each other")))
        || u.contains("co-location")
        || u.contains("colocation")
        || u.contains("near each other")
        || u.contains("相遇")
        || u.contains("两两")
        || u.contains("互相距离");
  }

  static boolean looksLikeCountRequest(String utterance) {
    if (utterance == null) {
      return false;
    }
    String u = utterance.toLowerCase(Locale.ROOT);
    return u.matches(".*\\bcount\\b.*")
        || u.contains("how many")
        || u.contains("多少条")
        || u.contains("多少条轨迹")
        || u.contains("有多少")
        || u.contains("计数")
        || u.contains("统计数量");
  }

  static boolean looksLikeContinuousPathRequest(String utterance) {
    if (utterance == null) {
      return false;
    }
    String u = utterance.toLowerCase(Locale.ROOT);
    return u.contains("continuous path")
        || u.contains("continuous-path")
        || u.contains("linear_segment")
        || u.contains("linear segment")
        || u.contains("polyline")
        || (u.contains("ring road") && (u.contains("cross") || u.contains("segment")))
        || u.contains("连续路径")
        || u.contains("插值");
  }

  static boolean looksLikeContinuousFrechetRequest(String utterance) {
    if (utterance == null) {
      return false;
    }
    String u = utterance.toLowerCase(Locale.ROOT);
    if (u.contains("continuous frechet") || u.contains("continuous fréchet")
        || u.contains("连续弗雷歇") || u.contains("连续 frechet")) {
      return true;
    }
    return u.contains("continuous") && (u.contains("frechet") || u.contains("fréchet"));
  }

  /** Returns the unsupported metric token if the utterance names one, else null. */
  static String looksLikeUnsupportedMetric(String utterance) {
    if (utterance == null) {
      return null;
    }
    String u = utterance.toUpperCase(Locale.ROOT);
    String[] bad = {
        "EDIT_DISTANCE", "LCSS", "ERP", "EDR", "LEVENSHTEIN", "HAMMING"
    };
    for (String m : bad) {
      if (u.contains(m)) {
        return m;
      }
    }
    return null;
  }

  /**
   * Bind the answer only to the asked field(s). Does not write one answer into unrelated slots.
   */
  static void applyClarificationAnswer(Map<String, String> context,
                                       List<ClarificationDetector.Question> qs,
                                       String answer) {
    if (qs == null || qs.isEmpty() || answer == null) {
      return;
    }
    String a = answer.trim();
    for (ClarificationDetector.Question q : qs) {
      String field = q.field == null ? "" : q.field;
      if (field.contains("metric")) {
        if (a.matches("(?i).*\\b(DTW|FRECHET|HAUSDORFF)\\b.*")) {
          context.put("similarity.metric",
              a.replaceAll("(?i).*\\b(DTW|FRECHET|HAUSDORFF)\\b.*", "$1")
                  .toUpperCase(Locale.ROOT));
        } else {
          context.put(field, a);
        }
      } else if (field.contains("reference")) {
        if (a.matches("(?i).*(reference|ref)\\s*=\\s*\\S+.*")) {
          context.put("similarity.reference_trajectory_id",
              a.replaceAll("(?i).*(?:reference|ref)\\s*=\\s*(\\S+).*", "$1"));
        } else {
          context.put("similarity.reference_trajectory_id", a);
        }
      } else if (field.contains("k") && !field.contains("metric")) {
        if (a.matches("(?i).*\\bk\\s*=\\s*\\d+.*")) {
          context.put("result.k", a.replaceAll("(?i).*\\bk\\s*=\\s*(\\d+).*", "$1"));
        } else if (a.matches("\\d+")) {
          context.put("result.k", a);
        } else {
          context.put(field, a);
        }
      } else if (field.contains("temporal") || field.equals("date")) {
        context.put("temporal", a);
        context.put("date", a);
      } else if (field.contains("spatial") || field.contains("region")) {
        context.put("spatial", a);
        context.put("region_name", a);
      } else {
        context.put(field, a);
      }
    }
  }

  private static String formatContext(Map<String, String> context) {
    if (context.isEmpty()) {
      return "";
    }
    StringBuilder sb = new StringBuilder();
    for (Map.Entry<String, String> e : context.entrySet()) {
      sb.append(e.getKey()).append("=").append(e.getValue()).append('\n');
    }
    return sb.toString();
  }

  static String semanticSummary(BoundIr b) {
    StringBuilder sb = new StringBuilder();
    sb.append("Semantic summary (sampled GPS points / OBSERVED_POINT, not continuous path):\n");
    sb.append("  dataset=").append(b.source == null ? "?" : b.source.dataset_id).append('\n');
    sb.append("  result=").append(b.result == null ? "?" : b.result.mode);
    if (b.result != null && b.result.k != null) {
      sb.append(" k=").append(b.result.k);
    }
    sb.append('\n');
    if (b.predicates != null && !b.predicates.isEmpty()) {
      for (BoundIr.Predicate p : b.predicates) {
        if (p == null) {
          continue;
        }
        sb.append("  predicate=").append(p.field).append(' ').append(p.op)
            .append(' ').append(p.value).append('\n');
      }
    }
    if (b.temporal != null) {
      sb.append("  temporal=[").append(ISO_SH.format(Instant.ofEpochMilli(b.temporal.start_ms)))
          .append(", ").append(ISO_SH.format(Instant.ofEpochMilli(b.temporal.end_ms)))
          .append(")\n");
      sb.append("  temporal_ms=[").append(b.temporal.start_ms).append(',')
          .append(b.temporal.end_ms).append(")\n");
    }
    if (b.spatial != null) {
      if (b.spatial.region_name != null && !b.spatial.region_name.isEmpty()) {
        sb.append("  region_name=").append(b.spatial.region_name).append('\n');
      }
      sb.append("  spatial_m=[").append(b.spatial.min_x).append(',').append(b.spatial.min_y)
          .append("]-[").append(b.spatial.max_x).append(',').append(b.spatial.max_y).append("]\n");
    }
    if (b.similarity != null) {
      sb.append("  similarity=").append(b.similarity.metric)
          .append(" ref_tid=").append(b.similarity.reference_tid).append('\n');
    }
    return sb.toString();
  }
}
