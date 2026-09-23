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
import kart.plan.PlanEnvelope;
import kart.search.BeamSearch;
import kart.search.RulePolicy;
import kart.search.SearchBudget;
import kart.search.SearchResult;
import kart.snapshot.FixtureBuilder;
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
 * Explain planning without executing data reads (T5.5): BoundIR, candidates,
 * validation, CostCards, selected plan, physical hex summary.
 * Physical bytes appear only in the PhysicalPlan section.
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

  @Option(names = "--manifest", description = "Manifest id (default: IR snapshot or fixture)")
  String manifestId;

  @Override
  public Integer call() throws Exception {
    BoundIr ir = BoundIr.fromJson(new String(Files.readAllBytes(irPath), StandardCharsets.UTF_8));
    AppConfig cfg = AppConfig.load(root);

    LayoutContext layout;
    StatsSnapshot stats = null;
    String mid = manifestId;
    if (mid == null && ir.snapshot != null) {
      mid = ir.snapshot.manifest_id;
    }
    Path cat = catalogDir.isAbsolute() ? catalogDir : root.resolve(catalogDir);
    if (mid != null && Files.isDirectory(cat)) {
      CatalogStore store = new CatalogStore(cat);
      if (store.loadManifest(mid).isPresent()) {
        layout = LayoutContext.from(store.loadManifest(mid).get());
        stats = store.loadStats(mid).orElse(null);
      } else {
        layout = LayoutContext.from(FixtureBuilder.fixtureManifest());
      }
    } else {
      layout = LayoutContext.from(FixtureBuilder.fixtureManifest());
    }

    SearchBudget budget = SearchBudget.from(cfg.planner());
    FastCost fastCost = new FastCost(layout, stats, cfg.planner().cost);
    BeamSearch search = new BeamSearch(layout, fastCost);
    SearchResult result = search.search(ir, new RulePolicy(), budget);

    CostModel model = new CostModel(cfg.planner().cost);
    CostFeaturesExtractor extractor = new CostFeaturesExtractor(layout, stats);
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

    System.out.println("=== explain ===");
    System.out.println("calibrated=" + model.calibrated() + " model=" + CostModel.MODEL_VERSION);
    System.out.println();
    System.out.println("--- BoundIR ---");
    // BoundIR must not contain physical bytes
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
          + " missing_stats=" + c.features.get("missing_stats"));
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
