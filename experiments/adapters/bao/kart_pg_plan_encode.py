"""
Encode KART SafePlan + CostCard into PostgreSQL EXPLAIN-shaped JSON.

This is the *only* intentional domain adapter for Bao / LCM transplants.
Upstream Bao featurize.py and Zero-Shot postgres parsers consume this shape;
decision logic (Tree-CNN predict / select_plan argmin) stays in adapters/upstream.

Node-type mapping (documented, not learned):
  P_FULL              -> Seq Scan
  single-index family -> Index Scan
  multi-index / combo -> Nested Loop over two Index Scans
"""
from __future__ import annotations

from typing import Any


REL_TRAJ = "traj_raw"
REL_BY_FAMILY = {
    "T": "idx_t",
    "Z": "idx_z",
    "H": "idx_h",
    "TS": "idx_ts",
    "XZ": "idx_xz",
    "XZT": "idx_xzt",
    "XZTXZ": "idx_xztxz",
    "FULL": REL_TRAJ,
    "COMBO": REL_TRAJ,
    "other": REL_TRAJ,
}


def family_of(plan_id: str | None) -> str:
  if not plan_id:
    return "other"
  pid = plan_id.upper()
  if pid.startswith("P_FULL"):
    return "FULL"
  for fam in ("XZTXZ", "XZT", "XZ", "TS", "TZH", "TZ", "TH", "ZH", "T", "Z", "H"):
    if fam in pid:
      if fam in ("TZH", "TZ", "TH", "ZH"):
        return "COMBO"
      return fam
  return "other"


def _leaf(node_type: str, relation: str, total_cost: float, plan_rows: float,
          index_name: str | None = None) -> dict[str, Any]:
  n: dict[str, Any] = {
      "Node Type": node_type,
      "Relation Name": relation,
      "Total Cost": float(max(0.0, total_cost)),
      "Plan Rows": float(max(1.0, plan_rows)),
  }
  if index_name:
    n["Index Name"] = index_name
  return n


def encode_plan(plan_id: str, card: dict[str, Any] | None = None,
                buffers: dict[str, float] | None = None) -> dict[str, Any]:
  """Return a Bao-compatible plan dict (root Plan + optional Buffers)."""
  card = card or {}
  est = float(card.get("estimated_ms") or card.get("estimated_ms".upper()) or 1.0)
  rows = float(card.get("estimated_index_rows")
               or (card.get("features") or {}).get("estimated_index_rows")
               or 1000.0)
  fam = family_of(plan_id)
  if fam == "FULL":
    plan = _leaf("Seq Scan", REL_TRAJ, est, rows)
  elif fam == "COMBO":
    # Split cost across temporal + spatial leaves under Nested Loop.
    left = _leaf("Index Scan", REL_BY_FAMILY["T"], est * 0.45, rows * 0.5, "idx_t")
    right = _leaf("Index Scan", REL_BY_FAMILY["Z"], est * 0.45, rows * 0.5, "idx_z")
    plan = {
        "Node Type": "Nested Loop",
        "Total Cost": est,
        "Plan Rows": rows,
        "Plans": [left, right],
    }
  else:
    rel = REL_BY_FAMILY.get(fam, REL_TRAJ)
    plan = _leaf("Index Scan", rel, est, rows, f"idx_{fam.lower()}" if fam != "other" else None)

  out: dict[str, Any] = {"Plan": plan}
  # Bao TreeFeaturizer(have_cache_data=True) norms Buffers; constant zeros → NaN.
  # Derive a proxy buffer footprint from plan_rows (intentional domain stand-in for
  # PG shared_buffers counters — disclosed in TRANSPLANT.md).
  if buffers is not None:
    buf = dict(buffers)
  else:
    scale = max(1.0, rows / 100.0)
    buf = {
        REL_TRAJ: scale if fam == "FULL" else scale * 0.2,
        "idx_t": scale if fam in ("T", "COMBO", "TS") else 0.0,
        "idx_z": scale if fam in ("Z", "COMBO", "XZ", "XZT", "XZTXZ") else 0.0,
        "idx_h": scale if fam == "H" else 0.0,
        "idx_ts": scale if fam == "TS" else 0.0,
        "idx_xz": scale if fam in ("XZ", "XZT", "XZTXZ") else 0.0,
    }
  out["Buffers"] = buf
  return out


def default_buffers() -> dict[str, float]:
  # Non-degenerate defaults for select_plan's trailing buffer message.
  return {
      REL_TRAJ: 10.0,
      "idx_t": 5.0,
      "idx_z": 5.0,
      "idx_h": 5.0,
      "idx_ts": 5.0,
      "idx_xz": 5.0,
  }
