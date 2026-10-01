#!/usr/bin/env python3
"""Prepare a new pre-KART candidate pool from the READY T-Drive snapshot.

Reuse deterministic pilot candidate rules, exclude already-frozen semantics,
and add four fixed small-window controls. CBO/Bao/full-safe-set screening picks
the final set; this generator never reads KART outcomes.
"""
from __future__ import annotations

import copy
import hashlib
import json
from pathlib import Path
from typing import Any, Dict, List, Set

ROOT = Path(__file__).resolve().parents[2]
WL = ROOT / "experiments/workloads"
SEED = "cbo-opportunity-mechanism-v2-seed-1"
GENERATOR = "generate_cbo_opportunity_candidates_v2.py/v1"
SOURCE_CANDIDATES = WL / "bound_ir_cbo_llm_pilot_v1.candidates.json"
BLOCKED_FILES = [
    WL / "bound_ir_advantage_v2.json",
    WL / "bound_ir_v1.json",
    WL / "bound_ir_holdout_v2_test.json",
    WL / "bound_ir_cbo_llm_pilot_v1.json",
    WL / "bound_ir_cbo_opportunity_dev_smoke_v1.json",
]
OUT_PREFIX = "bound_ir_cbo_opportunity_candidates_v2"
OUT_CAND = WL / f"{OUT_PREFIX}.candidates.json"
OUT_WORKLOAD = WL / f"{OUT_PREFIX}.json"
OUT_PROV = WL / f"{OUT_PREFIX}.provenance.json"
OUT_EMPTY_ORACLE = WL / f"{OUT_PREFIX}.screen-oracle.json"


def load(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def query_fp(query: dict) -> str:
    body = {k: v for k, v in query.items() if k not in ("query_id", "_pilot_meta")}
    blob = json.dumps(body, sort_keys=True, separators=(",", ":"), ensure_ascii=False)
    return hashlib.sha256(blob.encode("utf-8")).hexdigest()


def write_new(path: Path, obj: Any) -> bytes:
    data = (json.dumps(obj, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
    path.write_bytes(data)
    return data


def main() -> int:
    targets = (
        OUT_CAND, OUT_WORKLOAD, OUT_PROV, OUT_EMPTY_ORACLE,
        WL / "bound_ir_cbo_opportunity_verify_v2.json",
        WL / "bound_ir_cbo_opportunity_verify_v2.provenance.json",
        WL / "bound_ir_cbo_opportunity_verify_v2.oracle.json",
    )
    existing = [str(p) for p in targets if p.exists()]
    if existing:
        raise SystemExit("refusing to overwrite existing versioned inputs: " + ", ".join(existing))

    source = load(SOURCE_CANDIDATES)
    blocked: Set[str] = set()
    blocked_counts: Dict[str, int] = {}
    for path in BLOCKED_FILES:
        count = 0
        if path.is_file():
            for q in load(path).get("queries", []):
                blocked.add(query_fp(q))
                count += 1
        blocked_counts[path.name] = count

    selected: List[dict] = []
    seen: Set[str] = set()
    excluded: List[dict] = []

    def add(query: dict, category: str, reason: str, source_id: str) -> None:
        fp = query_fp(query)
        if fp in blocked:
            excluded.append({"query_id": query.get("query_id"), "reason": "matches_locked_or_seen_semantics"})
            return
        if fp in seen:
            excluded.append({"query_id": query.get("query_id"), "reason": "duplicate_candidate_semantics"})
            return
        seen.add(fp)
        selected.append({
            "query": query,
            "category": category,
            "reason": reason,
            "source_query_id": source_id,
            "fingerprint": fp,
        })

    for i, item in enumerate(source.get("candidates", []), start=1):
        old_q = item.get("query") or {}
        old_id = str(old_q.get("query_id") or f"candidate_{i:02d}")
        q = copy.deepcopy(old_q)
        q["query_id"] = f"mechv2_{i:03d}"
        add(q, str(item.get("category") or "candidate"),
            str(item.get("reason") or "deterministic inherited candidate rule"),
            str(item.get("source_query_id") or old_id))

    # Add four fixed small-window controls at unused times within the same snapshot.
    adv = load(WL / "bound_ir_advantage_v2.json")
    anchors = {q.get("query_id"): q for q in adv.get("queries", [])}
    base = anchors.get("st_ss_1")
    if not base or not isinstance(base.get("temporal"), dict):
        raise SystemExit("missing small TZ control template st_ss_1")
    for j, offset_min in enumerate((21, 27, 33, 39), start=1):
        q = copy.deepcopy(base)
        q["query_id"] = f"mechv2_control_{j:02d}"
        shift = offset_min * 60_000
        q["temporal"]["start_ms"] += shift
        q["temporal"]["end_ms"] += shift
        add(q, "normal_control",
            f"small TZ control shifted by fixed {offset_min} minutes from st_ss_1",
            "st_ss_1")

    if not selected:
        raise SystemExit("no candidates survived semantic isolation")

    ranks = {"cbo_candidate_miss": 0, "bao_tzh_full_risk": 1, "normal_control": 2}
    selected.sort(key=lambda x: (ranks.get(x["category"], 9), x["query"]["query_id"]))
    queries = [x["query"] for x in selected]
    workload = {
        "manifest_id": "tdrive_v1_ready",
        "semantics_version": "point_dtw_v1",
        "workload_id": OUT_PREFIX,
        "evaluation_role": "pre_kart_cbo_only_mechanism_candidate_pool",
        "seed": SEED,
        "queries": queries,
    }
    cat_counts = {c: sum(1 for x in selected if x["category"] == c)
                  for c in sorted({x["category"] for x in selected})}
    cand_doc = {
        "seed": SEED,
        "generator": GENERATOR,
        "manifest_id": "tdrive_v1_ready",
        "semantics_version": "point_dtw_v1",
        "source_candidate_pool": SOURCE_CANDIDATES.name,
        "n_candidates": len(selected),
        "category_counts": cat_counts,
        "excluded_locked_query_counts": blocked_counts,
        "excluded_rows": excluded,
        "selection_rule": "CBO/Bao/full-safe-set screen only; no KART arm results read before freeze",
        "candidates": selected,
    }
    empty_oracle = {
        "manifest_id": "tdrive_v1_ready",
        "semantics_version": "point_dtw_v1",
        "oracle_status": "not_applicable_to_plan_screen",
        "answers": [],
    }

    OUT_CAND.parent.mkdir(parents=True, exist_ok=True)
    encoded_cand = write_new(OUT_CAND, cand_doc)
    encoded_workload = write_new(OUT_WORKLOAD, workload)
    encoded_oracle = write_new(OUT_EMPTY_ORACLE, empty_oracle)
    provenance = {
        "evaluation_role": "candidate_discovery_before_kart_outcomes",
        "seed": SEED,
        "generator": GENERATOR,
        "manifest_id": "tdrive_v1_ready",
        "semantics_version": "point_dtw_v1",
        "candidate_count": len(selected),
        "category_counts": cat_counts,
        "selection_quota_for_freeze": {
            "cbo_candidate_miss": 8,
            "bao_tzh_full_risk": 3,
            "normal_control": 1,
        },
        "source_sha256": {p.name: sha(p) for p in [SOURCE_CANDIDATES] + BLOCKED_FILES if p.is_file()},
        "sha256": {
            OUT_CAND.name: hashlib.sha256(encoded_cand).hexdigest(),
            OUT_WORKLOAD.name: hashlib.sha256(encoded_workload).hexdigest(),
            OUT_EMPTY_ORACLE.name: hashlib.sha256(encoded_oracle).hexdigest(),
        },
        "note": "The screen oracle is intentionally empty; it is not used for E2. A fresh FullScan oracle is required after freezing the selected workload.",
    }
    write_new(OUT_PROV, provenance)
    print(json.dumps({
        "candidate_count": len(selected),
        "category_counts": cat_counts,
        "excluded": len(excluded),
        "workload": str(OUT_WORKLOAD.relative_to(ROOT)),
        "candidate_file": str(OUT_CAND.relative_to(ROOT)),
    }, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
