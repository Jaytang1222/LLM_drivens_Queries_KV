package kart.search;

/**
 * Index identifiers used in search actions (design.md §8.1).
 */
public final class IndexId {
  public static final String TIME = "idx_time";
  public static final String ZORDER = "idx_zorder";
  public static final String HASH = "idx_hash";

  private IndexId() {}

  /** Stable priority for RulePolicy / sorting: time → zorder → hash. */
  public static int order(String indexId) {
    if (TIME.equals(indexId)) {
      return 0;
    }
    if (ZORDER.equals(indexId)) {
      return 1;
    }
    if (HASH.equals(indexId)) {
      return 2;
    }
    return 99;
  }
}
