package kart.cli;

import kart.bench.SuiteRunner;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.Callable;

@Command(name = "bench-suite",
    description = "Run a compare/ablation/param / E1-E3 experiment suite (Oracle-gated trials)")
public final class BenchSuiteCmd implements Callable<Integer> {

  @Option(names = "--suite", required = true, description = "Suite YAML path")
  private Path suitePath;

  @Option(names = "--run-id", description = "Result directory id under experiments/results/")
  private String runId;

  @Option(names = "--arm", split = ",",
      description = "Filter arms (comma-separated); default: all in suite")
  private String[] arms;

  @Option(names = "--factor", split = ",",
      description = "Filter ablation factors (comma-separated); default: enabled factors")
  private String[] factors;

  @Option(names = "--allow-unsafe", description = "Allow factors/suites marked unsafe")
  private boolean allowUnsafe;

  @Option(names = "--cache", description = "Cache protocol cold|warm (default cold)")
  private String cache = "cold";

  @Option(names = "--limit", description = "Optional query limit (overrides suite YAML limit)")
  private Integer limit;

  @Option(names = "--trials", description = "Optional trial count (overrides suite YAML trials)")
  private Integer trials;

  @Option(names = "--workload", description = "Override suite workload JSON path")
  private Path workload;

  @Option(names = "--oracle", description = "Override suite oracle JSON path")
  private Path oracle;

  @Option(names = "--keep-artifacts", description = "Write per-query artifacts/ (off by default)")
  private boolean keepArtifacts;

  @Option(names = "--config-root", description = "Project root (default: -Dkart.root or cwd)")
  private Path configRoot;

  @Override
  public Integer call() throws Exception {
    Path root = resolveRoot();
    Path suite = suitePath.isAbsolute() ? suitePath : root.resolve(suitePath);
    String rid = runId;
    if (rid == null || rid.trim().isEmpty()) {
      rid = "bench-" + new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
    }

    SuiteRunner.Options opt = new SuiteRunner.Options();
    opt.root = root;
    opt.suitePath = suite;
    opt.runId = rid.trim();
    opt.allowUnsafe = allowUnsafe;
    opt.armFilter = toSet(arms);
    opt.factorFilter = toSet(factors);
    opt.cache = cache == null || cache.trim().isEmpty() ? "cold" : cache.trim().toLowerCase();
    if (!"cold".equals(opt.cache) && !"warm".equals(opt.cache)) {
      throw new IllegalArgumentException("--cache must be cold or warm");
    }
    opt.limit = limit;
    opt.trials = trials;
    opt.workloadOverride = workload;
    opt.oracleOverride = oracle;
    opt.keepArtifacts = keepArtifacts;

    SuiteRunner.Result r = new SuiteRunner().run(opt);
    return Integer.valueOf(r.exitCode);
  }

  private static Set<String> toSet(String[] arr) {
    Set<String> s = new LinkedHashSet<String>();
    if (arr == null) {
      return s;
    }
    for (String a : arr) {
      if (a != null && !a.trim().isEmpty() && !"all".equalsIgnoreCase(a.trim())) {
        s.add(a.trim());
      }
    }
    return s;
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
