"""
Encode KART SafePlan + CostCard into ParamTree's planinfo JSON.

Upstream QueryInference.predict expects:
  {planinfo: {Plan: <EXPLAIN JSON>}, config: {}, template: 0}

Modes (experimental honesty):
  - train: EXPLAIN ANALYZE shape with Actual* = measured t_exec (labels for fit)
  - infer: Plan-only EXPLAIN shape (no Actual* / no ANALYZE I/O) for pre-execution
           cost estimation — matches upstream get_op_info(..., execute=False)

Intentional I/O adapter only — prediction/training logic stays in adapters/upstream/paramtree.
"""
from __future__ import annotations

from typing import Any, Literal

from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "bao"))
from kart_pg_plan_encode import family_of  # noqa: E402

REL = "traj_raw"
IDX = {
    "T": "idx_t",
    "Z": "idx_z",
    "H": "idx_h",
    "TS": "idx_ts",
    "XZ": "idx_xz",
    "XZT": "idx_xzt",
    "XZTXZ": "idx_xztxz",
}

EncodeMode = Literal["train", "infer"]


def _leaf_plan_only(node_type: str, total_cost: float, plan_rows: float,
                    index_name: str | None = None) -> dict[str, Any]:
  rows = max(1.0, plan_rows)
  n: dict[str, Any] = {
      "Node Type": node_type,
      "Relation Name": REL,
      "Startup Cost": max(0.0, total_cost * 0.1),
      "Total Cost": float(max(0.0, total_cost)),
      "Plan Rows": rows,
      "Plan Width": 64,
      "Scan Direction": "Forward",
  }
  if index_name:
    n["Index Name"] = index_name
  return n


def _leaf_analyze(node_type: str, total_cost: float, plan_rows: float, actual_ms: float,
                  index_name: str | None = None) -> dict[str, Any]:
  rows = max(1.0, plan_rows)
  shared_read = max(1.0, rows / 100.0)
  n: dict[str, Any] = {
      "Node Type": node_type,
      "Relation Name": REL,
      "Startup Cost": max(0.0, total_cost * 0.1),
      "Total Cost": float(max(0.0, total_cost)),
      "Plan Rows": rows,
      "Plan Width": 64,
      "Actual Startup Time": max(0.0, actual_ms * 0.05),
      "Actual Total Time": float(max(0.001, actual_ms)),
      "Actual Rows": rows,
      "Actual Loops": 1,
      "Shared Hit Blocks": shared_read * 0.7,
      "Shared Read Blocks": shared_read * 0.3,
      "Shared Dirtied Blocks": 0,
      "Shared Written Blocks": 0,
      "Local Hit Blocks": 0,
      "Local Read Blocks": 0,
      "Local Dirtied Blocks": 0,
      "Local Written Blocks": 0,
      "Temp Read Blocks": 0,
      "Temp Written Blocks": 0,
      "I/O Read Time": max(0.0, actual_ms * 0.2),
      "I/O Write Time": 0,
      "Scan Direction": "Forward",
  }
  if index_name:
    n["Index Name"] = index_name
  return n


def encode_planinfo(plan_id: str, card: dict[str, Any] | None = None,
                    actual_ms: float | None = None,
                    mode: EncodeMode = "infer") -> dict[str, Any]:
  """
  mode=infer (default): Plan-only for optimizer selection.
  mode=train: requires actual_ms (measured t_exec) as Actual Total Time label.
  """
  card = card or {}
  est = float(card.get("estimated_ms") or 1.0)
  rows = float(
      card.get("estimated_index_rows")
      or (card.get("features") or {}).get("estimated_index_rows")
      or 1000.0
  )
  fam = family_of(plan_id)

  if mode == "train":
    if actual_ms is None:
      raise ValueError("encode_planinfo(mode=train) requires actual_ms=t_exec")
    act = float(actual_ms)
    leaf = _leaf_analyze
    if fam == "FULL":
      plan = leaf("Seq Scan", est, rows, act)
    elif fam == "COMBO":
      left = leaf("Index Scan", est * 0.45, rows * 0.5, act * 0.45, IDX["T"])
      right = leaf("Index Scan", est * 0.45, rows * 0.5, act * 0.45, IDX["Z"])
      plan = {
          "Node Type": "Nested Loop",
          "Join Type": "Inner",
          "Startup Cost": est * 0.1,
          "Total Cost": est,
          "Plan Rows": rows,
          "Plan Width": 64,
          "Actual Startup Time": act * 0.1,
          "Actual Total Time": act,
          "Actual Rows": rows,
          "Actual Loops": 1,
          "Shared Hit Blocks": 0,
          "Shared Read Blocks": 0,
          "Shared Dirtied Blocks": 0,
          "Shared Written Blocks": 0,
          "Local Hit Blocks": 0,
          "Local Read Blocks": 0,
          "Local Dirtied Blocks": 0,
          "Local Written Blocks": 0,
          "Temp Read Blocks": 0,
          "Temp Written Blocks": 0,
          "I/O Read Time": act * 0.1,
          "I/O Write Time": 0,
          "Plans": [left, right],
      }
    else:
      plan = leaf("Index Scan", est, rows, act, IDX.get(fam, "idx_t"))
    return {
        "planinfo": {
            "Plan": plan,
            "Planning Time": 0.0,
            "Execution Time": act,
        },
        "config": {},
        "template": 0,
        "query": plan_id,
    }

  # infer: Plan-only (no Actual* / ANALYZE fields)
  leaf = _leaf_plan_only
  if fam == "FULL":
    plan = leaf("Seq Scan", est, rows)
  elif fam == "COMBO":
    left = leaf("Index Scan", est * 0.45, rows * 0.5, IDX["T"])
    right = leaf("Index Scan", est * 0.45, rows * 0.5, IDX["Z"])
    plan = {
        "Node Type": "Nested Loop",
        "Join Type": "Inner",
        "Startup Cost": est * 0.1,
        "Total Cost": est,
        "Plan Rows": rows,
        "Plan Width": 64,
        "Plans": [left, right],
    }
  else:
    plan = leaf("Index Scan", est, rows, IDX.get(fam, "idx_t"))
  return {
      "planinfo": {"Plan": plan, "Planning Time": 0.0},
      "config": {},
      "template": 0,
      "query": plan_id,
  }
