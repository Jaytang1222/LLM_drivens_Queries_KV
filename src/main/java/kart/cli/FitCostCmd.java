package kart.cli;

import kart.catalog.CatalogStore;
import kart.catalog.StatsSnapshot;
import kart.cost.FeedbackCalibrator;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.Callable;

/**
 * Offline §16 Feedback: fit cost coeffs + optional filter_pass writeback to StatsSnapshot.
 */
@Command(name = "fit-cost", description = "Offline cost/feedback calibration (§16)")
public final class FitCostCmd implements Callable<Integer> {

  @Option(names = "--pairs", description = "Directory of <qid>/{cost_features,trace}.json",
      defaultValue = "runs/cost_calib")
  Path pairsDir;

  @Option(names = "--report", defaultValue = "experiments/results/cost_calibration_report.json")
  Path reportOut;

  @Option(names = "--coeffs", defaultValue = "experiments/results/cost_coeffs_calibrated.json")
  Path coeffsOut;

  @Option(names = "--catalog", defaultValue = "catalog")
  Path catalogDir;

  @Option(names = "--manifest", description = "If set, write filter_pass_rate into stats snapshot")
  String manifestId;

  @Option(names = "--config-root")
  Path configRoot;

  @Override
  public Integer call() throws Exception {
    Path root = resolveRoot();
    Path pairs = pairsDir.isAbsolute() ? pairsDir : root.resolve(pairsDir);
    Path report = reportOut.isAbsolute() ? reportOut : root.resolve(reportOut);
    Path coeffs = coeffsOut.isAbsolute() ? coeffsOut : root.resolve(coeffsOut);

    FeedbackCalibrator cal = new FeedbackCalibrator();
    FeedbackCalibrator.Report r = cal.calibrate(pairs, report, coeffs, 0.6, 0.2);
    System.out.println("fit-cost OK pairs_train=" + r.trainSize
        + " val=" + r.valSize
        + " test=" + r.testSize
        + " calibrated=" + r.calibrated
        + " train_mae=" + r.trainMae
        + " report=" + report);

    if (manifestId != null && r.filterPassRate != null) {
      Path cat = catalogDir.isAbsolute() ? catalogDir : root.resolve(catalogDir);
      CatalogStore store = new CatalogStore(cat);
      StatsSnapshot stats = store.loadStats(manifestId).orElse(null);
      if (stats == null) {
        System.err.println("stats not found for " + manifestId);
        return 1;
      }
      stats.filter_pass_rate = r.filterPassRate.doubleValue();
      stats.filter_pass_samples = r.filterPassSamples != null ? r.filterPassSamples.longValue() : 0L;
      stats.filter_pass_hits = r.filterPassHits != null ? r.filterPassHits.longValue() : 0L;
      store.saveStats(stats);
      System.out.println("updated filter_pass_rate=" + stats.filter_pass_rate
          + " on " + manifestId);
    }
    return r.calibrated ? 0 : 2;
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
