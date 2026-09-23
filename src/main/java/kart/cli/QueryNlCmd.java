package kart.cli;

import kart.catalog.CatalogStore;
import kart.catalog.Manifest;
import kart.compile.LayoutContext;
import kart.config.AppConfig;
import kart.dialog.Dialog;
import kart.exec.HBaseBackend;
import kart.exec.KvBackend;
import kart.exec.MemoryBackend;
import kart.ir.IrBinder;
import kart.ir.IrSchemaValidator;
import kart.llm.LlmClient;
import kart.llm.MockLlmClient;
import kart.llm.OpenAiCompatibleClient;
import kart.llm.PromptBuilder;
import kart.query.QueryEngine;
import kart.snapshot.FixtureBuilder;
import kart.snapshot.SnapshotBuilder;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Callable;

@Command(name = "query-nl", description = "Natural-language query with multi-turn clarification (P3)")
public final class QueryNlCmd implements Callable<Integer> {

  @Parameters(index = "0", arity = "0..1", description = "Natural language utterance")
  private String utterance;

  @Option(names = "--mock", description = "Use MockLlmClient from testdata/llm-mock")
  private boolean mock;

  @Option(names = "--memory",
      description = "Dev-only MemoryBackend. Experiment path: omit (uses HBase fixture_v1_ready).")
  private boolean memory;

  @Option(names = "--catalog", defaultValue = "catalog", description = "Catalog directory")
  private Path catalogDir;

  @Option(names = "--manifest", defaultValue = "fixture_v1_ready",
      description = "Manifest when using HBase")
  private String manifestId;

  @Option(names = "--config-root", description = "Project root")
  private Path configRoot;

  @Option(names = "--runs", defaultValue = "runs", description = "Run artifact directory")
  private Path runsDir;

  @Option(names = "--answers", description = "Scripted dialog answers file (one line per prompt)")
  private Path answersFile;

  @Option(names = "--dataset", defaultValue = "fixture_v1", description = "Default dataset id")
  private String datasetId;

  @Override
  public Integer call() throws Exception {
    Path root = resolveRoot();
    if (utterance == null || utterance.trim().isEmpty()) {
      System.err.println("usage: query-nl \"<utterance>\" [--mock] [--memory] [--answers file]");
      System.err.println("Experiment path: HBase (default). Dev: --memory. Need LLM or --mock.");
      return 2;
    }

    AppConfig config = AppConfig.load(root);
    IrSchemaValidator validator = new IrSchemaValidator(root.resolve("schemas"));

    LlmClient llm;
    if (mock) {
      Path mockDir = root.resolve("testdata/llm-mock");
      llm = new MockLlmClient(mockDir);
      ((MockLlmClient) llm).bindUtterances(new PromptBuilder(config.regions(), datasetId));
    } else {
      try {
        llm = new OpenAiCompatibleClient();
      } catch (Exception e) {
        System.err.println("LLM client init failed: " + e.getMessage());
        System.err.println("Hint: set LLM_API_KEY / LLM_BASE_URL or use --mock; else query-ir.");
        return 2;
      }
    }

    PromptBuilder prompts = new PromptBuilder(config.regions(), datasetId);

    KvBackend kv = null;
    boolean closeKv = false;
    HBaseBackend hbase = null;
    try {
      LayoutContext layout;
      String mid;
      kart.catalog.StatsSnapshot stats;
      if (memory) {
        MemoryBackend mem = SnapshotBuilder.buildFixtureInMemory();
        kv = mem;
        closeKv = true;
        layout = LayoutContext.from(FixtureBuilder.fixtureManifest());
        mid = FixtureBuilder.MANIFEST_ID;
        stats = fixtureStats();
      } else {
        Path cat = catalogDir.isAbsolute() ? catalogDir : root.resolve(catalogDir);
        CatalogStore catalog = new CatalogStore(cat);
        mid = manifestId == null ? FixtureBuilder.MANIFEST_ID : manifestId;
        Manifest manifest = catalog.loadManifest(mid).orElse(null);
        if (manifest == null) {
          System.err.println("manifest not found: " + mid + " — run: kart.sh load-fixture");
          return 2;
        }
        layout = LayoutContext.from(manifest);
        stats = catalog.loadStats(mid).orElse(null);
        Path site = root.resolve("config/hbase/hbase-site.xml");
        hbase = new HBaseBackend(HBaseBackend.open(site));
        kv = hbase;
        closeKv = true;
      }

      IrBinder binder = new IrBinder(
          config.regions(), kv, layout.tableMeta, layout.shardCount,
          mid, "point_dtw_v1");
      kart.exec.ExecLimits limits = kart.exec.ExecLimits.defaults();
      limits.maxCandidateChunks = (int) Math.min(Integer.MAX_VALUE,
          config.planner().max_candidate_chunks);
      limits.maxDtwCells = config.planner().max_dtw_cells;
      limits.fetchBatch = config.planner().fetch_batch_size;
      QueryEngine engine = new QueryEngine(kv, layout, limits, stats, config.planner().cost);
      Path runsRoot = runsDir.isAbsolute() ? runsDir : root.resolve(runsDir);

      Dialog dialog = new Dialog(llm, prompts, validator, binder, engine, runsRoot);
      Dialog.Io io = buildIo(root);
      Dialog.Outcome outcome = dialog.run(utterance, io);

      if (outcome.state == Dialog.State.Planning
          && outcome.queryResult != null
          && "OK".equals(outcome.queryResult.status)) {
        return 0;
      }
      if (outcome.state == Dialog.State.Unsupported) {
        return 3;
      }
      return 1;
    } finally {
      if (closeKv && kv != null) {
        if (hbase != null) {
          hbase.closeConnection();
        } else {
          kv.close();
        }
      }
    }
  }

  private static kart.catalog.StatsSnapshot fixtureStats() {
    try {
      return new kart.snapshot.StatsBuilder(FixtureBuilder.layoutParams())
          .build(FixtureBuilder.MANIFEST_ID, FixtureBuilder.trajectories());
    } catch (Exception e) {
      return null;
    }
  }

  private Dialog.Io buildIo(Path root) throws Exception {
    final Iterator<String> scripted;
    if (answersFile != null) {
      Path p = answersFile.isAbsolute() ? answersFile : root.resolve(answersFile);
      List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);
      List<String> filtered = new ArrayList<String>();
      for (String line : lines) {
        if (!line.trim().isEmpty() && !line.trim().startsWith("#")) {
          filtered.add(line);
        }
      }
      scripted = filtered.iterator();
    } else {
      scripted = null;
    }
    final BufferedReader stdin = new BufferedReader(
        new InputStreamReader(System.in, StandardCharsets.UTF_8));
    return new Dialog.Io() {
      @Override
      public void println(String line) {
        System.out.println(line);
      }

      @Override
      public String readLine() {
        try {
          if (scripted != null) {
            if (!scripted.hasNext()) {
              return null;
            }
            String a = scripted.next();
            System.out.println("> " + a);
            return a;
          }
          return stdin.readLine();
        } catch (Exception e) {
          return null;
        }
      }
    };
  }

  private Path resolveRoot() {
    if (configRoot != null) {
      return configRoot;
    }
    String prop = System.getProperty("kart.root");
    if (prop != null && !prop.isEmpty()) {
      return Paths.get(prop);
    }
    return Paths.get(".").toAbsolutePath().normalize();
  }
}
