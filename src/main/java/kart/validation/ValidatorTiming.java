package kart.validation;

/**
 * Low-overhead PlanValidator sub-stage timings and counters (refine_3).
 * Parent {@code t_fixed_safety_ms} remains the wall; do not sum children into it.
 */
public final class ValidatorTiming {

  public String coverageAlgo = "indexed";
  public long t_structure_ms;
  public long t_semantic_ms;
  public long t_coverage_ms;
  public long t_physical_safety_ms;
  public long t_range_decode_ms;
  public long t_coverage_index_ms;
  public long t_coverage_probe_ms;
  public long t_coverage_cert_ms;
  public long coverage_probe_count;
  public long range_count;
  public long range_compare_count;
  public long range_decode_count;

  public void putExtras(java.util.Map<String, Object> extras) {
    if (extras == null) {
      return;
    }
    extras.put("validator_coverage_algo", coverageAlgo);
    extras.put("t_structure_ms", Long.valueOf(t_structure_ms));
    extras.put("t_semantic_ms", Long.valueOf(t_semantic_ms));
    extras.put("t_coverage_ms", Long.valueOf(t_coverage_ms));
    extras.put("t_physical_safety_ms", Long.valueOf(t_physical_safety_ms));
    extras.put("t_range_decode_ms", Long.valueOf(t_range_decode_ms));
    extras.put("t_coverage_index_ms", Long.valueOf(t_coverage_index_ms));
    extras.put("t_coverage_probe_ms", Long.valueOf(t_coverage_probe_ms));
    extras.put("t_coverage_cert_ms", Long.valueOf(t_coverage_cert_ms));
    extras.put("coverage_probe_count", Long.valueOf(coverage_probe_count));
    extras.put("range_count", Long.valueOf(range_count));
    extras.put("range_compare_count", Long.valueOf(range_compare_count));
    extras.put("range_decode_count", Long.valueOf(range_decode_count));
  }
}
