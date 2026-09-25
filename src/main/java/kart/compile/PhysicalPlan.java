package kart.compile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Compiled physical plan: scan tasks plus fetch/DAG references.
 */
public final class PhysicalPlan {

  public String queryHash;
  public String manifestId;
  public String layoutHash;
  public String compilerVersion;
  public List<ScanTask> scanTasks = new ArrayList<ScanTask>();
  /** Fetch spec: table names used for meta/raw trajectory fetches. */
  public Map<String, String> fetchSpec = new LinkedHashMap<String, String>();
  /** plan_id of the logical DAG this physical plan was compiled from. */
  public String localDag;

  /** Scan tasks attributed to a given logical node. */
  public List<ScanTask> tasksForNode(String nodeId) {
    List<ScanTask> out = new ArrayList<ScanTask>();
    for (ScanTask t : scanTasks) {
      if (nodeId.equals(t.sourceNodeId)) {
        out.add(t);
      }
    }
    return out;
  }
}
