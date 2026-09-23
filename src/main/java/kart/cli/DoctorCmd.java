package kart.cli;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.Admin;
import org.apache.hadoop.hbase.client.Connection;
import org.apache.hadoop.hbase.client.ConnectionFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.Callable;

@Command(name = "doctor", description = "Check JDK, HBase/ZooKeeper connectivity, and LLM env vars")
public final class DoctorCmd implements Callable<Integer> {

  @Option(names = "--config-root", description = "Project root containing config/")
  private Path configRoot;

  @Override
  public Integer call() throws Exception {
    Path root = resolveRoot();
    boolean ok = true;

    System.out.println("=== KART doctor ===");
    System.out.println("project root: " + root.toAbsolutePath());

    String javaVersion = System.getProperty("java.version");
    System.out.println("JDK: " + javaVersion + " (" + System.getProperty("java.home") + ")");
    if (!javaVersion.startsWith("1.8")) {
      System.out.println("WARN: expected Java 8; got " + javaVersion);
    }

    Path siteXml = root.resolve("config/hbase/hbase-site.xml");
    if (!Files.isRegularFile(siteXml)) {
      System.out.println("FAIL: missing " + siteXml);
      ok = false;
    } else {
      System.out.println("OK: hbase-site.xml at " + siteXml);
    }

    checkLlmEnv("LLM_BASE_URL");
    checkLlmEnv("LLM_MODEL");
    checkLlmEnv("LLM_API_KEY");

    Configuration conf = HBaseConfiguration.create();
    if (Files.isRegularFile(siteXml)) {
      conf.addResource(new org.apache.hadoop.fs.Path(siteXml.toUri().toString()));
    }
    String quorum = conf.get("hbase.zookeeper.quorum", "(unset)");
    String port = conf.get("hbase.zookeeper.property.clientPort", "(unset)");
    System.out.println("hbase.zookeeper.quorum=" + quorum + " clientPort=" + port);
    System.out.println("hbase-client jar version (Approximate): 2.2.3 (pom)");

    try (Connection conn = ConnectionFactory.createConnection(conf);
         Admin admin = conn.getAdmin()) {
      TableName[] tables = admin.listTableNames();
      System.out.println("OK: connected to HBase; tables=" + tables.length);
      for (TableName t : tables) {
        System.out.println("  - " + t.getNameAsString());
      }
      // Cluster status is best-effort on 2.2
      try {
        System.out.println("HBase cluster status: " + admin.getClusterStatus().getHBaseVersion());
      } catch (Throwable t) {
        System.out.println("WARN: could not read cluster HBase version: " + t.getMessage());
      }
    } catch (Exception e) {
      System.out.println("FAIL: HBase connection: " + e.getClass().getSimpleName() + ": " + e.getMessage());
      ok = false;
    }

    if (ok) {
      System.out.println("=== doctor: OK ===");
      return 0;
    }
    System.out.println("=== doctor: FAILED ===");
    return 1;
  }

  private static void checkLlmEnv(String name) {
    String v = System.getenv(name);
    if (v == null || v.trim().isEmpty()) {
      System.out.println("WARN: env " + name + " not set (LLM features unavailable)");
    } else {
      System.out.println("OK: env " + name + " is set (value not printed)");
    }
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
