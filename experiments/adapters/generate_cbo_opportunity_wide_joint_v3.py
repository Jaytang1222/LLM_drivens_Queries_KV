#!/usr/bin/env python3
"""Generate a wide-window / joint-predicate candidate pool for opportunity census.

Deterministic expansions from advantage_v2 templates. Excludes fingerprints of
locked/seen workloads. Does not read KART outcomes. Seed fixed.
"""
from __future__ import annotations

import copy
import hashlib
import json
from pathlib import Path
from typing import Any, Dict, List, Optional, Set, Tuple

ROOT = Path(__file__).resolve().parents[2]
WL = ROOT / "experiments/workloads"
SEED = "2026-09-29-cbo-opportunity-wide-joint-v3"
GENERATOR = "generate_cbo_opportunity_wide_joint_v3.py/v1"
OUT_PREFIX = "bound_ir_cbo_opportunity_candidates_v3"

ADV2 = WL / "bound_ir_advantage_v2.json"
BLOCKED = [
    ADV2,
    WL / "bound_ir_v1.json",
    WL / "bound_ir_holdout_v2_test.json",
    WL / "bound_ir_cbo_llm_pilot_v1.json",
    WL / "bound_ir_cbo_llm_pilot_v1.candidates.json",
    WL / "bound_ir_cbo_opportunity_dev_smoke_v1.json",
    WL / "bound_ir_cbo_opportunity_verify_v1.json",
    WL / "bound_ir_cbo_opportunity_verify_v2.json",
    WL / "bound_ir_cbo_opportunity_candidates_v2.json",
    WL / "bound_ir_cbo_opportunity_candidates_v2.candidates.json",
]

OUT_CAND = WL / f"{OUT_PREFIX}.candidates.json"
OUT_WL = WL / f"{OUT_PREFIX}.json"
OUT_PROV = WL / f"{OUT_PREFIX}.provenance.json"


def load(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def query_fp(query: dict) -> str:
    body = {k: v for k, v in query.items() if k not in ("query_id", "_pilot_meta", "_gen")}
    blob = json.dumps(body, sort_keys=True, separators=(",", ":"), ensure_ascii=False)
    return hashlib.sha256(blob.encode("utf-8")).hexdigest()


def pad_spatial(q: dict, pad: float) -> dict:
    sp = q.get("spatial") or {}
    if not sp:
        return {}
    return {
        "min_x": sp["min_x"] - pad,
        "min_y": sp["min_y"] - pad,
        "max_x": sp["max_x"] + pad,
        "max_y": sp["max_y"] + pad,
        "relation": sp.get("relation", "INTERSECTS"),
        "boundary": sp.get("boundary", "INCLUDED"),
    }


def expand_temporal(q: dict, mult: float) -> Optional[dict]:
    t = q.get("temporal")
    if not t:
        return None
    start, end = int(t["start_ms"]), int(t["end_ms"])
    dur = end - start
    if dur <= 0:
        return None
    new_dur = int(dur * mult)
    mid = (start + end) // 2
    return {"start_ms": mid - new_dur // 2, "end_ms": mid + (new_dur - new_dur // 2)}


def write_json(path: Path, obj: Any) -> bytes:
    data = (json.dumps(obj, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
    path.write_bytes(data)
    return data


def blocked_fps() -> Tuple[Set[str], Dict[str, int]]:
    fps: Set[str] = set()
    counts: Dict[str, int] = {}
    for path in BLOCKED:
        n = 0
        if not path.is_file():
            counts[path.name] = 0
            continue
        root = load(path)
        queries = root.get("queries") or []
        if not queries and "candidates" in root:
            queries = [(c.get("query") or {}) for c in root.get("candidates") or []]
        for q in queries:
            if q:
                fps.add(query_fp(q))
                n += 1
        counts[path.name] = n
    return fps, counts


def main() -> int:
    for p in (OUT_CAND, OUT_WL, OUT_PROV, WL / f"{OUT_PREFIX}.oracle.json"):
        if p.exists():
            raise SystemExit(f"refusing overwrite: {p}")

    adv = load(ADV2)
    by = {q["query_id"]: q for q in adv["queries"]}
    blocked, blocked_counts = blocked_fps()

    templates = [
        "topk_st_3",
        "topk_st_2",
        "a2_topk_dtw_wide",
        "a2_topk_frechet_wide",
        "a2_tz_hard_d",
        "a2_tz_hard_a",
        "a2_tzh_g",
        "st_ll_1",
        "h_st_4",
        "a2_topk_dtw_k7",
    ]
    for tid in templates:
        if tid not in by:
            raise SystemExit(f"missing template {tid}")

    specs: List[Tuple[str, str, str, dict]] = []
    # (new_id, template_id, category, mutations described below via builders)

    def add_spec(qid: str, tmpl: str, category: str, reason: str,
                 temporal_mult: Optional[float] = None,
                 spatial_pad: Optional[float] = None,
                 k: Optional[int] = None,
                 metric: Optional[str] = None) -> None:
        specs.append((qid, tmpl, category, {
            "reason": reason,
            "temporal_mult": temporal_mult,
            "spatial_pad": spatial_pad,
            "k": k,
            "metric": metric,
        }))

    # Wider temporal × larger spatial on TZ / TZH-like ID lists
    add_spec("wj_tz_wide2x_pad3k", "a2_tz_hard_d", "wide_tz",
             "2x temporal duration + 3000m spatial pad on known TZ-hard template",
             temporal_mult=2.0, spatial_pad=3000.0)
    add_spec("wj_tz_wide4x_pad5k", "a2_tz_hard_d", "wide_tz",
             "4x temporal duration + 5000m spatial pad",
             temporal_mult=4.0, spatial_pad=5000.0)
    add_spec("wj_tz_a_wide3x_pad4k", "a2_tz_hard_a", "wide_tz",
             "3x temporal + 4000m pad on second TZ-hard template",
             temporal_mult=3.0, spatial_pad=4000.0)
    add_spec("wj_stll_wide2x_pad6k", "st_ll_1", "wide_tz",
             "2x long ST window + 6000m pad",
             temporal_mult=2.0, spatial_pad=6000.0)
    add_spec("wj_hst4_wide3x_pad5k", "h_st_4", "wide_tz",
             "3x h_st_4 window + 5000m pad",
             temporal_mult=3.0, spatial_pad=5000.0)
    add_spec("wj_tzhg_wide2x_pad4k", "a2_tzh_g", "wide_tzh",
             "2x TZH-family window + 4000m pad",
             temporal_mult=2.0, spatial_pad=4000.0)

    # Joint ST + Top-K + similarity: wider / higher-k / metric swap
    add_spec("wj_topk_dtw_k10_pad2k", "topk_st_3", "joint_topk_st",
             "ST Top-K DTW k=10 + 2000m pad (joint predicates retained)",
             spatial_pad=2000.0, k=10, metric="DTW")
    add_spec("wj_topk_dtw_k15_pad4k", "topk_st_3", "joint_topk_st",
             "ST Top-K DTW k=15 + 4000m pad",
             spatial_pad=4000.0, k=15, metric="DTW")
    add_spec("wj_topk_dtw_k20_wide2x", "a2_topk_dtw_wide", "joint_topk_st",
             "existing wide Top-K further 2x temporal + k=20",
             temporal_mult=2.0, k=20, metric="DTW")
    add_spec("wj_topk_dtw_wide3x_pad5k", "a2_topk_dtw_wide", "joint_topk_st",
             "3x temporal + 5000m pad on a2_topk_dtw_wide",
             temporal_mult=3.0, spatial_pad=5000.0, k=10, metric="DTW")
    add_spec("wj_topk_frechet_k12_pad3k", "a2_topk_frechet_wide", "joint_topk_st",
             "Frechet ST Top-K k=12 + 3000m pad",
             spatial_pad=3000.0, k=12, metric="FRECHET")
    add_spec("wj_topk_frechet_wide2x_k15", "a2_topk_frechet_wide", "joint_topk_st",
             "Frechet 2x temporal + k=15",
             temporal_mult=2.0, k=15, metric="FRECHET")
    add_spec("wj_topk_haus_k10_wide2x", "topk_st_2", "joint_topk_st",
             "Hausdorff joint on topk_st_2 base: 2x window k=10",
             temporal_mult=2.0, k=10, metric="HAUSDORFF")
    add_spec("wj_topk_haus_k15_pad5k", "topk_st_2", "joint_topk_st",
             "Hausdorff k=15 + 5000m pad",
             spatial_pad=5000.0, k=15, metric="HAUSDORFF")
    add_spec("wj_topk_dtw_k7_wide4x", "a2_topk_dtw_k7", "joint_topk_st",
             "k7 template with 4x temporal expansion",
             temporal_mult=4.0, k=10, metric="DTW")
    add_spec("wj_topk_dtw_pad8k_k12", "topk_st_3", "joint_topk_st",
             "very large spatial pad 8000m + k=12",
             spatial_pad=8000.0, k=12, metric="DTW")

    # Extra aggressive joint: wide TZ-hard + attach Top-K similarity (if base has no sim, inject)
    add_spec("wj_joint_tzhard_topk_k8", "a2_tz_hard_d", "joint_tz_topk",
             "TZ-hard base + inject TOP_K DTW k=8 + 2000m pad (multi-predicate joint)",
             spatial_pad=2000.0, k=8, metric="DTW")
    add_spec("wj_joint_tzhard_topk_k12_wide2x", "a2_tz_hard_a", "joint_tz_topk",
             "TZ-hard_a + TOP_K DTW k=12 + 2x temporal + 3000m pad",
             temporal_mult=2.0, spatial_pad=3000.0, k=12, metric="DTW")

    selected: List[dict] = []
    excluded: List[dict] = []
    seen: Set[str] = set()
    ref_tid = 41360  # advantage_v2 anchor tid

    for qid, tmpl, category, mut in specs:
        src = by[tmpl]
        q = copy.deepcopy(src)
        q["query_id"] = qid
        if mut.get("temporal_mult") is not None:
            nt = expand_temporal(q, float(mut["temporal_mult"]))
            if nt is None:
                excluded.append({"query_id": qid, "reason": "no_temporal_to_expand"})
                continue
            q["temporal"] = nt
        if mut.get("spatial_pad") is not None:
            q["spatial"] = pad_spatial(q, float(mut["spatial_pad"]))
        if mut.get("k") is not None or mut.get("metric") is not None:
            # Ensure joint Top-K + similarity
            metric = mut.get("metric") or ((q.get("similarity") or {}).get("metric") or "DTW")
            k = mut.get("k")
            if k is None:
                k = (q.get("result") or {}).get("k") or 5
            q["result"] = {
                "mode": "TOP_K",
                "k": int(k),
                "tie_breaker": "TID_ASC",
            }
            q["similarity"] = {
                "metric": metric,
                "reference_tid": int((q.get("similarity") or {}).get("reference_tid") or ref_tid),
                "scope": "FULL_TRAJECTORY",
                "exclude_reference": True,
                "local_distance": "EUCLIDEAN",
                "normalization": "NONE",
            }
        q["snapshot"] = {
            "manifest_id": "tdrive_v1_ready",
            "semantics_version": "point_dtw_v1",
        }
        fp = query_fp(q)
        if fp in blocked or fp in seen:
            excluded.append({
                "query_id": qid,
                "reason": "fingerprint_blocked_or_dup",
                "template": tmpl,
            })
            continue
        seen.add(fp)
        selected.append({
            "query": q,
            "category": category,
            "reason": mut["reason"],
            "source_query_id": tmpl,
            "fingerprint": fp,
            "expansion": {
                "temporal_mult": mut.get("temporal_mult"),
                "spatial_pad_m": mut.get("spatial_pad"),
                "k": mut.get("k"),
                "metric": mut.get("metric"),
            },
        })

    if len(selected) < 8:
        raise SystemExit(f"too few candidates survived: {len(selected)}")

    queries = [x["query"] for x in selected]
    cat_counts = {
        c: sum(1 for x in selected if x["category"] == c)
        for c in sorted({x["category"] for x in selected})
    }
    workload = {
        "manifest_id": "tdrive_v1_ready",
        "semantics_version": "point_dtw_v1",
        "workload_id": OUT_PREFIX,
        "evaluation_role": "pre_kart_wide_joint_opportunity_discovery",
        "seed": SEED,
        "generator": GENERATOR,
        "note": "Wider windows and joint ST+TopK+similarity expansions; discovery pool only.",
        "queries": queries,
    }
    cand_doc = {
        "seed": SEED,
        "generator": GENERATOR,
        "manifest_id": "tdrive_v1_ready",
        "semantics_version": "point_dtw_v1",
        "n_candidates": len(selected),
        "category_counts": cat_counts,
        "excluded_locked_query_counts": blocked_counts,
        "excluded_rows": excluded,
        "selection_rule": "fixed expansion grid; no KART outcomes; holdout/advantage fps excluded",
        "candidates": selected,
    }
    provenance = {
        "evaluation_role": "wide_joint_opportunity_discovery",
        "seed": SEED,
        "generator": GENERATOR,
        "manifest_id": "tdrive_v1_ready",
        "semantics_version": "point_dtw_v1",
        "candidate_count": len(selected),
        "category_counts": cat_counts,
        "blocked_files": blocked_counts,
        "source_sha256": {p.name: sha(p) for p in [ADV2] if p.is_file()},
        "note": "Oracle must be filled independently via FullScan before census exec labels.",
    }

    enc_c = write_json(OUT_CAND, cand_doc)
    enc_w = write_json(OUT_WL, workload)
    provenance["sha256"] = {
        OUT_CAND.name: hashlib.sha256(enc_c).hexdigest(),
        OUT_WL.name: hashlib.sha256(enc_w).hexdigest(),
    }
    write_json(OUT_PROV, provenance)
    print(json.dumps({
        "candidate_count": len(selected),
        "category_counts": cat_counts,
        "excluded": len(excluded),
        "workload": str(OUT_WL.relative_to(ROOT)),
        "candidates": str(OUT_CAND.relative_to(ROOT)),
    }, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
