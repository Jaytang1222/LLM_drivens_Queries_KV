"""Expand compact simple keepers + leftover eligible ids into a cold pad pool."""
from __future__ import annotations

import json
from copy import deepcopy
from pathlib import Path

from admit_dual_rules import ROOT, _feats, _load, _oracle_index, is_simple_eligible, _complex_ban

KEEP_SEEDS = [
    "h_t_small", "h_s_small", "s_small_1", "s_small_2", "t_grid_1", "st_ls_1",
    "topk_s_1", "h_eq_1", "t_shift_1h", "topk_st_k10",
]


def _scale_temporal(q: dict, factor: float, new_id: str) -> dict:
    q2 = deepcopy(q)
    q2["query_id"] = new_id
    t = q2.get("temporal")
    if not t or t.get("start_ms") is None or t.get("end_ms") is None:
        return q2
    span = t["end_ms"] - t["start_ms"]
    mid = (t["start_ms"] + t["end_ms"]) // 2
    half = max(1, int(span * factor / 2))
    q2["temporal"] = dict(t)
    q2["temporal"]["start_ms"] = mid - half
    q2["temporal"]["end_ms"] = mid + half
    return q2


def _scale_spatial(q: dict, factor: float, new_id: str) -> dict:
    q2 = deepcopy(q)
    q2["query_id"] = new_id
    s = q2.get("spatial")
    if not s:
        return q2
    cx = (s["min_x"] + s["max_x"]) / 2
    cy = (s["min_y"] + s["max_y"]) / 2
    hx = (s["max_x"] - s["min_x"]) * factor / 2
    hy = (s["max_y"] - s["min_y"]) * factor / 2
    q2["spatial"] = dict(s)
    q2["spatial"]["min_x"] = cx - hx
    q2["spatial"]["max_x"] = cx + hx
    q2["spatial"]["min_y"] = cy - hy
    q2["spatial"]["max_y"] = cy + hy
    return q2


def main() -> None:
    oidx = _oracle_index(ROOT)
    qidx = {}
    for name in (
        "bound_ir_advantage_v2.json",
        "bound_ir_v1.json",
        "bound_ir_simple_mine.json",
        "bound_ir_simple_v1.json",
        "bound_ir_cmp_v5.json",
    ):
        p = ROOT / "experiments/workloads" / name
        if not p.is_file():
            continue
        for q in _load(p).get("queries") or []:
            qid = q.get("query_id")
            if qid and qid not in qidx:
                qidx[qid] = q
    cb = _complex_ban(ROOT)
    queries = []
    seen = set()
    # leftover eligible with oracle
    for qid in (
        "t_small_2", "t_mid_2", "s_mid_2", "s_pad_2", "s_pad_3", "st_ls_2",
        "empty_far_1", "empty_far_2", "empty_far_3", "empty_far_4",
    ):
        q = qidx.get(qid)
        if not q or qid not in oidx or not is_simple_eligible(qid, cb, q):
            continue
        seen.add(qid)
        queries.append(q)
    # compact variants (new ids → need oracle later; skip if no oracle)
    for seed in KEEP_SEEDS:
        q = qidx.get(seed)
        if not q:
            continue
        for fac, tag in ((1.15, "t15"), (0.85, "t85")):
            q2 = _scale_temporal(q, fac, f"{seed}__{tag}")
            if is_simple_eligible(q2["query_id"], cb, q2) and q2["query_id"] not in seen:
                # only keep if oracle exists for seed (variants need new oracle on server)
                pass
        for fac, tag in ((1.2, "s12"), (0.8, "s08")):
            if not q.get("spatial"):
                continue
            q2 = _scale_spatial(q, fac, f"{seed}__{tag}")
            if is_simple_eligible(q2["query_id"], cb, q2) and q2["query_id"] not in seen:
                pass
    # For pad pool without new oracles: only leftover ids that already have oracle.
    answers = [oidx[q["query_id"]] for q in queries]
    wl = {
        "manifest_id": "tdrive_v1_ready",
        "workload_id": "bound_ir_simple_pad",
        "evaluation_role": "simple_pad_pool",
        "semantics_version": "point_similarity_v2",
        "queries": queries,
    }
    wp = ROOT / "experiments/workloads/bound_ir_simple_pad.json"
    op = ROOT / "experiments/workloads/bound_ir_simple_pad.oracle.json"
    wp.write_text(json.dumps(wl, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    op.write_text(json.dumps({"answers": answers}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    suite = (
        "kind: compare\n"
        "id: e2e-simple-pad\n"
        "workload: experiments/workloads/bound_ir_simple_pad.json\n"
        "oracle: experiments/workloads/bound_ir_simple_pad.oracle.json\n"
        "manifest: tdrive_v1_ready\n"
        "require_oracle: true\n"
        "trials: 1\n"
        "stages:\n"
        "  - e2e\n"
        "arms:\n"
        "  - kart\n"
        "  - cbo\n"
        "  - rbo\n"
        "  - fullscan\n"
    )
    (ROOT / "experiments/suites/e2e-simple-pad.yaml").write_text(suite, encoding="utf-8")
    print(json.dumps({"n": len(queries), "ids": [q["query_id"] for q in queries]}))


if __name__ == "__main__":
    main()
