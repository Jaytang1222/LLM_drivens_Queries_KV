package kart.validation;

import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic coverage proof artifact (IMPLEMENTATION_PLAN.md §12.5).
 * Produced by the compiler/validator — never trusted from plan JSON {@code safe=true}.
 */
public final class CoverageCertificate {

  public String query_hash;
  public String manifest_id;
  public String layout_hash;
  public String compiler_version = "kart_compiler_v1";
  public String index_id;
  public String predicate_binding;
  public List<Integer> required_shards = new ArrayList<Integer>();
  public List<String> required_bucket_or_cell_cover = new ArrayList<String>();
  public List<String> emitted_physical_ranges = new ArrayList<String>();
  public List<String> unresolved_obligations = new ArrayList<String>();

  public boolean complete() {
    return unresolved_obligations == null || unresolved_obligations.isEmpty();
  }
}
