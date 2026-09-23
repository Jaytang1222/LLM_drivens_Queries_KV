package kart.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * KART CLI root. Subcommands match requirement FR-8.
 */
@Command(
    name = "kart",
    mixinStandardHelpOptions = true,
    version = "kart 0.1.0-SNAPSHOT",
    description = "LLM-driven trajectory query over HBase (MVP)",
    subcommands = {
        DoctorCmd.class,
        ProfileDataCmd.class,
        BuildFixtureCmd.class,
        LoadFixtureHbaseCmd.class,
        BuildSnapshotCmd.class,
        VerifySnapshotCmd.class,
        BuildStatsCmd.class,
        BuildOracleCacheCmd.class,
        QueryIrCmd.class,
        QueryNlCmd.class,
        ExplainCmd.class,
        SmokeTdriveCmd.class,
        DemoFailuresCmd.class,
        ProbeLlmCmd.class,
        ChatCmd.class
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
