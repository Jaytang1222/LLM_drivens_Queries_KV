package kart.validation;

import kart.compile.PhysicalPlan;
import kart.plan.PlanEnvelope;

/**
 * Proof-of-validation handle. Only PlanValidator (same package) can construct it,
 * so holding a SafePlanHandle implies the plan passed all checks.
 */
public final class SafePlanHandle {

  private final PlanEnvelope plan;
  private final PhysicalPlan physicalPlan;
  private final String validationReportHash;

  SafePlanHandle(PlanEnvelope plan, PhysicalPlan physicalPlan, String validationReportHash) {
    this.plan = plan;
    this.physicalPlan = physicalPlan;
    this.validationReportHash = validationReportHash;
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
}
