package kart.search;

/**
 * Index identifiers used in search actions (design.md §8.1).
 */
public final class IndexId {
  public static final String TIME = "idx_time";
  public static final String ZORDER = "idx_zorder";
  public static final String HASH = "idx_hash";
  public static final String XZ = "idx_xz";
  public static final String TSHAPE = "idx_tshape";
  public static final String XZT = "idx_xzt";

  private IndexId() {}

  /** Stable priority for RulePolicy / sorting: time → xzt → zorder → xz → tshape → hash. */
  public static int order(String indexId) {
    if (TIME.equals(indexId)) {
      return 0;
    }
    if (XZT.equals(indexId)) {
      return 1;
    }
    if (ZORDER.equals(indexId)) {
      return 2;
    }
    if (XZ.equals(indexId)) {
      return 3;
    }
    if (TSHAPE.equals(indexId)) {
      return 4;
    }
    if (HASH.equals(indexId)) {
      return 5;
    }
    return 99;
  }

  public static boolean isTemporal(String indexId) {
    return TIME.equals(indexId) || XZT.equals(indexId);
  }

  public static boolean isSpatial(String indexId) {
    return ZORDER.equals(indexId) || XZ.equals(indexId) || TSHAPE.equals(indexId);
  }
}
