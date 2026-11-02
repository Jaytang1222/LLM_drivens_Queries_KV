"""Shared helpers for Bao / ParamTree offline train from KART e2e.jsonl."""
from __future__ import annotations

import json
from pathlib import Path
from typing import Any, Dict, Iterable, Set


def load_workload_query_ids(paths: Iterable[Path]) -> Set[str]:
  """Collect query_id from BoundIR workload JSON file(s)."""
  out: Set[str] = set()
  for p in paths:
    path = Path(p)
    if not path.is_file():
      continue
    raw = json.loads(path.read_text(encoding="utf-8"))
    qs: Any = raw.get("queries", raw) if isinstance(raw, dict) else raw
    if isinstance(qs, dict):
      qs = qs.get("queries") or qs.get("items") or []
    if not isinstance(qs, list):
      continue
    for q in qs:
      if isinstance(q, dict):
        qid = q.get("query_id") or q.get("id")
        if qid is not None and str(qid).strip():
          out.add(str(qid))
  return out


def should_keep_row(row: Dict[str, Any], exclude_qids: Set[str]) -> bool:
  """Drop eval-overlap query_ids; keep rows that can yield a reward label."""
  qid = row.get("query_id")
  if qid is not None and str(qid) in exclude_qids:
    return False
  if str(row.get("stage", "e2e")).lower() not in ("e2e", ""):
    return False
  if row.get("plan_id") in (None, ""):
    return False
  if row.get("t_exec_ms") is None:
    return False
  return True
