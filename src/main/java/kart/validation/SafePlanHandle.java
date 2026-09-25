package kart.validation;

import kart.compile.PhysicalPlan;
import kart.plan.PlanEnvelope;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Proof-of-validation handle. Only PlanValidator (same package) can construct it,
 * so holding a SafePlanHandle implies the plan passed all checks.
 * Retains the full {@link ValidationReport} for NFR-3 / explain artifacts.
 */
public final class SafePlanHandle {

  private final PlanEnvelope plan;
  private final PhysicalPlan physicalPlan;
  private final String validationReportHash;
  private final List<CoverageCertificate> certificates;
  private final ValidationReport report;

  SafePlanHandle(PlanEnvelope plan, PhysicalPlan physicalPlan, ValidationReport report) {
    this.plan = plan;
    this.physicalPlan = physicalPlan;
    this.report = report;
    this.validationReportHash = report != null ? report.hashHex() : null;
    this.certificates = report == null || report.certificates() == null
        ? Collections.<CoverageCertificate>emptyList()
        : Collections.unmodifiableList(new ArrayList<CoverageCertificate>(report.certificates()));
  }

  public PlanEnvelope plan() {
    return plan;
  }

  public PhysicalPlan physicalPlan() {
    return physicalPlan;
  }

  public String validationReportHash() {
    return validationReportHash;
  }

  /** Full validation report that produced this handle (never null for validator-built handles). */
  public ValidationReport validationReport() {
    return report;
  }

  public List<CoverageCertificate> coverageCertificates() {
    return certificates;
  }
}
