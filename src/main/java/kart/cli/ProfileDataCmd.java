package kart.cli;

import kart.snapshot.DataProfiler;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.Callable;

@Command(name = "profile-data", description = "Profile T-Drive part files; write docs/tdrive-profile.md")
public final class ProfileDataCmd implements Callable<Integer> {

  @Option(names = "--data", description = "Directory with part-* files", defaultValue = "datasets/tdrive")
  private Path dataDir;

  @Option(names = "--out", description = "Markdown report path", defaultValue = "docs/tdrive-profile.md")
  private Path out;

  @Option(names = "--config-root")
  private Path configRoot;

  @Override
  public Integer call() throws Exception {
    Path root = resolveRoot();
    Path data = dataDir.isAbsolute() ? dataDir : root.resolve(dataDir);
    Path outPath = out.isAbsolute() ? out : root.resolve(out);
    System.out.println("Profiling " + data.toAbsolutePath() + " ...");
    DataProfiler profiler = new DataProfiler();
    DataProfiler.Profile profile = profiler.profileDirectory(data);
    String md = profiler.toMarkdown(profile, data);
    Files.createDirectories(outPath.getParent());
    Files.write(outPath, md.getBytes(StandardCharsets.UTF_8));
    System.out.println("Wrote " + outPath.toAbsolutePath());
    System.out.println("trajectories=" + profile.trajectories + " points=" + profile.points
        + " parseRejected=" + profile.parseRejected);
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
