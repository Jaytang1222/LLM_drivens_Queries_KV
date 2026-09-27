#!/usr/bin/env python3
"""
Freeze complexity holdout BoundIR set BEFORE looking at new test results.

- Seed, snapshot, and split are fixed here.
- Old bound_ir_v1 (65) is the observed diagnostic/regression set.
- This holdout is for confirming conditional-LLM hypotheses on locked test only.

Oracle answers are filled later by FullScan (scripts/oracle-fill-holdout.sh) and
SHA256-frozen; never edit answers by hand after freeze.
"""
from __future__ import annotations

import copy
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BOUND = ROOT / "experiments/workloads/bound_ir_v1.json"
OUT = ROOT / "experiments/workloads/bound_ir_holdout_v1.json"
META = ROOT / "experiments/workloads/bound_ir_holdout_v1.provenance.json"

SEED = 20260926
SNAPSHOT = "tdrive_v1_ready"
SEMANTICS = "point_dtw_v1"
HOUR = 3_600_000
BUCKET = 600_000


def load(p: Path) -> dict:
    return json.loads(p.read_text(encoding="utf-8"))


def clone(q: dict, new_id: str, **mut) -> dict:
    out = copy.deepcopy(q)
    out["query_id"] = new_id
    for k, v in mut.items():
        if k == "temporal" and isinstance(v, dict) and out.get("temporal"):
            out["temporal"].update(v)
        elif k == "result" and isinstance(v, dict) and out.get("result"):
            out["result"].update(v)
        elif k == "similarity" and isinstance(v, dict):
            if out.get("similarity") is None:
                out["similarity"] = {}
            out["similarity"].update(v)
        else:
            out[k] = v
    return out


def main() -> None:
    src = load(BOUND)
    by = {q["query_id"]: q for q in src["queries"]}
    anchor = src["anchor"]
    queries = []
    catalog = {}
    splits = {"train": [], "val": [], "test": []}

    def add(q, family, layer, split, **extra):
        queries.append(q)
        catalog[q["query_id"]] = {
            "family": family,
            "layer": layer,
            "split": split,
            "group": extra.get("group", q["query_id"].rsplit("_", 1)[0]),
            **{k: v for k, v in extra.items() if k != "group"},
        }
        splits[split].append(q["query_id"])

    # --- construct layers from frozen templates (no post-hoc KART-win selection) ---
    t = by["t_small_1"]
    # easy T
    add(clone(t, "h_t_easy_1"), "T", "easy_single_index", "train", group="h_t_easy")
    add(
        clone(t, "h_t_easy_2", temporal={"start_ms": t["temporal"]["start_ms"] + HOUR,
                                         "end_ms": t["temporal"]["end_ms"] + HOUR}),
        "T", "easy_single_index", "val", group="h_t_easy",
    )
    add(
        clone(t, "h_t_easy_3", temporal={"start_ms": t["temporal"]["start_ms"] + 2 * HOUR,
                                         "end_ms": t["temporal"]["end_ms"] + 2 * HOUR}),
        "T", "easy_single_index", "test", group="h_t_easy",
    )
    # hard T (large window)
    tl = by["t_large_1"]
    add(clone(tl, "h_t_hard_1"), "T", "hard_large_window", "train", group="h_t_hard")
    add(
        clone(tl, "h_t_hard_2", temporal={"start_ms": tl["temporal"]["start_ms"] + BUCKET,
                                          "end_ms": tl["temporal"]["end_ms"] + BUCKET}),
        "T", "hard_large_window", "test", group="h_t_hard",
    )

    # Z / TZ composites
    if "s_small_1" in by:
        s = by["s_small_1"]
        add(clone(s, "h_z_easy_1"), "Z", "easy_single_index", "train", group="h_z_easy")
        add(clone(s, "h_z_easy_2"), "Z", "easy_single_index", "test", group="h_z_easy")
    if "st_ss_1" in by:
        st = by["st_ss_1"]
        add(clone(st, "h_tz_mid_1"), "TZ", "multi_index", "train", group="h_tz_mid")
        add(clone(st, "h_tz_mid_2"), "TZ", "multi_index", "val", group="h_tz_mid")
        add(clone(st, "h_tz_mid_3"), "TZ", "multi_index", "test", group="h_tz_mid")
    if "st_ll_1" in by:
        stl = by["st_ll_1"]
        add(clone(stl, "h_tz_hard_1"), "TZ", "hard_large_intersect", "train", group="h_tz_hard")
        add(clone(stl, "h_tz_hard_2"), "TZ", "hard_large_intersect", "test", group="h_tz_hard")

    # H / TH / TZH-like (use available templates)
    for src_id, new_id, fam, layer, split, group in [
        ("h_eq_1", "h_h_easy_1", "H", "easy_hash", "train", "h_h_easy"),
        ("h_eq_1", "h_h_easy_2", "H", "easy_hash", "test", "h_h_easy"),
        ("h_t_small", "h_th_mid_1", "TH", "multi_index", "train", "h_th_mid"),
        ("h_t_small", "h_th_mid_2", "TH", "multi_index", "test", "h_th_mid"),
        ("h_st_1", "h_tzh_hard_1", "TZH", "multi_index_uncertain", "train", "h_tzh_hard"),
        ("h_st_1", "h_tzh_hard_2", "TZH", "multi_index_uncertain", "val", "h_tzh_hard"),
        ("h_st_1", "h_tzh_hard_3", "TZH", "multi_index_uncertain", "test", "h_tzh_hard"),
    ]:
        if src_id in by:
            add(clone(by[src_id], new_id), fam, layer, split, group=group)

    # Top-K variants
    for src_id, new_id, k, split, group in [
        ("topk_st_1", "h_topk_dtw_k3_1", 3, "train", "h_topk_dtw"),
        ("topk_st_1", "h_topk_dtw_k5_1", 5, "val", "h_topk_dtw"),
        ("topk_st_1", "h_topk_dtw_k10_1", 10, "test", "h_topk_dtw"),
        ("topk_st_frechet_1", "h_topk_frechet_1", None, "test", "h_topk_frechet"),
    ]:
        if src_id not in by:
            continue
        q = clone(by[src_id], new_id)
        if k is not None and q.get("result"):
            q["result"]["k"] = k
        add(q, "TOPK", "topk_metric", split, group=group, k=k)

    # empty / boundary (keep ugly queries)
    if "empty_far_1" in by:
        add(clone(by["empty_far_1"], "h_empty_1"), "T", "empty_boundary", "test", group="h_empty")
    if "t_boundary_end_1" in by:
        add(
            clone(by["t_boundary_end_1"], "h_boundary_1"),
            "T",
            "empty_boundary",
            "val",
            group="h_boundary",
        )

    # Mark groups: variants stay in same split already by construction above.
    wl = {
        "manifest_id": SNAPSHOT,
        "semantics_version": SEMANTICS,
        "holdout": True,
        "seed": SEED,
        "anchor": anchor,
        "queries": queries,
        "catalog": catalog,
        "splits": splits,
        "hypothesis": (
            "On layers with n_safe>=2 and close/uncertain CostCards, "
            "kart-conditional-llm has non-negative paired e2e net benefit vs cbo "
            "on the locked test split only."
        ),
        "notes": [
            "Generated before evaluating new test results",
            "Old bound_ir_v1.json remains the diagnostic/regression set (65)",
            "Train may tune KART_COND_REL_GAP; val selects once; test evaluates once",
        ],
    }

    blob = json.dumps(wl, indent=2, ensure_ascii=False) + "\n"
    OUT.write_text(blob, encoding="utf-8")
    sha = hashlib.sha256(blob.encode("utf-8")).hexdigest()
    prov = {
        "path": str(OUT.relative_to(ROOT)).replace("\\", "/"),
        "seed": SEED,
        "snapshot": SNAPSHOT,
        "n_queries": len(queries),
        "splits": {k: len(v) for k, v in splits.items()},
        "sha256": sha,
        "oracle_status": "pending_fullscan_fill",
        "generator": "experiments/adapters/generate_holdout_workload.py",
    }
    META.write_text(json.dumps(prov, indent=2) + "\n", encoding="utf-8")
    print("wrote", OUT, "n=", len(queries), "sha256=", sha)
    print("splits", prov["splits"])


if __name__ == "__main__":
    main()
