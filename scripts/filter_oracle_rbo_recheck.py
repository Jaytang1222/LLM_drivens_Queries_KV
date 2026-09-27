#!/usr/bin/env python3
"""Filter bound_ir_v1 to previously failing RBO queries for a fast recheck."""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
FAILS = {
    "empty_far_3", "h_st_1", "h_st_2", "h_st_3", "h_st_4",
    "st_grid_1", "st_grid_2", "st_grid_3", "st_ll_1", "st_ll_2",
    "st_ls_1", "st_ls_2", "st_mid_1", "st_sl_1", "st_sl_2",
    "st_ss_1", "st_ss_2", "t_large_3", "topk_st_1", "topk_st_2",
    "topk_st_2_k1", "topk_st_3", "topk_st_frechet_1", "topk_st_frechet_k5",
    "topk_st_hausdorff_1", "topk_st_hausdorff_k1", "topk_st_k1",
    "topk_st_k10", "topk_st_k5",
}
src = ROOT / "experiments/workloads/bound_ir_v1.json"
dst = ROOT / "experiments/workloads/_oracle_rbo_recheck.json"
wl = json.loads(src.read_text(encoding="utf-8"))
if isinstance(wl, list):
    qs = wl
elif isinstance(wl, dict) and "queries" in wl:
    qs = wl["queries"]
else:
    qs = None
    for v in wl.values() if isinstance(wl, dict) else []:
        if isinstance(v, list) and v and isinstance(v[0], dict) and (
                "query_id" in v[0] or "id" in v[0]):
            qs = v
            break
    if qs is None:
        raise SystemExit("unrecognized workload shape: " + str(type(wl)))

out = []
for q in qs:
    qid = q.get("query_id") or q.get("id")
    if qid in FAILS:
        out.append(q)
missing = FAILS - {(q.get("query_id") or q.get("id")) for q in out}
if missing:
    raise SystemExit("missing queries: " + ", ".join(sorted(missing)))
if isinstance(wl, list):
    payload = out
elif isinstance(wl, dict) and "queries" in wl:
    payload = dict(wl)
    payload["queries"] = out
else:
    payload = out
dst.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
print("wrote", dst, "n=", len(out))
