package kart.cli;

import kart.catalog.CatalogStore;
import kart.catalog.StatsSnapshot;
import kart.compile.LayoutContext;
import kart.compile.PhysicalPlan;
import kart.compile.QueryCompiler;
import kart.compile.ScanTask;
import kart.config.AppConfig;
import kart.cost.CostCard;
import kart.cost.CostFeatures;
import kart.cost.CostFeaturesExtractor;
import kart.cost.CostModel;
import kart.cost.FastCost;
import kart.cost.PlanSelector;
import kart.ir.BoundIr;
import kart.llm.LlmClient;
import kart.llm.OpenAiCompatibleClient;
import kart.plan.PlanEnvelope;
import kart.search.BeamSearch;
import kart.search.BestFirstPolicy;
import kart.search.LlmDirectPlanPlanner;
import kart.search.LlmProposalPolicy;
import kart.search.PlannerMode;
import kart.search.ProposalPolicy;
import kart.search.RulePolicy;
import kart.search.SearchBudget;
import kart.search.SearchResult;
import kart.validation.SafePlanHandle;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Explain planning without executing data reads: BoundIR, candidates,
 * validation, CostCards, selected plan, physical hex summary.
 */
@Command(name = "explain", description = "Explain planning (no data execution)")
public final class ExplainCmd implements Callable<Integer> {

  @Option(names = "--ir", description = "BoundIR JSON path", required = true)
  Path irPath;

  @Option(names = "--root", description = "Config root (directory with config/)",
      defaultValue = ".")
  Path root;

  @Option(names = "--catalog", defaultValue = "catalog")
  Path catalogDir;

  @Option(names = "--manifest", description = "Manifest id (default: IR snapshot)")
  String manifestId;

  @Option(names = "--no-llm", description = "Force RulePolicy (skip LlmProposalPolicy)")
  boolean noLlm;

  @Option(names = "--policy",
      description = "Plan policy: rule|best_first|llm|llm_direct (overrides --no-llm when set)")
  String policy;

  @Override
  public Integer call() throws Exception {
    BoundIr ir = BoundIr.fromJson(new String(Files.readAllBytes(irPath), StandardCharsets.UTF_8));
    AppConfig cfg = AppConfig.load(root);

    String mid = manifestId;
    if (mid == null && ir.snapshot != null) {
      mid = ir.snapshot.manifest_id;
    }
    if (mid == null) {
      System.err.println("manifest id required (--manifest or IR.snapshot.manifest_id)");
      return 2;
    }
    Path cat = catalogDir.isAbsolute() ? catalogDir : root.resolve(catalogDir);
    CatalogStore store = new CatalogStore(cat);
    if (!store.loadManifest(mid).isPresent()) {
      System.err.println("manifest not found: " + mid + " in " + cat);
      return 1;
    }
    LayoutContext layout = LayoutContext.from(store.loadManifest(mid).get());
    StatsSnapshot stats = store.loadStats(mid).orElse(null);

    kart.cost.RegionMapping regionMapping = openRegionMapping(cfg, root);
    SearchBudget budget = SearchBudget.from(cfg.planner());
    FastCost fastCost = new FastCost(layout, stats, cfg.planner().cost, regionMapping);
    PlannerMode mode = resolveMode();
    SearchResult result = runExplainSearch(layout, fastCost, ir, budget, mode);

    long soft = cfg.planner().cost != null && cfg.planner().cost.soft_memory_bytes > 0
        ? cfg.planner().cost.soft_memory_bytes : (512L * 1024L * 1024L);
    CostModel model = new CostModel(cfg.planner().cost, regionMapping);
    CostFeaturesExtractor extractor = new CostFeaturesExtractor(layout, stats, regionMapping, soft);
    QueryCompiler compiler = new QueryCompiler(layout);
    List<PlanSelector.Scored> scored = new ArrayList<PlanSelector.Scored>();
    List<CostCard> cards = new ArrayList<CostCard>();
    for (SafePlanHandle h : result.safePlans) {
      CostFeatures f = extractor.extractFinal(h.physicalPlan(), h.plan(), ir);
      CostCard card = model.estimateFinal(f);
      cards.add(card);
      scored.add(new PlanSelector.Scored(h, card));
    }
    PlanSelector.Scored best = PlanSelector.selectScored(scored);

    Path runsOut = root.resolve("runs").resolve("explain");
    Files.createDirectories(runsOut);
    String qid = ir.query_id != null ? ir.query_id : ("explain_" + System.currentTimeMillis());
    Path art = runsOut.resolve(qid);
    Files.createDirectories(art);
    Files.write(art.resolve("bound_ir.json"), irToSafeJson(ir).getBytes(StandardCharsets.UTF_8));
    if (!result.rejections.isEmpty()) {
      List<Object> reps = new ArrayList<Object>();
      for (SearchResult.Rejection r : result.rejections) {
        if (r.report != null) {
          reps.add(r.report);
        }
      }
      Files.write(art.resolve("validation_reports.json"),
          new com.fasterxml.jackson.databind.ObjectMapper()
              .enable(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT)
              .writeValueAsBytes(reps));
    }
    if (best != null) {
      Files.write(art.resolve("plan.json"), best.handle.plan().toJson().getBytes(StandardCharsets.UTF_8));
      Files.write(art.resolve("cost_card.json"), best.card.toJson().getBytes(StandardCharsets.UTF_8));
      CostFeatures bf = extractor.extractFinal(best.handle.physicalPlan(), best.handle.plan(), ir);
      Files.write(art.resolve("cost_features.json"),
          new com.fasterxml.jackson.databind.ObjectMapper()
              .enable(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT)
              .writeValueAsBytes(bf.toFeatureMap()));
      Files.write(art.resolve("search_log.json"),
          result.log.toPrettyString().getBytes(StandardCharsets.UTF_8));
    }

    System.out.println("=== explain ===");
    System.out.println("artifacts=" + art.toAbsolutePath());
    System.out.println("policy=" + mode.wireName());
    System.out.println("calibrated=" + model.calibrated() + " model=" + CostModel.MODEL_VERSION);
    System.out.println("validation_report_hash="
        + (result.rejections.isEmpty() ? "none"
            : Integer.toHexString(result.rejections.hashCode())));
    System.out.println();
    System.out.println("--- BoundIR ---");
    System.out.println(irToSafeJson(ir));
    System.out.println();
    System.out.println("--- candidates ---");
    for (PlanEnvelope e : result.candidates) {
      System.out.println("  " + e.plan_id + " sigHash=" + Integer.toHexString(e.signature().hashCode()));
    }
    System.out.println("stop_reason=" + (budget.stopReason() != null ? budget.stopReason() : "OK"));
    System.out.println();
    System.out.println("--- validation ---");
    System.out.println("safe=" + result.safePlans.size() + " rejected=" + result.rejections.size());
    for (String id : result.safePlanIds()) {
      System.out.println("  SAFE " + id);
    }
    for (SearchResult.Rejection r : result.rejections) {
      System.out.println("  REJECT " + r.planId + ":");
      System.out.print(indent(r.report.toString(), "    "));
    }
    System.out.println();
    System.out.println("--- CostCards (FINAL) ---");
    for (CostCard c : cards) {
      System.out.println("  plan=" + c.plan_id
          + " estimated_ms=" + c.estimated_ms
          + " ranges=" + c.features.get("scan_ranges")
          + " drivers=" + c.main_cost_drivers
          + " uncertainty=" + c.uncertainty.label
          + " missing_stats=" + c.features.get("missing_stats")
          + " missing_region_map=" + c.features.get("missing_region_map"));
    }
    System.out.println();
    System.out.println("--- selected ---");
    if (best == null) {
      System.out.println("  (none)");
    } else {
      System.out.println("  plan_id=" + best.handle.plan().plan_id);
      System.out.println("  estimated_ms=" + best.card.estimated_ms);
      System.out.println("  CostCard:");
      System.out.print(indent(best.card.toJson(), "  "));
    }
    System.out.println();
    System.out.println("--- PhysicalPlan (hex intervals + readable coverage) ---");
    if (best != null) {
      PhysicalPlan phys = best.handle.physicalPlan();
      if (phys == null) {
        phys = compiler.compile(best.handle.plan(), ir);
      }
      System.out.println("  queryHash=" + phys.queryHash
          + " manifestId=" + phys.manifestId
          + " layoutHash=" + phys.layoutHash
          + " scanTasks=" + phys.scanTasks.size());
      for (ScanTask t : phys.scanTasks) {
        System.out.println("  " + t.table
            + " shard=" + t.shard
            + " " + t.coverageRef
            + " startHex=" + t.startHex
            + " stopHex=" + t.stopHex);
      }
    } else {
      System.out.println("  (no selected plan)");
    }
    System.out.println();
    System.out.println("--- search log ---");
    System.out.print(result.log.toPrettyString());
    return 0;
  }

  private PlannerMode resolveMode() {
    if (policy != null && !policy.trim().isEmpty()) {
      return PlannerMode.parse(policy);
    }
    if (noLlm) {
      return PlannerMode.RULE;
    }
    return PlannerMode.LLM;
  }

  private SearchResult runExplainSearch(LayoutContext layout, FastCost fastCost, BoundIr ir,
                                        SearchBudget budget, PlannerMode mode) {
    if (mode == PlannerMode.LLM_DIRECT) {
      LlmClient llm = tryOpenLlm();
      if (llm == null) {
        System.err.println("LLM unavailable; using RulePolicy");
        return new BeamSearch(layout, fastCost).search(ir, new RulePolicy(), budget);
      }
      return new LlmDirectPlanPlanner(layout, fastCost, llm).plan(ir, budget);
    }
    ProposalPolicy prop = buildProposalPolicy(mode, fastCost);
    return new BeamSearch(layout, fastCost).search(ir, prop, budget);
  }

  private ProposalPolicy buildProposalPolicy(PlannerMode mode, FastCost fastCost) {
    switch (mode) {
      case BEST_FIRST:
        return new BestFirstPolicy(fastCost);
      case LLM:
        LlmClient llm = tryOpenLlm();
        if (llm == null) {
          System.err.println("LLM unavailable; using RulePolicy");
          return new RulePolicy();
        }
        return new LlmProposalPolicy(llm);
      case RULE:
      default:
        return new RulePolicy();
    }
  }

  private static LlmClient tryOpenLlm() {
    try {
      return new OpenAiCompatibleClient();
    } catch (Exception e) {
      return null;
    }
  }

  /** Prefer live HBase RegionLocator; fall back to shard affinity with missing_region_map. */
  private static kart.cost.RegionMapping openRegionMapping(AppConfig cfg, Path root) {
    try {
      Path site = root.resolve("config/hbase/hbase-site.xml");
      if (java.nio.file.Files.isRegularFile(site)) {
        org.apache.hadoop.hbase.client.Connection conn = kart.exec.HBaseBackend.open(site);
        return new kart.cost.HBaseRegionMapping(conn);
      }
    } catch (Exception e) {
      System.err.println("explain: RegionLocator unavailable (" + e.getMessage()
          + "); using shard fallback");
    }
    return new kart.cost.RegionMapping.ShardFallback();
  }

  private static String irToSafeJson(BoundIr ir) throws Exception {
    return new com.fasterxml.jackson.databind.ObjectMapper()
        .enable(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT)
        .writeValueAsString(ir);
  }

  private static String indent(String s, String prefix) {
    StringBuilder sb = new StringBuilder();
    for (String line : s.split("\n", -1)) {
      sb.append(prefix).append(line).append('\n');
    }
    return sb.toString();
  }
}
