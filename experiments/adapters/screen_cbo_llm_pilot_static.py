#!/usr/bin/env python3
"""Build screen_log when live HBase CBO/Bao screen is unavailable.

Uses constructability + category rules only. Marks keep with pending_live_confirm.
Does NOT use cbo-llm-proposal outcomes.
"""
from __future__ import annotations

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CAND = ROOT / "experiments/workloads/bound_ir_cbo_llm_pilot_v1.candidates.json"
OUT = ROOT / "experiments/workloads/bound_ir_cbo_llm_pilot_v1.screen_log.json"


def constructable_ids(q: dict):
    has_t = q.get("temporal") is not None
    has_z = q.get("spatial") is not None
    preds = q.get("predicates") or []
    has_h = any(
        isinstance(p, dict) and p.get("field") == "vehicle_id" and p.get("op") == "EQ"
        for p in preds
    )
    out = []
    if has_t:
        out.append("P_T")
    if has_z:
        out.append("P_Z")
    if has_h:
        out.append("P_H")
    if has_t and has_z:
        out.append("P_TZ")
    if has_t and has_h:
        out.append("P_TH")
    if has_z and has_h:
        out.append("P_ZH")
    if has_t and has_z and has_h:
        out.append("P_TZH")
    out.append("P_FULL")
    if has_t and has_z:
        out.append("P_TZ_SORT_MERGE")
    if has_t and has_h:
        out.append("P_TH_SORT_MERGE")
    if has_z and has_h:
        out.append("P_ZH_SORT_MERGE")
    if has_t and has_z and has_h:
        out.append("P_TZH_SORT_MERGE")
    return out


def main() -> None:
    cand = json.loads(CAND.read_text(encoding="utf-8"))
    rows = []
    for c in cand["candidates"]:
        q = c["query"]
        cat = c["category"]
        constructable = constructable_ids(q)
        novel_sort = [p for p in constructable if p.endswith("SORT_MERGE")]
        has_index = any(p != "P_FULL" for p in constructable)
        keep = False
        reason = ""
        labels = ["static_screen_no_hbase"]
        if cat == "cbo_candidate_miss" and novel_sort:
            keep = True
            reason = "static: SORT_MERGE constructable (live CBO safe-set confirm pending; HBase down)"
            labels += ["candidate_miss", "pending_live_cbo_confirm"]
        elif cat == "bao_tzh_full_risk" and has_index and "P_TZH" in constructable:
            # Do NOT keep without live Bao selecting P_FULL (spec: no standard lowering).
            keep = False
            reason = "static: TZH constructable but live Bao P_FULL not confirmed (HBase down); not kept"
            labels += ["bao_full_tzh_candidate_only", "pending_live_bao_confirm"]
        elif cat == "normal_control" and "P_TZ" in constructable:
            keep = True
            reason = "static: TZ control with index plan constructable (live CBO confirm pending)"
            labels += ["control", "pending_live_cbo_confirm"]
        else:
            reason = "static: category gates not met"
        rows.append({
            "query_id": q["query_id"],
            "category": cat,
            "reason_in": c.get("reason"),
            "query": q,
            "cbo_plan_id": None,
            "bao_plan_id": None,
            "constructable": constructable,
            "novel_vs_cbo_selected": novel_sort,
            "keep": keep,
            "keep_reason": reason,
            "labels": labels,
        })
    out = {
        "seed": cand.get("seed"),
        "plan_jsonl": None,
        "hbase_available": False,
        "screen_mode": "static_constructability_fallback",
        "n_rows": len(rows),
        "n_keep": sum(1 for r in rows if r["keep"]),
        "rows": rows,
        "note": "Live scripts/screen-cbo-llm-pilot.sh must be re-run when ZooKeeper/HBase are up.",
    }
    OUT.write_text(json.dumps(out, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"wrote {OUT} keep={out['n_keep']}/{out['n_rows']}")


if __name__ == "__main__":
    main()
