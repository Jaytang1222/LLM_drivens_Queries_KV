package kart.bench;

import kart.catalog.Manifest;
import kart.catalog.StatsSnapshot;
import kart.compile.LayoutContext;
import kart.config.AppConfig;
import kart.exec.KvBackend;
import kart.ir.BoundIr;
import kart.llm.LlmClient;
import kart.query.QueryEngine;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Shared runtime for a suite run (one HBase connection, frozen planner base). */
public final class BenchContext {
  public final Path root;
  public final Path runDir;
  public final String runId;
  public final String suiteKind;
  public final String suiteId;
  public final String stage;
  public final boolean planOnly;
  public final boolean requireOracle;
  public final boolean keepArtifacts;
  /** cold | warm */
  public final String cache;
  public final Manifest manifest;
  public final LayoutContext layout;
  public final StatsSnapshot stats;
  public final AppConfig.PlannerConfig planner;
  public final KvBackend kv;
  public final LlmClient llm;
  public final Map<String, OracleChecker.Answer> oracleByQueryId;
  public final List<BoundIr> queries;
  public final Map<String, Object> extras;

  public BenchContext(Path root, Path runDir, String runId, String suiteKind, String suiteId,
                      String stage, boolean planOnly, boolean requireOracle,
                      boolean keepArtifacts, String cache,
                      Manifest manifest, LayoutContext layout, StatsSnapshot stats,
                      AppConfig.PlannerConfig planner, KvBackend kv, LlmClient llm,
                      Map<String, OracleChecker.Answer> oracleByQueryId,
                      List<BoundIr> queries, Map<String, Object> extras) {
    this.root = root;
    this.runDir = runDir;
    this.runId = runId;
    this.suiteKind = suiteKind;
    this.suiteId = suiteId;
    this.stage = stage;
    this.planOnly = planOnly;
    this.requireOracle = requireOracle;
    this.keepArtifacts = keepArtifacts;
    this.cache = cache == null ? "cold" : cache;
    this.manifest = manifest;
    this.layout = layout;
    this.stats = stats;
    this.planner = planner;
    this.kv = kv;
    this.llm = llm;
    this.oracleByQueryId = oracleByQueryId != null
        ? oracleByQueryId : Collections.<String, OracleChecker.Answer>emptyMap();
    this.queries = queries;
    this.extras = extras != null ? extras : Collections.<String, Object>emptyMap();
  }

  public Path artifactDir(String armId, String queryId) {
    if (!keepArtifacts) {
      return null;
    }
    return runDir.resolve("artifacts").resolve(armId).resolve(queryId);
  }

  public QueryEngine newEngine(kart.search.PlannerMode mode) {
    kart.exec.ExecLimits limits = kart.exec.ExecLimits.defaults();
    limits.maxCandidateChunks = (int) Math.min(Integer.MAX_VALUE, planner.max_candidate_chunks);
    limits.maxDtwCells = planner.max_dtw_cells;
    limits.fetchBatch = planner.fetch_batch_size;
    LlmClient useLlm = llm;
    if (mode != kart.search.PlannerMode.LLM && mode != kart.search.PlannerMode.LLM_DIRECT) {
      useLlm = null;
    }
    return new QueryEngine(kv, layout, limits, stats, planner, useLlm, mode);
  }
}
