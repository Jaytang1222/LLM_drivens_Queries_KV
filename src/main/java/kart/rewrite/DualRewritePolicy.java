package kart.rewrite;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import kart.llm.LlmMessage;
import kart.catalog.Manifest;
import java.util.*;

/** Offline-admitted rules and residual online LLM patches. D(q) is not cached; online patches reuse a signature. */
public final class DualRewritePolicy {
  public static final String VERSION = "dual_rewrite_v43";
  // Conservative rounded maximum of four v1 protocol calls; v2 latency is not yet measured.
  public static final double DEFAULT_MODEL_MS = 7000;
  private static final ObjectMapper M = new ObjectMapper();
  private DualRewritePolicy() {}

  /** A rule's performance evidence never silently transfers to another snapshot or layout. */
  public static ArrayNode scoped(JsonNode rules, Manifest manifest) {
    ArrayNode out = M.createArrayNode();
    if (rules == null || manifest == null || manifest.status != Manifest.Status.READY) return out;
    JsonNode current;
    try { current = M.readTree(M.writeValueAsString(manifest)); }
    catch (Exception e) { return out; }
    for (JsonNode r : rules) {
      boolean ok = admitted(r) && r.path("scope").isObject();
      for (String key : new String[] {"manifest_id", "semantics_version", "stats_version", "dataset", "layout"}) {
        JsonNode expected = r.path("scope").path(key);
        JsonNode actual = current.path(key);
        ok &= !expected.isMissingNode() && (expected.equals(actual)
            || (expected.isNull() && (actual.isMissingNode() || actual.isNull())));
      }
      if (ok) out.add(r);
    }
    return out;
  }

  public static boolean admitted(JsonNode r) {
    return "confirmed".equals(r.path("status").asText())
        && "train_measured_v1".equals(r.path("admission").asText())
        && r.path("support").asInt() >= 3
        && r.path("source_sha256").asText().matches("[a-f0-9]{64}");
  }

  /** True unless every admitted when-clause is already known to fail on this partial feature set. */
  public static boolean mayHold(JsonNode rules, JsonNode feats, boolean topK) {
    return mayHold(rules, feats, topK, false);
  }

  /** Sparse P_Z (eval tinies): admitted r256 needs z_ranges>=64; skip without extra CostCards. */
  public static boolean pzSparseOfflineSkip(boolean topK, int zRanges) {
    return !topK && zRanges >= 0 && zRanges < 64;
  }

  public static boolean mayHoldCheap(JsonNode rules, JsonNode feats, boolean topK) {
    return mayHold(rules, feats, topK, true);
  }

  static boolean mayHold(JsonNode rules, JsonNode feats, boolean topK, boolean cheapKeysClosed) {
    if (rules == null || feats == null) return false;
    for (JsonNode r : rules) {
      if (!admitted(r)) continue;
      if (whenMayHold(r.path("when"), feats, topK, cheapKeysClosed)) return true;
    }
    return false;
  }

  static boolean whenMayHold(JsonNode when, JsonNode feats, boolean topK) {
    return whenMayHold(when, feats, topK, false);
  }

  static boolean whenMayHold(JsonNode when, JsonNode feats, boolean topK, boolean cheapKeysClosed) {
    if (!when.isArray() || when.size() == 0) return false;
    for (JsonNode c : when) {
      String key = c.path(0).asText();
      JsonNode v = feats.get(key);
      if (v == null || v.isNull() || v.isMissingNode()) {
        if (!topK && ("k".equals(key) || "topk_work_ms".equals(key) || "metric".equals(key)
            || "eligible".equals(key))) return false;
        if (cheapKeysClosed && ("chunks_min".equals(key) || "chunks_keep".equals(key)
            || "z_ranges".equals(key) || "cbo_plan".equals(key))) return false;
        continue;
      }
      if (!RewriteProtocol.clauseHolds(c, feats)) return false;
    }
    return true;
  }

  /** Access/fetch predicates were measured on P_TZ; bind them after CBO already chose P_T/P_Z. */
  static boolean relaxPlan(JsonNode set) {
    return "drop_spatial_branch".equals(set.path(RewriteProtocol.ACCESS).asText());
  }

  static ArrayNode matchWhen(JsonNode when) {
    ArrayNode out = M.createArrayNode();
    if (when == null || !when.isArray()) return out;
    for (JsonNode clause : when) {
      if (!"cbo_plan".equals(clause.path(0).asText())) out.add(clause);
    }
    return out;
  }

  /** Access×fetch pairing may ignore idx_rows caps that were fit on P_TZ support rows. */
  static boolean holdsPairAccess(JsonNode when, JsonNode f) {
    if (when == null || !when.isArray()) return false;
    for (JsonNode clause : when) {
      String k = clause.path(0).asText();
      if ("cbo_plan".equals(k) || k.startsWith("idx_rows_")) continue;
      if (!RewriteProtocol.clauseHolds(clause, f)) return false;
    }
    return true;
  }

  static ObjectNode mergeSet(JsonNode a, JsonNode b) {
    ObjectNode s = M.createObjectNode();
    a.fields().forEachRemaining(e -> s.set(e.getKey(), e.getValue()));
    b.fields().forEachRemaining(e -> s.set(e.getKey(), e.getValue()));
    return s;
  }

  /** Protocol companion of an access rewrite: meta_probe only, never a third slot or P_Z→P_T flip. */
  static RewriteProtocol.Program attachMeta(JsonNode c, RewriteProtocol.Program p) {
    if (p == null || p.choice == null || !RewriteProtocol.slots(c).contains(RewriteProtocol.META)) return p;
    String acc = p.choice.containsKey(RewriteProtocol.ACCESS)
        ? p.choice.get(RewriteProtocol.ACCESS) : RewriteProtocol.defaults(c).choice.get(RewriteProtocol.ACCESS);
    if ("drop_spatial_branch".equals(acc) && "P_Z".equals(c.path("features").path("cbo_plan").asText())) return p;
    if ("on".equals(p.choice.get(RewriteProtocol.META))) return p;
    Map<String, String> def = RewriteProtocol.defaults(c).choice;
    for (String s : new String[] {"range_budget"}) {
      if (p.choice.containsKey(s) && !p.choice.get(s).equals(def.get(s))) return p;
    }
    Map<String, String> m = new TreeMap<String, String>(p.choice);
    m.put(RewriteProtocol.META, "on");
    double e0 = RewriteProtocol.estimate(c, p.choice);
    double e1 = RewriteProtocol.estimate(c, m);
    if (!Double.isFinite(e1) || e1 >= 0 || (Double.isFinite(e0) && e1 >= e0)) return p;
    return new RewriteProtocol.Program(m, p.status + "+meta_probe");
  }

  static RewriteProtocol.Program better(JsonNode c, RewriteProtocol.Program a, RewriteProtocol.Program b) {
    if (a == null || a.choice == null) return b;
    if (b == null || b.choice == null) return a;
    double ea = RewriteProtocol.estimate(c, a.choice);
    double eb = RewriteProtocol.estimate(c, b.choice);
    if (Double.isFinite(eb) && (!Double.isFinite(ea) || eb < ea)) return b;
    return a;
  }

  /** Prefer chunks_min: on NNLS P_T, chunks_keep is often inflated vs the tighter branch. */
  static boolean wideScan(JsonNode c) {
    JsonNode f = c.path("features");
    double min = f.path("chunks_min").asDouble(Double.NaN);
    if (Double.isFinite(min) && min > 0) return min > 10000;
    return f.path("chunks_keep").asDouble() > 10000;
  }

  /** Mid-size scans where NNLS sweep showed fetch-alone wins; excludes tiny top-k and wj. */
  static boolean midFetchScan(JsonNode c) {
    double min = c.path("features").path("chunks_min").asDouble(Double.NaN);
    if (!Double.isFinite(min) || min <= 0) min = c.path("features").path("chunks_keep").asDouble();
    return min > 500 && min <= 10000 && !denseZ(c);
  }

  /** NNLS sweep: mid P_T with z_ranges≥64 has no measured fetch/MP win (td_2/4). */
  static boolean denseZ(JsonNode c) {
    return c.path("features").path("z_ranges").asDouble() >= 64;
  }

  /** Small TOP_K queries where lb2 is the measured discrete win (td_5/6). */
  static boolean smallTopK(JsonNode c) {
    return "TOP_K".equals(c.path("query").asText()) && RewriteProtocol.slots(c).contains("topk");
  }

  /**
   * Request-local one-slot resource fill. Does not change access. On wide P_T and any P_Z,
   * skips meta_probe/fetch (NNLS sweep: those extras lost). Mid-size P_T may take fetch or meta.
   */
  public static RewriteProtocol.Program onlineFill(JsonNode c, RewriteProtocol.Program base) {
    RewriteProtocol.Program start = base != null && base.choice != null ? base : RewriteProtocol.defaults(c);
    Map<String, String> def = RewriteProtocol.defaults(c).choice;
    Map<String, String> cur = new TreeMap<String, String>(start.choice);
    String cbo = c.path("features").path("cbo_plan").asText();
    boolean pt = "P_T".equals(cbo);
    boolean pz = "P_Z".equals(cbo);
    boolean wide = wideScan(c);
    double bestE = RewriteProtocol.estimate(c, cur);
    if (!Double.isFinite(bestE)) bestE = 0;
    Map<String, String> best = null;
    String acc = cur.containsKey(RewriteProtocol.ACCESS) ? cur.get(RewriteProtocol.ACCESS) : def.get(RewriteProtocol.ACCESS);
    boolean preferTopk = smallTopK(c);
    for (String slot : RewriteProtocol.slots(c)) {
      if (RewriteProtocol.ACCESS.equals(slot)) continue;
      if (preferTopk && !"topk".equals(slot)) continue;
      if (pt && (wide || denseZ(c)) && (RewriteProtocol.META.equals(slot) || "fetch_batch".equals(slot))) continue;
      if (pz && (RewriteProtocol.META.equals(slot) || "fetch_batch".equals(slot))) continue;
      // Access flip to P_Z: do not stack fetch (NNLS sweep: P_Z+MP+B2000 loses to P_Z+R256).
      if ("drop_temporal_branch".equals(acc) && "fetch_batch".equals(slot)) continue;
      String have = cur.containsKey(slot) ? cur.get(slot) : def.get(slot);
      if (have != null && def.get(slot) != null && !have.equals(def.get(slot))) continue;
      for (String opt : RewriteProtocol.options(c, slot)) {
        if (opt.equals(def.get(slot))) continue;
        Map<String, String> m = new TreeMap<String, String>(cur);
        m.put(slot, opt);
        double e = RewriteProtocol.estimate(c, m);
        if (!Double.isFinite(e) || e >= 0 || e >= bestE) continue;
        bestE = e;
        best = m;
      }
    }
    if (best == null) return base;
    String st = start.status == null || start.status.isEmpty() ? "online_fill" : start.status + "+online";
    return new RewriteProtocol.Program(best, st);
  }

  /**
   * One admitted rule, or one access rule paired with one resource rule. Not a global slot argmin.
   */
  public static RewriteProtocol.Program exact(JsonNode rules, JsonNode c) {
    RewriteProtocol.Program best = null;
    RewriteProtocol.Program atomic = null;
    double cost = 0;
    double atomicCost = 0;
    if (rules == null) return null;
    List<JsonNode> access = new ArrayList<JsonNode>();
    List<JsonNode> resource = new ArrayList<JsonNode>();
    List<JsonNode> pairFetch = new ArrayList<JsonNode>();
    List<JsonNode> pairAccess = new ArrayList<JsonNode>();
    for (JsonNode r : rules) {
      if (!admitted(r)) continue;
      JsonNode set = r.path("set");
      if (set.size() > 1) {
        JsonNode when = relaxPlan(set) ? matchWhen(r.path("when")) : r.path("when");
        if (RewriteProtocol.holds(when, c.path("features"))) {
          RewriteProtocol.Program p = RewriteProtocol.fromSet(c, set);
          if (p.choice != null && !p.isDefault(c)) {
            double e = RewriteProtocol.estimate(c, p.choice);
            if (Double.isFinite(e) && e < atomicCost) {
              atomicCost = e;
              atomic = new RewriteProtocol.Program(p.choice, "offline_rule:" + r.path("id").asText());
            }
          }
        }
        continue;
      }
      if (set.has("fetch_batch")) {
        if (RewriteProtocol.holds(matchWhen(r.path("when")), c.path("features"))) pairFetch.add(r);
        continue;
      }
      if (set.has(RewriteProtocol.ACCESS) && holdsPairAccess(r.path("when"), c.path("features"))) {
        RewriteProtocol.Program ap = RewriteProtocol.fromSet(c, set);
        if (ap.choice != null && !ap.isDefault(c)) pairAccess.add(r);
      }
      JsonNode when = relaxPlan(set) ? matchWhen(r.path("when")) : r.path("when");
      if (!RewriteProtocol.holds(when, c.path("features"))) continue;
      RewriteProtocol.Program p = RewriteProtocol.fromSet(c, set);
      if (p.choice == null || p.isDefault(c)) continue;
      double e = RewriteProtocol.estimate(c, p.choice);
      // Resource-only topk on native P_TZ would occupy exact() and block related access.
      boolean topkOnlyPtz = "P_TZ".equals(c.path("features").path("cbo_plan").asText())
          && set.size() == 1 && set.has("topk");
      if (!topkOnlyPtz && Double.isFinite(e) && e < cost) {
        cost = e;
        best = new RewriteProtocol.Program(p.choice, "offline_rule:" + r.path("id").asText());
      }
      if (set.has(RewriteProtocol.ACCESS)) access.add(r);
      else resource.add(r);
    }
    for (JsonNode a : access) {
      for (JsonNode b : resource) {
        ObjectNode set = mergeSet(a.path("set"), b.path("set"));
        if (set.size() > 2) continue;
        RewriteProtocol.Program p = RewriteProtocol.fromSet(c, set);
        if (p.choice == null || p.isDefault(c)) continue;
        double e = RewriteProtocol.estimate(c, p.choice);
        if (Double.isFinite(e) && e < cost) {
          cost = e;
          best = new RewriteProtocol.Program(p.choice,
              "offline_rule:" + a.path("id").asText() + "+" + b.path("id").asText());
        }
      }
    }
    RewriteProtocol.Program fetchPair = null;
    double fetchPairE = Double.POSITIVE_INFINITY;
    for (JsonNode a : pairAccess) {
      for (JsonNode b : pairFetch) {
        if (!"drop_spatial_branch".equals(a.path("set").path(RewriteProtocol.ACCESS).asText())) continue;
        if ("P_T".equals(c.path("features").path("cbo_plan").asText())) continue;
        ObjectNode set = mergeSet(a.path("set"), b.path("set"));
        if (set.size() > 2) continue;
        RewriteProtocol.Program p = RewriteProtocol.fromSet(c, set);
        if (p.choice == null || p.isDefault(c)) continue;
        double e = RewriteProtocol.estimate(c, p.choice);
        if (!Double.isFinite(e) || e >= 0 || e >= fetchPairE) continue;
        fetchPairE = e;
        fetchPair = new RewriteProtocol.Program(p.choice,
            "offline_rule:" + a.path("id").asText() + "+" + b.path("id").asText());
      }
    }
    RewriteProtocol.Program strictRes = null;
    double strictResE = Double.POSITIVE_INFINITY;
    for (JsonNode r : rules) {
      if (!admitted(r)) continue;
      JsonNode set = r.path("set");
      if (set.has(RewriteProtocol.ACCESS) || set.has("fetch_batch")) continue;
      if (!RewriteProtocol.holds(r.path("when"), c.path("features"))) continue;
      RewriteProtocol.Program p = RewriteProtocol.fromSet(c, set);
      if (p.choice == null || p.isDefault(c)) continue;
      double e = RewriteProtocol.estimate(c, p.choice);
      if (!Double.isFinite(e)) e = 0;
      if (e >= strictResE) continue;
      strictResE = e;
      strictRes = new RewriteProtocol.Program(p.choice, "offline_rule:" + r.path("id").asText());
    }
    String cboPlan = c.path("features").path("cbo_plan").asText();
    if ("P_T".equals(cboPlan)) {
      if (smallTopK(c)) {
        RewriteProtocol.Program topk = null;
        double topkE = Double.POSITIVE_INFINITY;
        RewriteProtocol.Program meta = null;
        for (JsonNode r : rules) {
          if (!admitted(r)) continue;
          JsonNode set = r.path("set");
          if (set.size() != 1) continue;
          if (!RewriteProtocol.holds(r.path("when"), c.path("features"))) continue;
          RewriteProtocol.Program p = RewriteProtocol.fromSet(c, set);
          if (p.choice == null || p.isDefault(c)) continue;
          if (set.has("topk")) {
            double e = RewriteProtocol.estimate(c, p.choice);
            if (!Double.isFinite(e)) e = 0;
            if (e < topkE) { topkE = e; topk = p; }
          } else if (set.has(RewriteProtocol.META)) meta = p;
        }
        RewriteProtocol.Program base = topk != null ? topk : RewriteProtocol.defaults(c);
        if (meta != null) {
          Map<String, String> m = new TreeMap<String, String>(base.choice);
          m.put(RewriteProtocol.META, "on");
          String st = (base.status == null || base.status.isEmpty() ? "offline_rule" : base.status) + "+meta_probe";
          base = new RewriteProtocol.Program(m, st);
        }
        if (base.choice != null && !base.isDefault(c)) return better(c, base, atomic);
        return atomic;
      }
      if (denseZ(c) && !wideScan(c)) return atomic;
      if (midFetchScan(c) || wideScan(c)) {
        // Fetch rules were measured on P_TZ. After CBO already chose P_T (NNLS),
        // FastCost still quotes a fetch win that sweep/eval do not reproduce.
        Map<String, String> def = RewriteProtocol.defaults(c).choice;
        if (!"keep".equals(def.get(RewriteProtocol.ACCESS))) {
          RewriteProtocol.Program ptFetch = null;
          double ptE = Double.POSITIVE_INFINITY;
          for (JsonNode b : pairFetch) {
            RewriteProtocol.Program p = RewriteProtocol.fromSet(c, b.path("set"));
            if (p.choice == null || p.isDefault(c)) continue;
            double e = RewriteProtocol.estimate(c, p.choice);
            if (!Double.isFinite(e) || e >= 0 || e >= ptE) continue;
            ptE = e;
            ptFetch = new RewriteProtocol.Program(p.choice, "offline_rule:" + b.path("id").asText());
          }
          if (ptFetch != null) return better(c, ptFetch, atomic);
        }
      }
      RewriteProtocol.Program meta = attachMeta(c, RewriteProtocol.defaults(c));
      if (meta != null && meta.choice != null && !meta.isDefault(c)) {
        double e = RewriteProtocol.estimate(c, meta.choice);
        if (Double.isFinite(e) && e < 0) {
          return better(c, new RewriteProtocol.Program(meta.choice, "offline_rule:pt_keep_meta"), atomic);
        }
      }
      if (strictRes != null) {
        double e = RewriteProtocol.estimate(c, strictRes.choice);
        if (Double.isFinite(e) && e < 0) return better(c, strictRes, atomic);
      }
      return atomic;
    }
    if ("P_Z".equals(cboPlan)) {
      // Wide P_Z (wj-scale) still wants access×fetch; compact P_Z keeps admitted range_budget.
      if (fetchPair != null && c.path("features").path("chunks_keep").asDouble() > 10000) {
        return better(c, fetchPair, atomic);
      }
      return better(c, strictRes, atomic);
    }
    RewriteProtocol.Program raw = attachMeta(c, better(c, best, atomic));
    raw = better(c, raw, attachMeta(c, fetchPair));
    return raw;
  }

  /** Related structural rules are evidence, not automatically executable near misses. */
  public static ArrayNode related(JsonNode rules, JsonNode c) {
    ArrayNode out = M.createArrayNode();
    if (rules == null) return out;
    for (JsonNode r : rules) {
      if (!admitted(r)) continue;
      boolean structural = false, ok = true;
      boolean relax = relaxPlan(r.path("set"));
      for (JsonNode clause : r.path("when")) {
        if (relax && "cbo_plan".equals(clause.path(0).asText())) {
          structural = true;
          continue;
        }
        if (!clause.path(2).isNumber()) {
          structural = true;
          ok &= RewriteProtocol.clauseHolds(clause, c.path("features"));
        }
      }
      RewriteProtocol.Program p = RewriteProtocol.fromSet(c, r.path("set"));
      if (structural && ok && p.choice != null && !p.isDefault(c)) out.add(r);
    }
    // Deterministic rule order, independent of input file order.
    List<JsonNode> sorted = new ArrayList<JsonNode>();
    for (JsonNode r : out) sorted.add(r);
    sorted.sort(Comparator.comparing(r -> r.path("id").asText()));
    out.removeAll();
    for (JsonNode r : sorted) out.add(r);
    return out;
  }

  /** Related rules whose set still changes a slot the offline program left at default. */
  public static ArrayNode residual(JsonNode related, JsonNode c, RewriteProtocol.Program base) {
    ArrayNode out = M.createArrayNode();
    if (related == null) return out;
    Map<String, String> def = RewriteProtocol.defaults(c).choice;
    Map<String, String> have = base != null && base.choice != null ? base.choice : def;
    for (JsonNode r : related) {
      JsonNode set = r.path("set");
      boolean open = false;
      for (Iterator<String> it = set.fieldNames(); it.hasNext();) {
        String slot = it.next();
        if (!RewriteProtocol.slots(c).contains(slot)) continue;
        String want = set.path(slot).asText();
        String cur = have.get(slot);
        if (cur == null) cur = def.get(slot);
        if (cur == null || !cur.equals(want)) {
          open = true;
          break;
        }
      }
      if (open) out.add(r);
    }
    return out;
  }

  /** Resource slots still at CBO default. Access is offline-only; 1.5B must not overwrite it. */
  public static ArrayNode resourceResidual(JsonNode related, JsonNode c, RewriteProtocol.Program base) {
    ArrayNode out = M.createArrayNode();
    Map<String, String> def = RewriteProtocol.defaults(c).choice;
    Map<String, String> have = base != null && base.choice != null ? base.choice : def;
    for (JsonNode r : residual(related, c, base)) {
      JsonNode set = r.path("set");
      boolean open = false;
      for (Iterator<String> it = set.fieldNames(); it.hasNext();) {
        String slot = it.next();
        if (RewriteProtocol.ACCESS.equals(slot) || !RewriteProtocol.slots(c).contains(slot)) continue;
        String d = def.get(slot);
        String cur = have.get(slot);
        if (cur == null) cur = d;
        if (d != null && cur != null && !cur.equals(d)) continue;
        String want = set.path(slot).asText();
        if (cur == null || !cur.equals(want)) {
          open = true;
          break;
        }
      }
      if (open) out.add(r);
    }
    return out;
  }

  static String accessPlan(JsonNode c, RewriteProtocol.Program base) {
    String acc = base != null && base.choice != null ? base.choice.get(RewriteProtocol.ACCESS) : null;
    if ("drop_temporal_branch".equals(acc)) return "P_Z";
    if ("drop_spatial_branch".equals(acc)) return "P_T";
    return c.path("features").path("cbo_plan").asText();
  }

  public static ObjectNode postAccessContext(JsonNode c, RewriteProtocol.Program base) {
    ObjectNode c2 = c.deepCopy();
    ((ObjectNode) c2.get("features")).put("cbo_plan", accessPlan(c, base));
    return c2;
  }

  /**
   * At most one admitted resource rule that holds on the post-access plan and is still default.
   * Range budget first; fetch is not an online residual slot.
   */
  public static ArrayNode oneOnlineResource(JsonNode rules, JsonNode c, RewriteProtocol.Program base) {
    ArrayNode out = M.createArrayNode();
    if (rules == null || c == null) return out;
    ObjectNode c2 = postAccessContext(c, base);
    JsonNode feats = c2.path("features");
    Map<String, String> def = RewriteProtocol.defaults(c).choice;
    Map<String, String> have = base != null && base.choice != null ? base.choice : def;
    JsonNode best = null;
    int bestRank = 99;
    for (JsonNode r : rules) {
      if (!admitted(r)) continue;
      JsonNode set = r.path("set");
      if (set.has(RewriteProtocol.ACCESS) || set.size() != 1) continue;
      if (set.has("fetch_batch")) continue;
      if (!RewriteProtocol.holds(r.path("when"), feats)) continue;
      String slot = set.fieldNames().next();
      if (!RewriteProtocol.slots(c).contains(slot)) continue;
      String d = def.get(slot);
      String cur = have.get(slot);
      if (cur == null) cur = d;
      if (d != null && cur != null && !cur.equals(d)) continue;
      String want = set.path(slot).asText();
      if (cur != null && cur.equals(want)) continue;
      int rank = "range_budget".equals(slot) ? 0 : "meta_probe".equals(slot) ? 1
          : "topk".equals(slot) ? 2 : "fetch_batch".equals(slot) ? 3 : 4;
      if (rank < bestRank) {
        bestRank = rank;
        best = r;
      }
    }
    if (best != null) out.add(best);
    return out;
  }

  /** A conservative engineering budget, not a calibrated probability of benefit. */
  public static long budget(double cboMs, double potentialMs, double modelMs,
      double overheadMs, double marginMs, double capMs) {
    if (!Double.isFinite(cboMs) || !Double.isFinite(potentialMs)
        || !Double.isFinite(modelMs) || !Double.isFinite(overheadMs)
        || !Double.isFinite(marginMs) || !Double.isFinite(capMs)
        || cboMs <= 0 || potentialMs <= 0 || modelMs <= 0 || overheadMs < 0
        || marginMs < 0 || capMs < modelMs) return 0;
    double available = 0.5 * Math.min(cboMs, potentialMs) - overheadMs - marginMs;
    return available >= modelMs ? (long) Math.min(capMs, available) : 0;
  }

  /**
   * When a new model sample cannot pay for itself, bind one related LLM rule with exactly one
   * failed numeric predicate and negative FastCost delta. Full matches with e≥0 stay with exact().
   * Not a token-generating call.
   */
  public static RewriteProtocol.Program instantiateRelated(JsonNode related, JsonNode c) {
    RewriteProtocol.Program best = null;
    double bestEst = 0.0d;
    if (related == null) {
      return null;
    }
    JsonNode feats = c.path("features");
    if ("P_T".equals(feats.path("cbo_plan").asText()) && (wideScan(c) || denseZ(c))) return null;
    // P_Z range_budget stays exact()-only: related r256 on the chunks_keep boundary is high-variance.
    boolean pz = "P_Z".equals(feats.path("cbo_plan").asText());
    for (JsonNode r : related) {
      int nfail = RewriteProtocol.fails(r.path("when"), feats);
      if (nfail != 1) {
        continue;
      }
      JsonNode set = r.path("set");
      if (!set.has(RewriteProtocol.ACCESS) || pz) {
        continue;
      }
      RewriteProtocol.Program p = RewriteProtocol.fromSet(c, set);
      if (p.choice == null) {
        continue;
      }
      double e = RewriteProtocol.estimate(c, p.choice);
      if (!Double.isFinite(e) || e >= bestEst) {
        continue;
      }
      bestEst = e;
      best = new RewriteProtocol.Program(p.choice, "online_related:" + r.path("id").asText());
    }
    return attachMeta(c, best);
  }

  /**
   * On native P_TZ, resource-only fills must not lose to deterministic by FastCost.
   * Access rewrites from admitted offline rules are kept as-is.
   */
  public static RewriteProtocol.Program preferDetIfBetter(JsonNode c, RewriteProtocol.Program p) {
    if (p == null || p.choice == null) return p;
    if (!"P_TZ".equals(c.path("features").path("cbo_plan").asText())) return p;
    Map<String, String> def = RewriteProtocol.defaults(c).choice;
    String acc = p.choice.containsKey(RewriteProtocol.ACCESS)
        ? p.choice.get(RewriteProtocol.ACCESS) : def.get(RewriteProtocol.ACCESS);
    if (acc != null && def.get(RewriteProtocol.ACCESS) != null && !acc.equals(def.get(RewriteProtocol.ACCESS))) {
      return p;
    }
    return better(c, p, RewriteProtocol.deterministic(c));
  }

  /** Budget only actions actually exposed to this request, never an unrelated global argmin. */
  public static double potential(JsonNode c, JsonNode related) {
    JsonNode properties = schema(c, related).path("properties");
    Map<String, String> defaults = RewriteProtocol.defaults(c).choice;
    List<Map<String, String>> singles = new ArrayList<Map<String, String>>();
    for (Iterator<String> it = properties.fieldNames(); it.hasNext();) {
      String slot = it.next();
      for (String option : RewriteProtocol.options(c, slot)) {
        if (!option.equals(defaults.get(slot))) {
          Map<String, String> p = new TreeMap<String, String>(); p.put(slot, option); singles.add(p);
        }
      }
    }
    double best = 0;
    for (Map<String, String> a : singles) {
      Map<String, String> p = new TreeMap<String, String>(defaults); p.putAll(a);
      best = Math.min(best, RewriteProtocol.estimate(c, p));
      for (Map<String, String> b : singles) {
        if (a.keySet().equals(b.keySet())) continue;
        p = new TreeMap<String, String>(defaults); p.putAll(a); p.putAll(b);
        best = Math.min(best, RewriteProtocol.estimate(c, p));
      }
    }
    return -best;
  }

  /** Sparse explicit slot patch, at most two changes. Empty object means KEEP. */
  public static ObjectNode schema(JsonNode c, JsonNode related) {
    ObjectNode s = M.createObjectNode();
    s.put("type", "object"); s.put("additionalProperties", false); s.put("maxProperties", 2);
    ObjectNode props = s.putObject("properties");
    Set<String> allowed = new TreeSet<String>();
    for (JsonNode r : related) r.path("set").fieldNames().forEachRemaining(allowed::add);
    if (allowed.contains("access") && RewriteProtocol.slots(c).contains("meta_probe")) allowed.add("meta_probe");
    for (String slot : allowed) {
      if (!RewriteProtocol.slots(c).contains(slot)) continue;
      ArrayNode en = props.putObject(slot).put("type", "string").putArray("enum");
      for (String option : RewriteProtocol.options(c, slot)) en.add(option);
    }
    return s;
  }

  /** Enumerates only residual resource values from admitted related sets. */
  public static ObjectNode onlineSchema(JsonNode c, JsonNode related, RewriteProtocol.Program base) {
    ObjectNode s = M.createObjectNode();
    s.put("type", "object"); s.put("additionalProperties", false); s.put("maxProperties", 2);
    ObjectNode props = s.putObject("properties");
    Map<String, String> def = RewriteProtocol.defaults(c).choice;
    Map<String, String> have = base != null && base.choice != null ? base.choice : def;
    Map<String, TreeSet<String>> allowed = new TreeMap<String, TreeSet<String>>();
    for (JsonNode r : related) {
      JsonNode set = r.path("set");
      for (Iterator<String> it = set.fieldNames(); it.hasNext();) {
        String slot = it.next();
        if (RewriteProtocol.ACCESS.equals(slot) || !RewriteProtocol.slots(c).contains(slot)) continue;
        String d = def.get(slot);
        String cur = have.get(slot);
        if (cur == null) cur = d;
        if (d != null && cur != null && !cur.equals(d)) continue;
        TreeSet<String> opts = allowed.get(slot);
        if (opts == null) {
          opts = new TreeSet<String>();
          allowed.put(slot, opts);
        }
        String want = set.path(slot).asText();
        if (RewriteProtocol.options(c, slot).contains(want)) opts.add(want);
      }
    }
    for (Map.Entry<String, TreeSet<String>> e : allowed.entrySet()) {
      if (e.getValue().isEmpty()) continue;
      ArrayNode en = props.putObject(e.getKey()).put("type", "string").putArray("enum");
      for (String option : e.getValue()) en.add(option);
    }
    return s;
  }

  public static List<LlmMessage> messages(JsonNode c, JsonNode related) {
    return messages(c, related, null);
  }

  public static List<LlmMessage> messages(JsonNode c, JsonNode related, RewriteProtocol.Program base) {
    JsonNode ctx = postAccessContext(c, base);
    ObjectNode input = M.createObjectNode();
    input.put("query", c.path("query").asText());
    // Include only rule predicates and operator semantics, not unrelated estimates or a schema duplicate.
    TreeSet<String> names = new TreeSet<String>(Arrays.asList("cbo_plan", "metric", "k",
        "chunks_drop_spatial_branch", "chunks_drop_temporal_branch"));
    for (JsonNode r : related) for (JsonNode clause : r.path("when")) names.add(clause.path(0).asText());
    ObjectNode features = input.putObject("features");
    for (String name : names) if (ctx.path("features").has(name)) features.set(name, ctx.path("features").get(name));
    ArrayNode rs = input.putArray("past_rules");
    List<JsonNode> ordered = new ArrayList<JsonNode>();
    for (JsonNode r : related) ordered.add(r);
    ordered.sort(Comparator.comparing(r -> r.path("id").asText()));
    for (JsonNode r : ordered) {
      ObjectNode x = rs.addObject();
      List<JsonNode> clauses = new ArrayList<JsonNode>();
      for (JsonNode clause : r.path("when")) clauses.add(clause);
      clauses.sort(Comparator.comparing(JsonNode::toString));
      ArrayNode when = x.putArray("when");
      for (JsonNode clause : clauses) when.add(clause);
      ObjectNode set = x.putObject("set");
      TreeSet<String> slots = new TreeSet<String>(); r.path("set").fieldNames().forEachRemaining(slots::add);
      for (String slot : slots) set.set(slot, r.path("set").get(slot));
    }
    if (base != null && base.choice != null) {
      ObjectNode off = input.putObject("offline");
      for (String slot : new TreeSet<String>(base.choice.keySet())) off.put(slot, base.choice.get(slot));
    }
    return Arrays.asList(LlmMessage.system("Apply the single past_rules set if it holds after the offline access rewrite, "
        + "or return {}. Do not change access. No explanation."),
        LlmMessage.user(input.toString()));
  }

  public static RewriteProtocol.Program parse(String raw, JsonNode c, JsonNode related) {
    return parse(raw, c, related, null);
  }

  public static RewriteProtocol.Program parse(String raw, JsonNode c, JsonNode related,
      RewriteProtocol.Program base) {
    try {
      JsonNode p = M.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
          .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(raw);
      if (p == null || !p.isObject() || p.size() > 2) return new RewriteProtocol.Program(null, "invalid_patch");
      if (p.size() == 0) {
        if (base != null && base.choice != null) {
          return new RewriteProtocol.Program(new TreeMap<String, String>(base.choice), "online_keep");
        }
        RewriteProtocol.Program d = RewriteProtocol.defaults(c);
        return new RewriteProtocol.Program(d.choice, "online_keep");
      }
      JsonNode props = (base != null ? onlineSchema(c, related, base) : schema(c, related)).path("properties");
      for (Iterator<Map.Entry<String, JsonNode>> it = p.fields(); it.hasNext();) {
        Map.Entry<String, JsonNode> e = it.next();
        if (!props.has(e.getKey()) || !e.getValue().isTextual())
          return new RewriteProtocol.Program(null, "invalid_patch");
        ArrayNode en = (ArrayNode) props.path(e.getKey()).path("enum");
        boolean ok = false;
        for (JsonNode n : en) if (e.getValue().asText().equals(n.asText())) { ok = true; break; }
        if (!ok) return new RewriteProtocol.Program(null, "invalid_patch");
      }
      Map<String, String> m = new TreeMap<String, String>(
          base != null && base.choice != null ? base.choice : RewriteProtocol.defaults(c).choice);
      Map<String, String> def = RewriteProtocol.defaults(c).choice;
      for (Iterator<Map.Entry<String, JsonNode>> it = p.fields(); it.hasNext();) {
        Map.Entry<String, JsonNode> e = it.next();
        String slot = e.getKey();
        if (RewriteProtocol.ACCESS.equals(slot)) return new RewriteProtocol.Program(null, "invalid_patch");
        String d = def.get(slot);
        String cur = m.get(slot);
        if (d != null && cur != null && !cur.equals(d)) return new RewriteProtocol.Program(null, "invalid_patch");
        m.put(slot, e.getValue().asText());
      }
      if (Double.isInfinite(RewriteProtocol.estimate(c, m))) {
        return new RewriteProtocol.Program(null, "invalid_combination");
      }
      return new RewriteProtocol.Program(m, "online_patch");
    } catch (Exception e) { return new RewriteProtocol.Program(null, "invalid_json"); }
  }
}
