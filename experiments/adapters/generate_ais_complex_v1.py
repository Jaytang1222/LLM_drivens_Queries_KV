#!/usr/bin/env python3
"""Generate a 16-query AIS complex BoundIR development pool from ais_v1_ready.

Requires catalog/ais_v1_ready.manifest.json (READY). Oracle is built separately via
build-oracle-cache --evaluate-workload. Not a paper win-rate sample.
"""
from __future__ import annotations

import hashlib
import json
from pathlib import Path
from typing import Any, Dict, List, Optional

ROOT = Path(__file__).resolve().parents[2]
MANIFEST = ROOT / "catalog" / "ais_v1_ready.manifest.json"
WL = ROOT / "experiments" / "workloads"
PREFIX = "bound_ir_ais_complex_v1"
SEED = "2026-09-29-ais-complex-v1"
GENERATOR = "generate_ais_complex_v1.py/v1"


def clamp(v: float, lo: float, hi: float) -> float:
    return max(lo, min(hi, v))


def base_ir(qid: str, m: dict) -> dict:
    return {
        "ir_version": "1.0",
        "query_id": qid,
        "source": {"dataset_id": "ais_v1", "entity": "trajectory"},
        "temporal": None,
        "spatial": None,
        "predicates": [],
        "semantics": {"mode": "OBSERVED_POINT", "coupling": "SAME_POINT"},
        "similarity": None,
        "result": {"mode": "TRAJECTORY_IDS", "k": None, "tie_breaker": "TID_ASC"},
        "snapshot": {
            "manifest_id": m["manifest_id"],
            "semantics_version": m.get("semantics_version") or "point_similarity_v2",
        },
    }


def temporal(start_ms: int, end_ms: int) -> dict:
    return {"start_ms": int(start_ms), "end_ms": int(end_ms)}


def spatial(min_x: float, min_y: float, max_x: float, max_y: float, dom: dict) -> dict:
    return {
        "min_x": clamp(min_x, dom["xmin"], dom["xmax"]),
        "min_y": clamp(min_y, dom["ymin"], dom["ymax"]),
        "max_x": clamp(max_x, dom["xmin"], dom["xmax"]),
        "max_y": clamp(max_y, dom["ymin"], dom["ymax"]),
        "relation": "INTERSECTS",
        "boundary": "INCLUDED",
    }


def ids_query(qid: str, m: dict, t: Optional[dict], s: Optional[dict]) -> dict:
    ir = base_ir(qid, m)
    ir["temporal"] = t
    ir["spatial"] = s
    return ir


def topk_query(
    qid: str,
    m: dict,
    t: Optional[dict],
    s: Optional[dict],
    ref_tid: int,
    k: int,
    metric: str,
) -> dict:
    ir = base_ir(qid, m)
    ir["temporal"] = t
    ir["spatial"] = s
    ir["similarity"] = {
        "metric": metric,
        "reference_tid": int(ref_tid),
        "scope": "FULL_TRAJECTORY",
        "exclude_reference": True,
        "local_distance": "EUCLIDEAN",
        "normalization": "NONE",
    }
    ir["result"] = {"mode": "TOP_K", "k": int(k), "tie_breaker": "TID_ASC"}
    return ir


def main() -> int:
    if not MANIFEST.is_file():
        raise SystemExit(f"missing {MANIFEST}; build ais_v1_ready first")
    m = json.loads(MANIFEST.read_text(encoding="utf-8"))
    if str(m.get("status")) not in ("READY", "Status.READY") and m.get("status") != "READY":
        # Jackson may serialize enum as string READY
        if m.get("status") != "READY":
            raise SystemExit(f"manifest not READY: {m.get('status')}")
    dom = m["layout"]["domain"]
    epoch = int(m["layout"]["epoch_ms"])
    bucket = int(m["layout"].get("bucket_ms") or 600_000)
    cx = (dom["xmin"] + dom["xmax"]) / 2.0
    cy = (dom["ymin"] + dom["ymax"]) / 2.0
    w = dom["xmax"] - dom["xmin"]
    h = dom["ymax"] - dom["ymin"]

    # Time windows spanning days/weeks inside AIS May–June 2017 range (from data profile).
    # Prefer absolute UTC ms known from AIS file: 2017-05-09 .. 2017-06-25
    t_start = 1494338400000  # 2017-05-09 14:00 UTC
    t_end = 1498419780000    # ~2017-06-25 20:43 UTC
    day = 24 * 3600 * 1000
    week = 7 * day

    queries: List[dict] = []
    # Wide temporal + large spatial (ST range) — heavy candidate sets
    queries.append(ids_query(
        "ais_st_wide_week", m,
        temporal(t_start, t_start + week),
        spatial(cx - 0.45 * w, cy - 0.45 * h, cx + 0.45 * w, cy + 0.45 * h, dom),
    ))
    queries.append(ids_query(
        "ais_st_wide_2week", m,
        temporal(t_start, t_start + 2 * week),
        spatial(cx - 0.4 * w, cy - 0.4 * h, cx + 0.4 * w, cy + 0.4 * h, dom),
    ))
    queries.append(ids_query(
        "ais_s_large", m, None,
        spatial(cx - 0.48 * w, cy - 0.48 * h, cx + 0.48 * w, cy + 0.48 * h, dom),
    ))
    queries.append(ids_query(
        "ais_t_wide_month", m,
        temporal(t_start, min(t_end, t_start + 30 * day)),
        None,
    ))
    # Mid windows
    queries.append(ids_query(
        "ais_st_mid", m,
        temporal(t_start + 3 * day, t_start + 5 * day),
        spatial(cx - 0.2 * w, cy - 0.2 * h, cx + 0.2 * w, cy + 0.2 * h, dom),
    ))
    queries.append(ids_query(
        "ais_st_mid_b", m,
        temporal(t_start + 10 * day, t_start + 14 * day),
        spatial(cx - 0.25 * w, cy - 0.15 * h, cx + 0.25 * w, cy + 0.15 * h, dom),
    ))
    # Narrow controls
    queries.append(ids_query(
        "ais_st_control_small", m,
        temporal(t_start + day, t_start + day + 6 * bucket),
        spatial(cx - 0.05 * w, cy - 0.05 * h, cx + 0.05 * w, cy + 0.05 * h, dom),
    ))
    queries.append(ids_query(
        "ais_st_control_tiny", m,
        temporal(t_start + 2 * day, t_start + 2 * day + 3 * bucket),
        spatial(cx - 2000, cy - 2000, cx + 2000, cy + 2000, dom),
    ))

    # Top-K similarity — use early tid as reference (assigned sequentially from parse order).
    # tid 1 is first AIS traj after TidAssigner (1-based typically).
    ref = 1
    queries.append(topk_query(
        "ais_topk_dtw_wide", m,
        temporal(t_start, t_start + week),
        spatial(cx - 0.35 * w, cy - 0.35 * h, cx + 0.35 * w, cy + 0.35 * h, dom),
        ref, 5, "DTW",
    ))
    queries.append(topk_query(
        "ais_topk_dtw_2week", m,
        temporal(t_start, t_start + 2 * week),
        spatial(cx - 0.3 * w, cy - 0.3 * h, cx + 0.3 * w, cy + 0.3 * h, dom),
        ref, 5, "DTW",
    ))
    queries.append(topk_query(
        "ais_topk_frechet_wide", m,
        temporal(t_start + day, t_start + week),
        spatial(cx - 0.3 * w, cy - 0.3 * h, cx + 0.3 * w, cy + 0.3 * h, dom),
        ref, 3, "FRECHET",
    ))
    queries.append(topk_query(
        "ais_topk_hausdorff_mid", m,
        temporal(t_start + 5 * day, t_start + 8 * day),
        spatial(cx - 0.2 * w, cy - 0.2 * h, cx + 0.2 * w, cy + 0.2 * h, dom),
        ref, 3, "HAUSDORFF",
    ))
    queries.append(topk_query(
        "ais_topk_dtw_large_s", m, None,
        spatial(cx - 0.4 * w, cy - 0.4 * h, cx + 0.4 * w, cy + 0.4 * h, dom),
        ref, 5, "DTW",
    ))
    queries.append(topk_query(
        "ais_topk_dtw_control", m,
        temporal(t_start + day, t_start + day + 4 * bucket),
        spatial(cx - 3000, cy - 3000, cx + 3000, cy + 3000, dom),
        ref, 3, "DTW",
    ))
    # Extra hard temporal-only Top-K
    queries.append(topk_query(
        "ais_topk_dtw_t_wide", m,
        temporal(t_start, t_start + 10 * day),
        None,
        ref, 5, "DTW",
    ))
    queries.append(ids_query(
        "ais_st_port_focus", m,
        temporal(t_start + 7 * day, t_start + 10 * day),
        spatial(cx - 0.12 * w, cy - 0.12 * h, cx + 0.12 * w, cy + 0.12 * h, dom),
    ))

    assert len(queries) == 16, len(queries)
    for q in queries:
        q["snapshot"]["manifest_id"] = "ais_v1_ready"

    out_w = {
        "manifest_id": "ais_v1_ready",
        "semantics_version": m.get("semantics_version") or "point_similarity_v2",
        "workload_id": PREFIX,
        "evaluation_role": "ais_complex_dev_pool",
        "seed": SEED,
        "generator": GENERATOR,
        "note": "16 AIS complex queries for opportunity / hybrid cost coverage; not a win-rate sample.",
        "queries": queries,
    }
    path_w = WL / f"{PREFIX}.json"
    blob_w = (json.dumps(out_w, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
    path_w.write_bytes(blob_w)

    empty_oracle = {
        "manifest_id": "ais_v1_ready",
        "semantics_version": out_w["semantics_version"],
        "oracle_status": "pending_fullscan",
        "answers": [],
    }
    path_o = WL / f"{PREFIX}.oracle.json"
    path_o.write_bytes((json.dumps(empty_oracle, ensure_ascii=False, indent=2) + "\n").encode("utf-8"))

    prov = {
        "seed": SEED,
        "generator": GENERATOR,
        "manifest": "ais_v1_ready",
        "query_count": len(queries),
        "query_ids": [q["query_id"] for q in queries],
        "domain": dom,
        "epoch_ms": epoch,
        "sha256": {
            path_w.name: hashlib.sha256(blob_w).hexdigest(),
        },
    }
    (WL / f"{PREFIX}.provenance.json").write_text(
        json.dumps(prov, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    print(f"wrote {len(queries)} queries -> {path_w}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
