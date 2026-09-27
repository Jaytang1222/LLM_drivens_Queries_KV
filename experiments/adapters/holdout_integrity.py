#!/usr/bin/env python3
"""
Holdout integrity checks (fail-closed).

Rejects:
  - duplicate query_id
  - same catalog.group assigned to more than one split
  - identical normalized BoundIR bodies (query_id stripped) across different splits
"""
from __future__ import annotations

import copy
import hashlib
import json
from typing import Any, Dict, Iterable, List, Tuple


def canonical_ir_blob(q: dict) -> str:
    """Stable JSON of BoundIR with query_id removed (split leakage detector)."""
    body = copy.deepcopy(q)
    body.pop("query_id", None)
    return json.dumps(body, sort_keys=True, separators=(",", ":"), ensure_ascii=False)


def canonical_ir_hash(q: dict) -> str:
    return hashlib.sha256(canonical_ir_blob(q).encode("utf-8")).hexdigest()


def assert_holdout_integrity(queries: List[dict], catalog: Dict[str, dict]) -> None:
    if not queries:
        raise ValueError("holdout has no queries")
    seen_ids = set()
    group_split: Dict[str, str] = {}
    hash_split: Dict[str, Tuple[str, str]] = {}  # hash -> (split, query_id)
    for q in queries:
        qid = q.get("query_id")
        if not qid:
            raise ValueError("query missing query_id")
        if qid in seen_ids:
            raise ValueError(f"duplicate query_id: {qid}")
        seen_ids.add(qid)
        meta = catalog.get(qid)
        if not meta:
            raise ValueError(f"catalog missing for {qid}")
        split = meta.get("split")
        group = meta.get("group")
        if split not in ("train", "val", "test"):
            raise ValueError(f"{qid}: invalid split {split!r}")
        if not group:
            raise ValueError(f"{qid}: missing catalog.group")
        prev = group_split.get(group)
        if prev is not None and prev != split:
            raise ValueError(
                f"group {group!r} leaks across splits: {prev} and {split} "
                f"(query {qid})"
            )
        group_split[group] = split
        h = canonical_ir_hash(q)
        hit = hash_split.get(h)
        if hit is not None and hit[0] != split:
            raise ValueError(
                f"normalized BoundIR hash collision across splits: "
                f"{hit[1]} ({hit[0]}) vs {qid} ({split})"
            )
        if hit is None:
            hash_split[h] = (split, qid)


def summarize_splits(catalog: Dict[str, dict]) -> Dict[str, Any]:
    counts = {"train": 0, "val": 0, "test": 0}
    groups = {"train": set(), "val": set(), "test": set()}
    for meta in catalog.values():
        sp = meta.get("split")
        if sp in counts:
            counts[sp] += 1
            groups[sp].add(meta.get("group"))
    return {
        "n_by_split": counts,
        "groups_by_split": {k: sorted(v) for k, v in groups.items()},
    }
