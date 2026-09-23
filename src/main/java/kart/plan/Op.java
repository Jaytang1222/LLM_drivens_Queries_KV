package kart.plan;

/**
 * Plan operators, matching schemas/plan.schema.json enum exactly.
 */
public enum Op {
  TIME_RANGE_SCAN,
  ZORDER_RANGE_SCAN,
  EQUALITY_LOOKUP,
  FULL_SCAN_CHUNKS,
  INTERSECT,
  UNION,
  DEDUPLICATE,
  FETCH_TRAJECTORY_CHUNK,
  EXACT_ST_FILTER,
  PROJECT_TRAJECTORY_IDS,
  EXCLUDE_REFERENCE,
  BATCH_GET_TRAJECTORY,
  SIMILARITY,
  TOP_K,
  RETURN_TRAJECTORY_IDS;

  /** Output data type of this operator. */
  public DataType outputType() {
    switch (this) {
      case TIME_RANGE_SCAN:
      case ZORDER_RANGE_SCAN:
      case EQUALITY_LOOKUP:
      case INTERSECT:
      case UNION:
      case DEDUPLICATE:
        return DataType.CHUNK_REF_SET;
      case FULL_SCAN_CHUNKS:
        // design.md §7.1: FULL_SCAN_CHUNKS → ChunkBatch (raw scan + decode)
        return DataType.CHUNK_BATCH;
      case FETCH_TRAJECTORY_CHUNK:
        return DataType.CHUNK_BATCH;
      case EXACT_ST_FILTER:
        return DataType.MATCHED_CHUNK;
      case PROJECT_TRAJECTORY_IDS:
      case EXCLUDE_REFERENCE:
        return DataType.TRAJECTORY_IDS;
      case BATCH_GET_TRAJECTORY:
        return DataType.TRAJECTORY;
      case SIMILARITY:
        return DataType.SCORED_TRAJ;
      case TOP_K:
      case RETURN_TRAJECTORY_IDS:
        return DataType.RESULT;
      default:
        return DataType.NONE;
    }
  }

  /** Expected input data type for each input edge (uniform per op); NONE means leaf. */
  public DataType inputType() {
    switch (this) {
      case TIME_RANGE_SCAN:
      case ZORDER_RANGE_SCAN:
      case EQUALITY_LOOKUP:
      case FULL_SCAN_CHUNKS:
        return DataType.NONE;
      case INTERSECT:
      case UNION:
      case DEDUPLICATE:
      case FETCH_TRAJECTORY_CHUNK:
        return DataType.CHUNK_REF_SET;
      case EXACT_ST_FILTER:
        return DataType.CHUNK_BATCH;
      case PROJECT_TRAJECTORY_IDS:
        return DataType.MATCHED_CHUNK;
      case EXCLUDE_REFERENCE:
      case BATCH_GET_TRAJECTORY:
      case RETURN_TRAJECTORY_IDS:
        return DataType.TRAJECTORY_IDS;
      case SIMILARITY:
        return DataType.TRAJECTORY;
      case TOP_K:
        return DataType.SCORED_TRAJ;
      default:
        return DataType.NONE;
    }
  }

  /** True if the op is an index/table access leaf (no inputs). */
  public boolean isAccess() {
    return this == TIME_RANGE_SCAN || this == ZORDER_RANGE_SCAN
        || this == EQUALITY_LOOKUP || this == FULL_SCAN_CHUNKS;
  }

  /** Minimum number of inputs. */
  public int minInputs() {
    if (isAccess()) {
      return 0;
    }
    if (this == INTERSECT || this == UNION) {
      return 2;
    }
    return 1;
  }

  /** Maximum number of inputs (Integer.MAX_VALUE for variadic). */
  public int maxInputs() {
    if (isAccess()) {
      return 0;
    }
    if (this == INTERSECT || this == UNION) {
      return Integer.MAX_VALUE;
    }
    return 1;
  }

  /** True for set ops whose input order is irrelevant (isomorphism). */
  public boolean isCommutative() {
    return this == INTERSECT || this == UNION;
  }
}
