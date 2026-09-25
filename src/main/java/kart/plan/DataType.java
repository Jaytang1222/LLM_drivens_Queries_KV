package kart.plan;

/**
 * Logical data types flowing between plan operators.
 */
public enum DataType {
  NONE,
  CHUNK_REF_SET,
  CHUNK_BATCH,
  MATCHED_CHUNK,
  TRAJECTORY_IDS,
  TRAJECTORY,
  SCORED_TRAJ,
  RESULT
}
