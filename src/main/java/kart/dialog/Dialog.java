package kart.dialog;

import kart.config.AppConfig;
import kart.exec.KvBackend;
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
import java.util.ArrayList;
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
  }

  public Outcome run(String initialUtterance, Io io) {
    Outcome out = new Outcome();
    StatusLog.info("DIALOG", "start utterance_len="
        + (initialUtterance == null ? 0 : initialUtterance.length()));
    if (looksLikeCountRequest(initialUtterance)) {
      out.state = State.Unsupported;
      out.status = DraftIrParser.STATUS_UNSUPPORTED_QUERY;
      out.error = "COUNT / aggregation queries are not supported";
      StatusLog.info("DIALOG", "reject COUNT/aggregation early");
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
      clarifier.enrichMissing(draft);
      Set<String> known = new LinkedHashSet<String>(context.keySet());
      List<ClarificationDetector.Question> qs = clarifier.detect(draft, known);

      if (!qs.isEmpty()) {
        if (clarifies >= maxClarifyRounds) {
          out.state = State.Failed;
          out.status = "NEED_CLARIFICATION";
          out.error = "clarification limit exceeded";
          for (ClarificationDetector.Question q : qs) {
            out.clarificationQuestions.add(q.prompt);
          }
          StatusLog.info("CLARIFY", "limit exceeded remaining=" + qs.size());
          io.println("NEED_CLARIFICATION: remaining questions:");
          for (ClarificationDetector.Question q : qs) {
            io.println(" - " + q.prompt);
          }
          return out;
        }
        out.state = State.Clarify;
        clarifies++;
        out.clarifyRounds = clarifies;
        StatusLog.info("CLARIFY", "asking " + qs.size() + " question(s) round=" + clarifies);
        io.println("I need a bit more information:");
        for (ClarificationDetector.Question q : qs) {
          io.println(" - " + q.prompt);
          out.clarificationQuestions.add(q.prompt);
        }
        io.println("Please answer (one line):");
        String answer = io.readLine();
        if (answer == null) {
          out.state = State.Failed;
          out.status = "FAILED";
          out.error = "EOF during clarification";
          return out;
        }
        applyClarificationAnswer(context, qs, answer);
        utterance = initialUtterance + "\nAdditional facts: " + formatContext(context);
        continue;
      }

      StatusLog.info("BIND", "binding DraftIR → BoundIR");
      IrBinder.BindResult br = binder.bind(draft);
      if (IrBinder.STATUS_UNSUPPORTED_QUERY.equals(br.status)) {
        out.state = State.Unsupported;
        out.status = br.status;
        out.error = br.error;
        io.println("UNSUPPORTED_QUERY: " + br.error);
        return out;
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
      if (yn == null || !yn.trim().toLowerCase(Locale.ROOT).startsWith("y")) {
        io.println("Not confirmed; you may refine the request.");
        out.state = State.Failed;
        out.status = "NOT_CONFIRMED";
        out.error = "user declined confirm";
        StatusLog.info("CONFIRM", "declined");
        return out;
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

  private static void applyClarificationAnswer(Map<String, String> context,
                                               List<ClarificationDetector.Question> qs,
                                               String answer) {
    // Store under each asked field; also parse simple "start=... end=..." pairs.
    for (ClarificationDetector.Question q : qs) {
      context.put(q.field, answer.trim());
    }
    String a = answer.trim();
    if (a.toLowerCase(Locale.ROOT).contains("2008") || a.contains("T")) {
      context.put("temporal", a);
      context.put("date", a);
    }
    if (a.matches("(?i).*\\bk\\s*=\\s*\\d+.*")) {
      context.put("result.k", a.replaceAll("(?i).*\\bk\\s*=\\s*(\\d+).*", "$1"));
    }
    if (a.matches("(?i).*(reference|ref)\\s*=\\s*\\S+.*")) {
      context.put("similarity.reference_trajectory_id",
          a.replaceAll("(?i).*(?:reference|ref)\\s*=\\s*(\\S+).*", "$1"));
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
    if (b.temporal != null) {
      sb.append("  temporal=[").append(b.temporal.start_ms).append(',').append(b.temporal.end_ms)
          .append(")\n");
    }
    if (b.spatial != null) {
      sb.append("  spatial=[").append(b.spatial.min_x).append(',').append(b.spatial.min_y)
          .append("]-[").append(b.spatial.max_x).append(',').append(b.spatial.max_y).append("]\n");
    }
    if (b.similarity != null) {
      sb.append("  similarity=DTW ref_tid=").append(b.similarity.reference_tid).append('\n');
    }
    return sb.toString();
  }
}
