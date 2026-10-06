package kart.rewrite;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import kart.llm.LlmMessage;
import kart.llm.LlmOptions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * llm_action_program_v3: one option per applicable slot (access, meta_probe, range_budget, fetch_batch, topk).
 * Context evidence is a per-option estimated exec-time delta in ms (negative = faster); meta_probe "on" is
 * keyed by access option because its value depends on the chunk set.
 */
public final class RewriteProtocol {
  public static final String VERSION = "llm_action_program_v3";
  public static final String ACCESS = "access";
  public static final String META = "meta_probe";
  static final String SYSTEM = "You tune one query plan. Every option is result-preserving; the system rechecks "
      + "all predicates. For each slot pick one option. Numbers are estimated execution-time changes in ms versus "
      + "the current plan (negative = faster); estimates can be off by tens of ms and slots interact. Pick the "
      + "combination you expect to run fastest.";
  static final String ADAPT_SYS = "Offline rewrite rules almost match this plan (exactly one predicate failed). "
      + "Change at most two slots. If you change access, the only other allowed change is meta_probe; "
      + "leave fetch_batch and range_budget at defaults. Printed estimates mis-rank; do not copy their argmin. "
      + "The system will re-validate coverage and compare cost with CBO.";
  private static final ObjectMapper MAPPER = new ObjectMapper();

  public static final class Program {
    public final Map<String, String> choice;
    public final String status;

    public Program(Map<String, String> choice, String status) {
      this.choice = choice == null ? null : Collections.unmodifiableMap(new TreeMap<String, String>(choice));
      this.status = status;
    }

    public boolean isDefault(JsonNode ctx) {
      return choice != null && choice.equals(defaults(ctx).choice);
    }

    @Override
    public String toString() {
      return choice == null ? status : choice.toString();
    }
  }

  private RewriteProtocol() {}

  public static ObjectNode newContext(String mode) {
    ObjectNode c = MAPPER.createObjectNode();
    c.put("protocol", VERSION);
    c.put("query", mode);
    c.putObject("features");
    c.putObject("slots");
    return c;
  }

  public static ObjectNode addSlot(ObjectNode ctx, String slot, String def) {
    ObjectNode s = ((ObjectNode) ctx.get("slots")).putObject(slot);
    s.put("default", def);
    s.putObject("options").put(def, 0.0d);
    return (ObjectNode) s.get("options");
  }

  /** Drops slots whose only option is the default. */
  public static void prune(ObjectNode ctx) {
    ObjectNode slots = (ObjectNode) ctx.get("slots");
    List<String> dead = new ArrayList<String>();
    for (Iterator<String> it = slots.fieldNames(); it.hasNext(); ) {
      String k = it.next();
      if (slots.get(k).path("options").size() < 2) {
        dead.add(k);
      }
    }
    slots.remove(dead);
  }

  public static TreeSet<String> slots(JsonNode ctx) {
    TreeSet<String> out = new TreeSet<String>();
    for (Iterator<String> it = ctx.path("slots").fieldNames(); it.hasNext(); ) {
      out.add(it.next());
    }
    return out;
  }

  public static TreeSet<String> options(JsonNode ctx, String slot) {
    TreeSet<String> out = new TreeSet<String>();
    for (Iterator<String> it = ctx.path("slots").path(slot).path("options").fieldNames(); it.hasNext(); ) {
      out.add(it.next());
    }
    return out;
  }

  public static Program defaults(JsonNode ctx) {
    Map<String, String> m = new TreeMap<String, String>();
    for (String s : slots(ctx)) {
      m.put(s, ctx.path("slots").path(s).path("default").asText());
    }
    return new Program(m, "default");
  }

  /** Estimated exec delta of a program; +inf if an option is unknown or meta_probe "on" lacks evidence. */
  public static double estimate(JsonNode ctx, Map<String, String> prog) {
    String access = prog.containsKey(ACCESS) ? prog.get(ACCESS) : "keep";
    double sum = 0.0d;
    for (String s : slots(ctx)) {
      JsonNode slot = ctx.path("slots").path(s);
      JsonNode v = slot.path("options").path(prog.containsKey(s) ? prog.get(s) : slot.path("default").asText());
      if (v.isObject()) {
        v = v.path(access);
      }
      if (!v.isNumber()) {
        return Double.POSITIVE_INFINITY;
      }
      if (!ignored(slot, access)) {
        sum += v.asDouble();
      }
    }
    return sum;
  }

  public static boolean ignored(JsonNode slot, String access) {
    for (JsonNode a : slot.path("ignored_with")) {
      if (a.asText().equals(access)) {
        return true;
      }
    }
    return false;
  }

  /** All option combinations (cartesian product over slots, slot order sorted). */
  public static List<Map<String, String>> combinations(JsonNode ctx) {
    List<Map<String, String>> out = new ArrayList<Map<String, String>>();
    out.add(new TreeMap<String, String>());
    for (String s : slots(ctx)) {
      List<Map<String, String>> next = new ArrayList<Map<String, String>>();
      for (Map<String, String> p : out) {
        for (String o : options(ctx, s)) {
          Map<String, String> q = new TreeMap<String, String>(p);
          q.put(s, o);
          next.add(q);
        }
      }
      out = next;
    }
    return out;
  }

  /** Point-estimate argmin; ties prefer fewer non-default slots, then lexical order. */
  public static Program deterministic(JsonNode ctx) {
    Map<String, String> def = defaults(ctx).choice;
    Map<String, String> best = def;
    double bestEst = estimate(ctx, def);
    for (Map<String, String> p : combinations(ctx)) {
      double e = estimate(ctx, p);
      if (e < bestEst || (e == bestEst && changes(p, def) < changes(best, def))) {
        best = p;
        bestEst = e;
      }
    }
    return new Program(best, "deterministic");
  }

  /** Smallest estimate among programs that differ from {@code p}, or +inf when none. */
  public static double runnerUp(JsonNode ctx, Map<String, String> p) {
    double r = Double.POSITIVE_INFINITY;
    Map<String, String> np = normalize(ctx, p);
    for (Map<String, String> q : combinations(ctx)) {
      if (!normalize(ctx, q).equals(np)) {
        r = Math.min(r, estimate(ctx, q));
      }
    }
    return r;
  }

  /** Resets slots that have no effect under the chosen access to their default. */
  public static Map<String, String> normalize(JsonNode ctx, Map<String, String> p) {
    Map<String, String> out = new TreeMap<String, String>(p);
    String access = p.containsKey(ACCESS) ? p.get(ACCESS) : "keep";
    for (String s : slots(ctx)) {
      if (ignored(ctx.path("slots").path(s), access)) {
        out.put(s, ctx.path("slots").path(s).path("default").asText());
      }
    }
    return out;
  }

  public static int changes(JsonNode ctx, Map<String, String> p) {
    return changes(p, defaults(ctx).choice);
  }

  private static int changes(Map<String, String> p, Map<String, String> def) {
    int n = 0;
    for (Map.Entry<String, String> e : p.entrySet()) {
      if (!e.getValue().equals(def.get(e.getKey()))) {
        n++;
      }
    }
    return n;
  }

  /** Memo key: mode plus, per slot, options ranked by delta and a log2 bucket of the best delta. */
  public static String signature(JsonNode ctx) {
    StringBuilder sb = new StringBuilder(ctx.path("query").asText());
    for (String s : slots(ctx)) {
      TreeMap<Double, TreeSet<String>> rank = new TreeMap<Double, TreeSet<String>>();
      for (String o : options(ctx, s)) {
        JsonNode v = ctx.path("slots").path(s).path("options").path(o);
        double d = v.isNumber() ? v.asDouble() : best(v);
        rank.computeIfAbsent(Double.valueOf(d), k -> new TreeSet<String>()).add(o);
      }
      double top = rank.firstKey().doubleValue();
      long bucket = Math.round(Math.signum(top) * Math.floor(Math.log(1.0d + Math.abs(top) / 25.0d) / Math.log(2)));
      sb.append('|').append(s).append(':');
      for (TreeSet<String> tie : rank.values()) {
        sb.append(String.join("=", tie)).append('>');
      }
      sb.append(bucket);
    }
    return sb.toString();
  }

  private static double best(JsonNode byAccess) {
    double b = Double.POSITIVE_INFINITY;
    for (JsonNode v : byAccess) {
      b = Math.min(b, v.asDouble());
    }
    return b;
  }

  public static List<LlmMessage> messages(JsonNode ctx, boolean reverse) {
    StringBuilder u = new StringBuilder("query: ").append(ctx.path("query").asText());
    for (String s : slots(ctx)) {
      List<String> opts = new ArrayList<String>(options(ctx, s));
      if (reverse) {
        Collections.reverse(opts);
      }
      JsonNode slot = ctx.path("slots").path(s);
      u.append('\n').append(s).append(" (default ").append(slot.path("default").asText()).append("):");
      for (String o : opts) {
        JsonNode v = slot.path("options").path(o);
        u.append(' ').append(o).append(' ');
        if (v.isObject()) {
          List<String> parts = new ArrayList<String>();
          for (Iterator<String> it = v.fieldNames(); it.hasNext(); ) {
            String a = it.next();
            parts.add("with " + a + " " + ms(v.get(a)));
          }
          if (reverse) {
            Collections.reverse(parts);
          }
          u.append('[').append(String.join(", ", parts)).append(']');
        } else {
          u.append(ms(v));
        }
      }
      if (slot.has("ignored_with")) {
        u.append(" (no effect with access ").append(slot.get("ignored_with").get(0).asText()).append(')');
      }
    }
    if (ctx.path("evidence").size() > 0) {
      u.append(ActionMemory.text(ctx));
    }
    List<LlmMessage> msgs = new ArrayList<LlmMessage>();
    msgs.add(LlmMessage.system(SYSTEM));
    msgs.add(LlmMessage.user(u.toString()));
    return msgs;
  }

  public static List<LlmMessage> messagesAdapt(JsonNode ctx, JsonNode near) {
    List<LlmMessage> msgs = messages(ctx, false);
    msgs.set(0, LlmMessage.system(ADAPT_SYS));
    StringBuilder extra = new StringBuilder("\nunmatched_rules:");
    for (JsonNode r : near) {
      extra.append("\n ").append(r.path("id").asText()).append(' ')
          .append(r.path("when")).append(" -> ").append(r.path("set"));
    }
    msgs.set(1, LlmMessage.user(msgs.get(1).content + extra));
    return msgs;
  }

  private static String ms(JsonNode v) {
    long r = Math.round(v.asDouble());
    return (r > 0 ? "+" : "") + r;
  }

  public static ObjectNode schema(JsonNode ctx) {
    ObjectNode s = MAPPER.createObjectNode();
    s.put("type", "object");
    s.put("additionalProperties", false);
    ArrayNode req = s.putArray("required");
    ObjectNode props = s.putObject("properties");
    for (String slot : slots(ctx)) {
      req.add(slot);
      ArrayNode e = props.putObject(slot).put("type", "string").putArray("enum");
      for (String o : options(ctx, slot)) {
        e.add(o);
      }
    }
    return s;
  }

  public static LlmOptions options(long timeoutMs, int maxTokens) {
    LlmOptions o = LlmOptions.oneShot((int) Math.min(Integer.MAX_VALUE, timeoutMs));
    o.maxTokens = maxTokens;
    o.jsonObjectFormat = false;
    o.responseSchemaFormat = true;
    return o;
  }

  public static Program parse(String raw, JsonNode ctx) {
    JsonNode j;
    try {
      j = MAPPER.readTree(raw);
    } catch (Exception e) {
      return new Program(null, "invalid_json");
    }
    TreeSet<String> slots = slots(ctx);
    if (j == null || !j.isObject() || j.size() != slots.size()) {
      return new Program(null, "invalid_shape");
    }
    Map<String, String> m = new TreeMap<String, String>();
    for (String s : slots) {
      JsonNode v = j.get(s);
      if (v == null || !v.isTextual() || !options(ctx, s).contains(v.asText())) {
        return new Program(null, "invalid_shape");
      }
      m.put(s, v.asText());
    }
    if (Double.isInfinite(estimate(ctx, m))) {
      return new Program(null, "inapplicable_combination");
    }
    return new Program(m, "ok");
  }

  /**
   * First confirmed rule whose every condition holds on ctx.features; its "set" overrides defaults.
   * Rules naming unknown features, slots or options never match.
   */
  public static Program matchRule(JsonNode rules, JsonNode ctx) {
    if (rules == null) {
      return null;
    }
    Map<String, String> m = new TreeMap<String, String>(defaults(ctx).choice);
    List<String> ids = new ArrayList<String>();
    for (JsonNode r : rules) {
      String st = r.path("status").asText("");
      if (!(st.isEmpty() || "confirmed".equals(st)) || !holds(r.path("when"), ctx.path("features"))) {
        continue;
      }
      Map<String, String> cand = new TreeMap<String, String>(m);
      boolean ok = r.path("set").size() > 0;
      for (Iterator<Map.Entry<String, JsonNode>> it = r.path("set").fields(); it.hasNext() && ok; ) {
        Map.Entry<String, JsonNode> e = it.next();
        ok = options(ctx, e.getKey()).contains(e.getValue().asText());
        cand.put(e.getKey(), e.getValue().asText());
      }
      if (ok && !Double.isInfinite(estimate(ctx, cand))) {
        m = cand;
        ids.add(r.path("id").asText());
      }
    }
    return ids.isEmpty() ? null : new Program(m, "rule:" + String.join("+", ids));
  }

  /** Confirmed rules whose set is legal here and whose when fails on exactly one predicate. */
  public static ArrayNode nearMiss(JsonNode rules, JsonNode ctx) {
    ArrayNode out = MAPPER.createArrayNode();
    if (rules == null) {
      return out;
    }
    JsonNode feats = ctx.path("features");
    for (JsonNode r : rules) {
      String st = r.path("status").asText("");
      if (!(st.isEmpty() || "confirmed".equals(st)) || r.path("when").size() < 2) {
        continue;
      }
      int nfail = 0;
      boolean close = false;
      for (JsonNode c : r.path("when")) {
        if (!clauseHolds(c, feats)) {
          nfail++;
          close = closeMiss(c, feats);
        }
      }
      if (nfail != 1 || !close) {
        continue;
      }
      boolean ok = r.path("set").size() > 0;
      Map<String, String> cand = new TreeMap<String, String>(defaults(ctx).choice);
      for (Iterator<Map.Entry<String, JsonNode>> it = r.path("set").fields(); it.hasNext() && ok; ) {
        Map.Entry<String, JsonNode> e = it.next();
        ok = options(ctx, e.getKey()).contains(e.getValue().asText());
        cand.put(e.getKey(), e.getValue().asText());
      }
      if (ok && !Double.isInfinite(estimate(ctx, cand))) {
        out.add(r);
      }
    }
    return out;
  }

  static int fails(JsonNode when, JsonNode f) {
    if (!when.isArray() || when.size() == 0) {
      return 99;
    }
    int n = 0;
    for (JsonNode c : when) {
      if (!clauseHolds(c, f)) {
        n++;
      }
    }
    return n;
  }

    public static Program fromSet(JsonNode ctx, JsonNode set) {
      Map<String, String> m = new TreeMap<String, String>(defaults(ctx).choice);
      for (Iterator<Map.Entry<String, JsonNode>> it = set.fields(); it.hasNext(); ) {
        Map.Entry<String, JsonNode> e = it.next();
        if (!options(ctx, e.getKey()).contains(e.getValue().asText())) {
          return new Program(null, "near_inapplicable");
        }
        m.put(e.getKey(), e.getValue().asText());
      }
      return Double.isInfinite(estimate(ctx, m)) ? new Program(null, "near_inapplicable") : new Program(m, "near");
    }

    static boolean holds(JsonNode when, JsonNode f) {
    if (!when.isArray() || when.size() == 0) {
      return false;
    }
    for (JsonNode c : when) {
      if (!clauseHolds(c, f)) {
        return false;
      }
    }
    return true;
  }

  /** Failed numeric predicate but the observed value is within 15% of the threshold. */
  static boolean closeMiss(JsonNode c, JsonNode f) {
    JsonNode v = f.get(c.path(0).asText());
    JsonNode t = c.path(2);
    String op = c.path(1).asText();
    if (v == null || !v.isNumber() || !t.isNumber()) {
      return false;
    }
    double x = v.asDouble();
    double y = t.asDouble();
    if (!(y > 0.0d) || clauseHolds(c, f)) {
      return false;
    }
    if (">=".equals(op) || ">".equals(op)) {
      return x >= 0.85d * y;
    }
    if ("<=".equals(op) || "<".equals(op)) {
      return x <= y / 0.85d;
    }
    return false;
  }

  static boolean clauseHolds(JsonNode c, JsonNode f) {
    JsonNode v = f.get(c.path(0).asText());
    JsonNode t = c.path(2);
    String op = c.path(1).asText();
    if (v == null || v.isNull()) {
      return false;
    }
    if (t.isNumber() && v.isNumber()) {
      double a = v.asDouble();
      double b = t.asDouble();
      return "<".equals(op) ? a < b : "<=".equals(op) ? a <= b : ">".equals(op) ? a > b
          : ">=".equals(op) ? a >= b : "==".equals(op) ? a == b : "!=".equals(op) && a != b;
    }
    return ("==".equals(op) && v.asText().equals(t.asText()))
        || ("!=".equals(op) && !v.asText().equals(t.asText()));
  }
}
