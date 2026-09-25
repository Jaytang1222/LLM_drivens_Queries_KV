package kart.search;

/**
 * Kind of legal plan-search action (IMPLEMENTATION_PLAN §11.3).
 */
public enum ActionKind {
  START,
  INTERSECT,
  REPLACE,
  CHOOSE_MERGE,
  PARTITION_UNION,
  FINISH
}
