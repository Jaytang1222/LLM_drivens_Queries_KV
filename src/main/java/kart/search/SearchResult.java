package kart.search;

import kart.plan.PlanEnvelope;
import kart.validation.SafePlanHandle;
import kart.validation.ValidationReport;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Output of plan search: candidates, SafePlan set, rejection reasons, log.
 */
public final class SearchResult {

  public List<PlanEnvelope> candidates = new ArrayList<PlanEnvelope>();
  public List<SafePlanHandle> safePlans = new ArrayList<SafePlanHandle>();
  public List<Rejection> rejections = new ArrayList<Rejection>();
  public final SearchLog log = new SearchLog();
  public SearchBudget budget;

  public static final class Rejection {
    public final String planId;
    public final String signature;
    public final ValidationReport report;

    public Rejection(String planId, String signature, ValidationReport report) {
      this.planId = planId;
      this.signature = signature;
      this.report = report;
    }

    public boolean hasCheck(String checkName) {
      if (report == null) {
        return false;
      }
      for (ValidationReport.Finding f : report.findings()) {
        if (!f.ok && checkName.equals(f.check)) {
          return true;
        }
      }
      return false;
    }
  }

  public Set<String> safeSignatures() {
    Set<String> out = new LinkedHashSet<String>();
    for (SafePlanHandle h : safePlans) {
      out.add(h.plan().signature());
    }
    return out;
  }

  public Set<String> safePlanIds() {
    Set<String> out = new LinkedHashSet<String>();
    for (SafePlanHandle h : safePlans) {
      if (h.plan().plan_id != null) {
        out.add(h.plan().plan_id);
      }
    }
    return out;
  }

  public boolean hasRejectionWith(String checkName) {
    for (Rejection r : rejections) {
      if (r.hasCheck(checkName)) {
        return true;
      }
    }
    return false;
  }
}
