package kart.cli;

import kart.catalog.CatalogStore;
import kart.catalog.Manifest;
import kart.catalog.StatsSnapshot;
import kart.compile.LayoutContext;
import kart.config.AppConfig;
import kart.dialog.Dialog;
import kart.exec.ExecLimits;
import kart.exec.HBaseBackend;
import kart.exec.KvBackend;
import kart.exec.MemoryBackend;
import kart.ir.IrBinder;
import kart.ir.IrSchemaValidator;
import kart.llm.LlmClient;
import kart.llm.OpenAiCompatibleClient;
import kart.llm.PromptBuilder;
import kart.query.QueryEngine;
import kart.snapshot.FixtureBuilder;
import kart.snapshot.SnapshotBuilder;
import kart.util.StatusLog;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.Callable;

/**
 * Interactive NL REPL. Experiment path: Live LLM → HBase {@code tdrive_v1_ready}.
 * Dev-only: {@code --memory} uses in-process fixture (not for handbook/smoke authenticity).
 */
@Command(name = "chat", description = "Interactive natural-language query shell (REPL)")
public final class ChatCmd implements Callable<Integer> {

  @Option(names = "--memory",
      description = "Dev-only: in-process MemoryBackend (skips HBase). Experiment path: omit this.")
  private boolean memory;

  @Option(names = "--catalog", defaultValue = "catalog", description = "Catalog directory")
  private Path catalogDir;

  @Option(names = "--manifest", defaultValue = "tdrive_v1_ready",
      description = "Manifest id when using HBase (experiment default: tdrive_v1_ready)")
  private String manifestId;

  @Option(names = "--config-root", description = "Project root")
  private Path configRoot;

  @Option(names = "--runs", defaultValue = "runs", description = "Run artifact directory")
  private Path runsDir;

  @Option(names = "--dataset", defaultValue = "tdrive_v1", description = "Default dataset id for NL prompts")
  private String datasetId;

  @Override
  public Integer call() throws Exception {
    Path root = resolveRoot();
    AppConfig config = AppConfig.load(root);
    IrSchemaValidator validator = new IrSchemaValidator(root.resolve("schemas"));
    PromptBuilder prompts = new PromptBuilder(config.regions(), datasetId);

    LlmClient llm = buildLiveLlm();
    if (llm == null) {
      return 2;
    }

    KvBackend kv = null;
    boolean closeKv = false;
    HBaseBackend hbase = null;
    try {
      LayoutContext layout;
      StatsSnapshot stats;
      String mid;
      if (memory) {
        MemoryBackend mem = SnapshotBuilder.buildFixtureInMemory();
        kv = mem;
        closeKv = true;
        layout = LayoutContext.from(FixtureBuilder.fixtureManifest());
        mid = FixtureBuilder.MANIFEST_ID;
        stats = fixtureStats();
        System.out.println("backend=memory-fixture (dev; not experiment HBase path)");
      } else {
        Path cat = catalogDir.isAbsolute() ? catalogDir : root.resolve(catalogDir);
        CatalogStore catalog = new CatalogStore(cat);
        mid = manifestId == null || manifestId.trim().isEmpty() ? "tdrive_v1_ready" : manifestId;
        Manifest manifest = catalog.loadManifest(mid).orElse(null);
        if (manifest == null) {
          System.err.println("manifest not found: " + mid + " in " + cat);
          System.err.println("Hint: ensure catalog/" + mid + ".manifest.json exists after");
          System.err.println("      ./scripts/kart.sh up && ./scripts/kart.sh run build-snapshot --data datasets/tdrive");
          return 2;
        }
        layout = LayoutContext.from(manifest);
        stats = catalog.loadStats(mid).orElse(null);
        Path site = root.resolve("config/hbase/hbase-site.xml");
        hbase = new HBaseBackend(HBaseBackend.open(site));
        kv = hbase;
        closeKv = true;
        System.out.println("backend=hbase manifest=" + mid);
      }

      IrBinder binder = new IrBinder(
          config.regions(), kv, layout.tableMeta, layout.shardCount,
          mid, "point_similarity_v2");
      ExecLimits limits = ExecLimits.defaults();
      limits.maxCandidateChunks = (int) Math.min(Integer.MAX_VALUE,
          config.planner().max_candidate_chunks);
      limits.maxDtwCells = config.planner().max_dtw_cells;
      limits.fetchBatch = config.planner().fetch_batch_size;
      ExecLimits fromCost = ExecLimits.fromCostCoeffs(config.planner().cost);
      limits.softMemoryBytes = fromCost.softMemoryBytes;
      limits.maxExecMs = fromCost.maxExecMs;
      limits.indexParallelism = fromCost.indexParallelism;
      limits.scanParallelism = fromCost.scanParallelism;
      limits.scanParallelismPerRs = fromCost.scanParallelismPerRs;
      limits.fetchParallelism = fromCost.fetchParallelism;
      QueryEngine engine = new QueryEngine(kv, layout, limits, stats, config.planner(), llm);
      Path runsRoot = runsDir.isAbsolute() ? runsDir : root.resolve(runsDir);

      BufferedReader stdin = new BufferedReader(
          new InputStreamReader(System.in, StandardCharsets.UTF_8));
      Dialog.Io io = new Dialog.Io() {
        @Override
        public void println(String line) {
          System.out.println(line);
        }

        @Override
        public String readLine() {
          try {
            System.out.print("> ");
            System.out.flush();
            return stdin.readLine();
          } catch (Exception e) {
            return null;
          }
        }
      };

      printBanner(memory);
      StatusLog.info("CHAT", "ready live llm memory=" + memory
          + " status=" + StatusLog.enabled());

      while (true) {
        System.out.print("kart> ");
        System.out.flush();
        String line = stdin.readLine();
        if (line == null) {
          break;
        }
        line = line.trim();
        if (line.isEmpty()) {
          continue;
        }
        if (line.equalsIgnoreCase("/quit") || line.equalsIgnoreCase("/exit")
            || line.equalsIgnoreCase("quit") || line.equalsIgnoreCase("exit")) {
          System.out.println("bye");
          break;
        }
        if (line.equalsIgnoreCase("/help")) {
          printHelp();
          continue;
        }
        if (line.toLowerCase().startsWith("/status")) {
          String[] parts = line.split("\\s+");
          if (parts.length >= 2 && "off".equalsIgnoreCase(parts[1])) {
            System.setProperty("kart.status", "false");
          } else {
            System.setProperty("kart.status", "true");
          }
          System.out.println("kart.status=" + StatusLog.enabled());
          continue;
        }

        Dialog dialog = new Dialog(llm, prompts, validator, binder, engine, runsRoot);
        try {
          dialog.run(line, io);
        } catch (Exception e) {
          StatusLog.info("CHAT", "error " + e.getMessage());
          System.out.println("ERROR: " + e.getMessage());
        }
        System.out.println();
      }
      return 0;
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

  private static StatsSnapshot fixtureStats() {
    try {
      kart.snapshot.IndexBuilders.LayoutParams lp = FixtureBuilder.layoutParams();
      return new kart.snapshot.StatsBuilder(lp)
          .build(FixtureBuilder.MANIFEST_ID, FixtureBuilder.trajectories());
    } catch (Exception e) {
      return null;
    }
  }

  private static LlmClient buildLiveLlm() {
    try {
      return new OpenAiCompatibleClient();
    } catch (Exception e) {
      System.err.println("LLM init failed: " + e.getMessage());
      System.err.println("Hint: set LLM_API_KEY / LLM_BASE_URL / LLM_MODEL (see docs/how-to-run.md)");
      return null;
    }
  }

  private void printBanner(boolean mem) {
    System.out.println("mode=live backend=" + (mem ? "memory-fixture" : "hbase"));
    System.out.println("Type a natural-language query. Confirm with y when prompted.");
    System.out.println("Commands: /help /status on|off /quit");
    System.out.println("  Example: Find trajectories of taxi 8857 between "
        + "2008-02-03T21:20:00+08:00 and 2008-02-03T21:30:00+08:00");
  }

  private void printHelp() {
    printBanner(memory);
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
