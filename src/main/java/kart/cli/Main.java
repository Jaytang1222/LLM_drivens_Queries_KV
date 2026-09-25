package kart.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * KART CLI root — Live LLM → HBase experiment path.
 */
@Command(
    name = "kart",
    mixinStandardHelpOptions = true,
    version = "kart 0.1.0-SNAPSHOT",
    description = "LLM-driven trajectory query over HBase (experiment)",
    subcommands = {
        DoctorCmd.class,
        ProfileDataCmd.class,
        BuildSnapshotCmd.class,
        VerifySnapshotCmd.class,
        BuildStatsCmd.class,
        BuildFixtureCmd.class,
        BuildOracleCacheCmd.class,
        QueryIrCmd.class,
        QueryNlCmd.class,
        ExplainCmd.class,
        FitCostCmd.class,
        SmokeTdriveCmd.class,
        ProbeLlmCmd.class,
        ChatCmd.class,
        BenchSuiteCmd.class
    }
)
public final class Main implements Runnable {

  @Override
  public void run() {
    CommandLine.usage(this, System.out);
  }

  public static void main(String[] args) {
    int code = new CommandLine(new Main()).execute(args);
    System.exit(code);
  }
}
