package kart.query;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import kart.catalog.StatsSnapshot;
import kart.compile.LayoutContext;
import kart.compile.PhysicalPlan;
import kart.compile.QueryCompiler;
import kart.config.AppConfig;
import kart.cost.CostCard;
import kart.cost.CostFeatures;
import kart.cost.CostFeaturesExtractor;
import kart.cost.CostModel;
import kart.cost.PlanSelector;
import kart.exec.Coordinator;
import kart.exec.ExecLimits;
import kart.exec.KvBackend;
import kart.exec.QueryResult;
import kart.ir.BoundIr;
import kart.plan.PlanBuilder;
import kart.plan.PlanEnvelope;
import kart.validation.PlanValidator;
import kart.validation.SafePlanHandle;
import kart.validation.ValidationReport;
import kart.util.StatusLog;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * End-to-end BoundIR → candidates → validate → cost-select → execute (P5).
 */
public final class QueryEngine {

  private static final ObjectMapper MAPPER = new ObjectMapper()
      .enable(SerializationFeature.INDENT_OUTPUT);

  public static final class RunResult {
    public BoundIr ir;
    public List<PlanEnvelope> candidates = new ArrayList<PlanEnvelope>();
    public List<SafePlanHandle> safe = new ArrayList<SafePlanHandle>();
    public List<CostCard> costCards = new ArrayList<CostCard>();
    public List<ValidationReport> rejectionReports = new ArrayList<ValidationReport>();
    public SafePlanHandle selected;
    public CostCard selectedCost;
    public QueryResult result;
    public Path runDir;
  }

  private final KvBackend kv;
  private final LayoutContext layout;
  private final ExecLimits limits;
  private final StatsSnapshot stats;
  private final CostModel costModel;

  public QueryEngine(KvBackend kv, LayoutContext layout) {
    this(kv, layout, ExecLimits.defaults(), null, null);
  }

  public QueryEngine(KvBackend kv, LayoutContext layout, ExecLimits limits) {
    this(kv, layout, limits, null, null);
  }

  public QueryEngine(KvBackend kv, LayoutContext layout, ExecLimits limits,
                     StatsSnapshot stats, AppConfig.CostCoeffs coeffs) {
    this.kv = kv;
    this.layout = layout;
    this.limits = limits == null ? ExecLimits.defaults() : limits;
    this.stats = stats;
    this.costModel = new CostModel(coeffs != null ? coeffs : new AppConfig.CostCoeffs());
  }

  public RunResult run(BoundIr ir, Path runsRoot) throws IOException {
    RunResult rr = new RunResult();
    rr.ir = ir;
    StatusLog.info("ENGINE", "build candidates for query_id="
        + (ir == null ? "?" : ir.query_id));
    List<PlanEnvelope> candidates = PlanBuilder.buildCandidates(ir);
    rr.candidates = candidates;
    StatusLog.info("ENGINE", "candidates=" + candidates.size());

    QueryCompiler compiler = new QueryCompiler(layout);
    PlanValidator validator = new PlanValidator(layout);
    CostFeaturesExtractor extractor = new CostFeaturesExtractor(layout, stats);
    List<PlanSelector.Scored> scored = new ArrayList<PlanSelector.Scored>();

    for (PlanEnvelope env : candidates) {
      String pid = env == null || env.plan_id == null ? "?" : env.plan_id;
      PhysicalPlan phys = compiler.compile(env, ir);
      ValidationReport report = new ValidationReport();
      Optional<SafePlanHandle> handle = validator.validate(env, ir, phys, report);
      if (handle.isPresent()) {
        SafePlanHandle h = handle.get();
        rr.safe.add(h);
        CostFeatures features = extractor.extractFinal(phys, env, ir);
        CostCard card = costModel.estimateFinal(features);
        rr.costCards.add(card);
        scored.add(new PlanSelector.Scored(h, card));
        StatusLog.info("VALIDATE", "SAFE plan_id=" + pid
            + " estimated_ms=" + card.estimated_ms);
      } else {
        rr.rejectionReports.add(report);
        StatusLog.info("VALIDATE", "REJECT plan_id=" + pid
            + " findings=" + report.findings().size());
      }
    }

    PlanSelector.Scored best = PlanSelector.selectScored(scored);
    rr.selected = best == null ? null : best.handle;
    rr.selectedCost = best == null ? null : best.card;

    if (rr.selected == null) {
      QueryResult fail = new QueryResult();
      fail.status = "FAILED";
      fail.error = "no safe plan among " + candidates.size() + " candidates";
      rr.result = fail;
      StatusLog.info("SELECT", "no safe plan");
      if (runsRoot != null) {
        rr.runDir = writeArtifacts(runsRoot, ir, null, null, null, rr.costCards, fail);
      }
      return rr;
    }

    String selId = rr.selected.plan() == null || rr.selected.plan().plan_id == null
        ? "?" : rr.selected.plan().plan_id;
    StatusLog.info("SELECT", "chosen plan_id=" + selId
        + " estimated_ms="
        + (rr.selectedCost == null ? "?" : String.valueOf(rr.selectedCost.estimated_ms))
        + " calibrated=" + costModel.calibrated());
    StatusLog.info("EXEC", "Coordinator executing…");
    Coordinator coord = new Coordinator(kv, ir, layout, limits);
    rr.result = coord.execute(rr.selected);
    StatusLog.info("EXEC", "done status="
        + (rr.result == null ? "?" : rr.result.status)
        + " ids="
        + (rr.result == null || rr.result.trajectoryIds == null
            ? 0 : rr.result.trajectoryIds.size()));

    if (runsRoot != null) {
      rr.runDir = writeArtifacts(runsRoot, ir, rr.selected.plan(), rr.selected.physicalPlan(),
          rr.selectedCost, rr.costCards, rr.result);
      StatusLog.info("ARTIFACTS", "runDir=" + rr.runDir);
    }
    return rr;
  }

  private static Path writeArtifacts(Path runsRoot, BoundIr ir, PlanEnvelope plan,
                                     PhysicalPlan phys, CostCard selectedCost,
                                     List<CostCard> allCosts, QueryResult result)
      throws IOException {
    String runId = ir.query_id != null ? ir.query_id : ("run_" + System.currentTimeMillis());
    Path dir = runsRoot.resolve(runId);
    Files.createDirectories(dir);
    write(dir.resolve("bound_ir.json"), MAPPER.writeValueAsString(ir));
    if (plan != null) {
      write(dir.resolve("plan.json"), plan.toJson());
    }
    if (phys != null) {
      write(dir.resolve("physical.json"), MAPPER.writeValueAsString(phys));
    }
    if (selectedCost != null) {
      write(dir.resolve("cost_card.json"), selectedCost.toJson());
    }
    if (allCosts != null && !allCosts.isEmpty()) {
      write(dir.resolve("cost_cards.json"), MAPPER.writeValueAsString(allCosts));
    }
    if (result != null) {
      if (result.trace != null) {
        if (result.trace.run_id == null) {
          result.trace.run_id = runId;
        }
        write(dir.resolve("trace.json"), result.trace.toJson());
      }
      write(dir.resolve("result.json"), result.toJson());
    }
    return dir;
  }

  private static void write(Path p, String json) throws IOException {
    Files.write(p, json.getBytes(StandardCharsets.UTF_8));
  }
}
