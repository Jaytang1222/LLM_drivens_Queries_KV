package kart.cli;

import kart.snapshot.FixtureBuilder;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.Callable;

@Command(name = "build-fixture", description = "Generate deterministic fixture-v1 (R/A/B/C)")
public final class BuildFixtureCmd implements Callable<Integer> {

  @Option(names = "--out", description = "Output directory", defaultValue = "testdata/fixture-v1")
  private Path outDir;

  @Option(names = "--config-root", description = "Project root")
  private Path configRoot;

  @Override
  public Integer call() throws Exception {
    Path root = resolveRoot();
    Path target = outDir.isAbsolute() ? outDir : root.resolve(outDir);
    FixtureBuilder.build(target);
    System.out.println("Wrote fixture to " + target.toAbsolutePath());
    return 0;
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
