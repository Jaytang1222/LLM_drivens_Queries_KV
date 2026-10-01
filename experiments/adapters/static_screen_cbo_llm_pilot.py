#!/usr/bin/env python3
"""Static provisional screen when HBase is unavailable (honest shortfalls)."""
from __future__ import annotations

import json
from pathlib import Path

from screen_cbo_llm_pilot import constructable_ids

ROOT = Path(__file__).resolve().parents[2]
CAND = ROOT / "experiments/workloads/bound_ir_cbo_llm_pilot_v1.candidates.json"
OUT = ROOT / "experiments/workloads/bound_ir_cbo_llm_pilot_v1.screen_log.json"


def main() -> None:
    cand = json.loads(CAND.read_text(encoding="utf-8"))
    rows = []
    for c in cand["candidates"]:
        q = c["query"]
        cat = c["category"]
        constructable = constructable_ids(q)
        novel_assumed = [p for p in constructable if p.endswith("SORT_MERGE")]
        keep = False
        keep_reason = ""
        labels = []
        if cat == "cbo_candidate_miss":
            if novel_assumed:
                # Historical adv2 E2: CBO almost never selects SORT_MERGE; treat as
                # provisional miss pending live CBO safe-set dump.
                keep = True
                keep_reason = (
                    "provisional: constructable SORT_MERGE present; "
                    "live CBO safe-set not verified (HBase down)"
                )
                labels = ["candidate_miss_provisional", "latency_unverified"]
            else:
                keep_reason = "no_sort_merge_constructable"
        elif cat == "bao_tzh_full_risk":
            # Cannot claim Bao selects P_FULL without live Bao arm.
            keep = False
            keep_reason = "deferred: requires live Bao E2 screen (HBase/ZK unavailable)"
            labels = ["bao_screen_pending"]
        elif cat == "normal_control":
            if any(p != "P_FULL" for p in constructable):
                keep = True
                keep_reason = "provisional control with index constructable; live CBO pending"
                labels = ["control_provisional"]
            else:
                keep_reason = "no_index_constructable"
        rows.append({
            "query_id": q["query_id"],
            "category": cat,
            "reason_in": c.get("reason"),
            "query": q,
            "cbo_plan_id": None,
            "bao_plan_id": None,
            "constructable": constructable,
            "novel_vs_cbo_selected": novel_assumed,
            "keep": keep,
            "keep_reason": keep_reason,
            "labels": labels,
            "note": "STATIC provisional screen — re-run scripts/screen-cbo-llm-pilot.sh when HBase is up",
        })
    out = {
        "seed": cand.get("seed"),
        "plan_jsonl": None,
        "screen_mode": "static_provisional_hbase_unavailable",
        "n_rows": len(rows),
        "n_keep": sum(1 for r in rows if r["keep"]),
        "rows": rows,
    }
    OUT.write_text(json.dumps(out, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"wrote {OUT} keep={out['n_keep']}/{out['n_rows']}")


if __name__ == "__main__":
    main()
