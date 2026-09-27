#!/usr/bin/env python3
"""
Generate bound_ir_holdout_v2.json — group-disjoint holdout (fail-closed).

Differences from v1:
  - Each catalog.group is assigned to exactly one split; all variants stay there.
  - Distinct source templates preferred across train/val/test when available.
  - Cross-split group / normalized-IR hash collisions abort generation.
  - Honest role: diagnostic_generalization_stress (templates still derived from
    bound_ir_v1 families; not a brand-new unused-parameter confirmatory set).

Does NOT run FullScan / does not overwrite v1.
"""
from __future__ import annotations

import copy
import hashlib
import json
import os
import sys
from pathlib import Path

ROOT = Path(os.environ.get("KART_HOLDOUT_ROOT") or Path(__file__).resolve().parents[2]).resolve()
sys.path.insert(0, str(Path(__file__).resolve().parent))

from holdout_integrity import assert_holdout_integrity, summarize_splits  # noqa: E402

BOUND = ROOT / "experiments/workloads/bound_ir_v1.json"
OUT = ROOT / "experiments/workloads/bound_ir_holdout_v2.json"
META = ROOT / "experiments/workloads/bound_ir_holdout_v2.provenance.json"

SEED = 20260926
VERSION = "v2"
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
    if META.is_file() and load(META).get("oracle_status") == "frozen":
        raise SystemExit("refuse to regenerate frozen holdout v2; create a new version")
    if any((ROOT / f"experiments/workloads/bound_ir_holdout_v2_{split}.oracle.json").exists()
           for split in ("train", "val", "test")):
        raise SystemExit("refuse to regenerate holdout v2 with existing split oracles")
    src = load(BOUND)
    by = {q["query_id"]: q for q in src["queries"]}
    anchor = src["anchor"]
    queries: list = []
    catalog: dict = {}
    splits = {"train": [], "val": [], "test": []}

    def add(q, family, layer, split, group, **extra):
        queries.append(q)
        catalog[q["query_id"]] = {
            "family": family,
            "layer": layer,
            "split": split,
            "group": group,
            **extra,
        }
        splits[split].append(q["query_id"])

    # --- TRAIN: entire groups (tune only here) ---
    t1 = by["t_small_1"]
    add(clone(t1, "h2_t_easy_a"), "T", "easy_single_index", "train", "h2_t_easy")
    add(
        clone(
            t1,
            "h2_t_easy_b",
            temporal={
                "start_ms": t1["temporal"]["start_ms"] + HOUR,
                "end_ms": t1["temporal"]["end_ms"] + HOUR,
            },
        ),
        "T",
        "easy_single_index",
        "train",
        "h2_t_easy",
    )
    if "st_ss_1" in by:
        st = by["st_ss_1"]
        add(clone(st, "h2_tz_mid_a"), "TZ", "multi_index", "train", "h2_tz_mid")
        add(
            clone(
                st,
                "h2_tz_mid_b",
                temporal={
                    "start_ms": st["temporal"]["start_ms"] + BUCKET,
                    "end_ms": st["temporal"]["end_ms"] + BUCKET,
                },
            ),
            "TZ",
            "multi_index",
            "train",
            "h2_tz_mid",
        )
    if "h_st_1" in by:
        add(clone(by["h_st_1"], "h2_tzh_a"), "TZH", "multi_index_uncertain", "train", "h2_tzh")
        add(clone(by["h_st_1"], "h2_tzh_b"), "TZH", "multi_index_uncertain", "train", "h2_tzh")
    if "topk_st_1" in by:
        q = clone(by["topk_st_1"], "h2_topk_dtw_k3")
        q["result"]["k"] = 3
        add(q, "TOPK", "topk_metric", "train", "h2_topk_dtw", k=3)
        q5 = clone(by["topk_st_1"], "h2_topk_dtw_k5")
        q5["result"]["k"] = 5
        add(q5, "TOPK", "topk_metric", "train", "h2_topk_dtw", k=5)
    if "h_eq_1" in by:
        add(clone(by["h_eq_1"], "h2_h_easy_a"), "H", "easy_hash", "train", "h2_h_easy")

    # --- VAL: disjoint groups (select thresholds once) ---
    if "t_large_1" in by:
        tl = by["t_large_1"]
        add(clone(tl, "h2_t_hard_a"), "T", "hard_large_window", "val", "h2_t_hard")
        add(
            clone(
                tl,
                "h2_t_hard_b",
                temporal={
                    "start_ms": tl["temporal"]["start_ms"] + BUCKET,
                    "end_ms": tl["temporal"]["end_ms"] + BUCKET,
                },
            ),
            "T",
            "hard_large_window",
            "val",
            "h2_t_hard",
        )
    if "h_t_small" in by:
        add(clone(by["h_t_small"], "h2_th_mid_a"), "TH", "multi_index", "val", "h2_th_mid")
    if "t_boundary_end_1" in by:
        add(
            clone(by["t_boundary_end_1"], "h2_boundary_a"),
            "T",
            "empty_boundary",
            "val",
            "h2_boundary",
        )
    if "topk_st_frechet_1" in by:
        add(
            clone(by["topk_st_frechet_1"], "h2_topk_frechet_a"),
            "TOPK",
            "topk_metric",
            "val",
            "h2_topk_frechet",
        )

    # --- TEST: locked groups (evaluate once; different source templates when possible) ---
    if "s_small_2" in by:
        add(clone(by["s_small_2"], "h2_z_easy_a"), "Z", "easy_single_index", "test", "h2_z_easy")
        add(clone(by["s_small_2"], "h2_z_easy_b"), "Z", "easy_single_index", "test", "h2_z_easy")
    elif "s_small_1" in by:
        add(clone(by["s_small_1"], "h2_z_easy_a"), "Z", "easy_single_index", "test", "h2_z_easy")
    if "st_ll_1" in by:
        add(clone(by["st_ll_1"], "h2_tz_hard_a"), "TZ", "hard_large_intersect", "test", "h2_tz_hard")
        add(clone(by["st_ll_1"], "h2_tz_hard_b"), "TZ", "hard_large_intersect", "test", "h2_tz_hard")
    if "empty_far_1" in by:
        add(clone(by["empty_far_1"], "h2_empty_a"), "T", "empty_boundary", "test", "h2_empty")
    if "topk_st_hausdorff_1" in by:
        add(
            clone(by["topk_st_hausdorff_1"], "h2_topk_hausdorff_a"),
            "TOPK",
            "topk_metric",
            "test",
            "h2_topk_hausdorff",
        )
    if "h_st_2" in by:
        add(
            clone(by["h_st_2"], "h2_tzh_test_a"),
            "TZH",
            "multi_index_uncertain",
            "test",
            "h2_tzh_test",
        )

    assert_holdout_integrity(queries, catalog)
    summary = summarize_splits(catalog)

    wl = {
        "manifest_id": SNAPSHOT,
        "semantics_version": SEMANTICS,
        "holdout": True,
        "holdout_version": VERSION,
        "seed": SEED,
        "anchor": anchor,
        "evaluation_role": "diagnostic_generalization_stress",
        "queries": queries,
        "catalog": catalog,
        "splits": splits,
        "hypothesis": (
            "On layers with n_safe>=2 and close/uncertain CostCards, "
            "kart-conditional-llm has non-negative paired e2e net benefit vs cbo "
            "on the locked test split only (pilot; independent sample size not argued)."
        ),
        "notes": [
            "v2 is group-disjoint: each catalog.group belongs to exactly one split",
            "Templates still derive from bound_ir_v1 families — not unused-parameter confirmatory",
            "bound_ir_holdout_v1 remains diagnostic-only (cross-split group leakage)",
            "Train may tune KART_COND_*; val selects once; test evaluates once after freeze",
        ],
    }

    blob = json.dumps(wl, indent=2, ensure_ascii=False) + "\n"
    OUT.write_text(blob, encoding="utf-8")
    sha = hashlib.sha256(blob.encode("utf-8")).hexdigest()
    prov = {
        "path": str(OUT.relative_to(ROOT)).replace("\\", "/"),
        "holdout_version": VERSION,
        "seed": SEED,
        "snapshot": SNAPSHOT,
        "n_queries": len(queries),
        "splits": summary["n_by_split"],
        "groups_by_split": summary["groups_by_split"],
        "sha256": sha,
        "oracle_status": "pending_fullscan_fill",
        "evaluation_role": "diagnostic_generalization_stress",
        "generator": "experiments/adapters/generate_holdout_workload_v2.py",
        "integrity": "holdout_integrity.assert_holdout_integrity",
        "supersedes_leakage_in": "experiments/workloads/bound_ir_holdout_v1.json",
        "split_script": "experiments/adapters/split_holdout_workloads.py",
        "oracle_fill_script": "scripts/fill-holdout-oracle.sh",
        "notes": [
            "Physical split via split_holdout_workloads.py --version v2",
            "FullScan oracle SHA256 only after fill-holdout-oracle.sh --version v2",
        ],
    }
    META.write_text(json.dumps(prov, indent=2) + "\n", encoding="utf-8")
    print("wrote", OUT, "n=", len(queries), "sha256=", sha)
    print("splits", summary["n_by_split"])
    print("groups", summary["groups_by_split"])


if __name__ == "__main__":
    main()
