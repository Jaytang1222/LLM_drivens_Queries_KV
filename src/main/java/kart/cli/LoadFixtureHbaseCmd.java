package kart.cli;

import kart.catalog.Manifest;
import kart.snapshot.FixtureBuilder;
import kart.snapshot.SnapshotBuilder;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.Callable;

/**
 * Load §18 fixture into isolated HBase tables and publish catalog {@code fixture_v1_ready}.
 * Required before experiment-path {@code chat}/{@code query-nl}/{@code query-ir} without {@code --memory}.
 */
@Command(name = "load-fixture-hbase",
    description = "Write fixture R/A/B/C to HBase (fixture_* tables) and publish fixture_v1_ready")
public final class LoadFixtureHbaseCmd implements Callable<Integer> {

  @Option(names = "--catalog", defaultValue = "catalog", description = "Catalog directory")
  private Path catalogDir;

  @Option(names = "--config-root", description = "Project root")
  private Path configRoot;

  @Override
  public Integer call() throws Exception {
    Path root = resolveRoot();
    Path site = root.resolve("config/hbase/hbase-site.xml");
    Path cat = catalogDir.isAbsolute() ? catalogDir : root.resolve(catalogDir);
    Manifest m = SnapshotBuilder.loadFixtureToHBase(site, cat);
    System.out.println("OK fixture loaded to HBase");
    System.out.println("manifest_id=" + m.manifest_id);
    System.out.println("tables=" + FixtureBuilder.TABLE_RAW + "," + FixtureBuilder.TABLE_META
        + "," + FixtureBuilder.TABLE_TIME + "," + FixtureBuilder.TABLE_ZORDER
        + "," + FixtureBuilder.TABLE_HASH);
    System.out.println("catalog=" + cat.toAbsolutePath());
    System.out.println("note=does not modify T-Drive traj_*_v1 tables");
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
