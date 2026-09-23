#!/usr/bin/env python3
"""Build compact paired answers from smoke oracle for verification doc."""
import json
from datetime import datetime, timezone, timedelta
from pathlib import Path

ROOT = Path("/mnt/f/Projects/LLM_KV")
# Prefer Windows tree; fall back to WSL workspace
for cand in [ROOT, Path("/home/jaytang/projects/llm-kv")]:
    if (cand / "experiments/workloads/tdrive_smoke.oracle.json").exists():
        ROOT = cand
        break

CST = timezone(timedelta(hours=8))

def fmt(ms):
    return datetime.fromtimestamp(ms / 1000.0, tz=CST).isoformat()

oracle = json.load(open(ROOT / "experiments/workloads/tdrive_smoke.oracle.json"))
wl = json.load(open(ROOT / "experiments/workloads/tdrive_smoke.json"))
report = json.load(open(ROOT / "experiments/results/tdrive_smoke_report.json"))
by = {a["query_id"]: a for a in oracle["answers"]}
qmap = {q["query_id"]: q for q in wl["queries"]}
rep = {r["query_id"]: r for r in report["results"]}

want = ["st_ss_1", "st_ss_2", "h_t_small", "h_st_1", "topk_st_1", "topk_st_2", "topk_st_3", "topk_s_1"]
out = {"root": str(ROOT), "anchor": wl["anchor"], "manifest_id": wl["manifest_id"], "items": []}
for qid in want:
    a, q, r = by[qid], qmap[qid], rep[qid]
    item = {
        "query_id": qid,
        "pass": r["pass"],
        "oracle_ids": a["trajectory_ids"],
        "top_k": a.get("top_k"),
        "count": len(a["trajectory_ids"]),
        "temporal": None,
        "spatial": None,
        "predicates": q.get("predicates") or [],
        "similarity": q.get("similarity"),
        "result": q.get("result"),
        "bound_ir": q,
    }
    if q.get("temporal"):
        item["temporal"] = {
            "start_ms": q["temporal"]["start_ms"],
            "end_ms": q["temporal"]["end_ms"],
            "start_cst": fmt(q["temporal"]["start_ms"]),
            "end_cst": fmt(q["temporal"]["end_ms"]),
        }
    if q.get("spatial"):
        s = q["spatial"]
        item["spatial"] = {k: s[k] for k in ("min_x", "min_y", "max_x", "max_y", "relation") if k in s}
    out["items"].append(item)
    print(qid, "n=", item["count"], "ids=", item["oracle_ids"][:5], "top_k=", item["top_k"])

path = ROOT / "experiments/results/verification_tdrive_pairs.json"
# Always also write to Windows path if different
targets = {path}
targets.add(Path("/mnt/f/Projects/LLM_KV/experiments/results/verification_tdrive_pairs.json"))
for p in targets:
    p.parent.mkdir(parents=True, exist_ok=True)
    json.dump(out, open(p, "w"), indent=2, ensure_ascii=False)
    print("wrote", p)
