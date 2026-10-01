#!/usr/bin/env python3
"""Post-process CBO/Bao plan.jsonl into pilot screen_log with keep decisions."""
from __future__ import annotations

import argparse
import json
from collections import defaultdict
from pathlib import Path
from typing import Any, Dict, List, Set


def constructable_ids(q: dict) -> List[str]:
    """Mirror PlanBuilder.buildCandidatesWithMergeVariants (HASH_SET + SORT_MERGE)."""
    has_t = q.get("temporal") is not None
    has_z = q.get("spatial") is not None
    preds = q.get("predicates") or []
    has_h = any(
        isinstance(p, dict) and p.get("field") == "vehicle_id" and p.get("op") == "EQ"
        for p in preds
    )
    out: List[str] = []
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
    # SORT_MERGE twins
    if has_t and has_z:
        out.append("P_TZ_SORT_MERGE")
    if has_t and has_h:
        out.append("P_TH_SORT_MERGE")
    if has_z and has_h:
        out.append("P_ZH_SORT_MERGE")
    if has_t and has_z and has_h:
        out.append("P_TZH_SORT_MERGE")
    return out


def load_jsonl(p: Path) -> List[dict]:
    if not p.is_file():
        return []
    rows = []
    for line in p.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line:
            rows.append(json.loads(line))
    return rows


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--candidates", type=Path, required=True)
    ap.add_argument("--plan-jsonl", type=Path, required=True)
    ap.add_argument("--out", type=Path, required=True)
    args = ap.parse_args()

    cand = json.loads(args.candidates.read_text(encoding="utf-8"))
    by_qid_arm: Dict[str, Dict[str, dict]] = defaultdict(dict)
    for r in load_jsonl(args.plan_jsonl):
        by_qid_arm[r.get("query_id")][r.get("arm")] = r
    expected = {c["query"]["query_id"] for c in cand.get("candidates", [])}
    for qid in expected:
        missing = {"cbo", "bao", "pilot-safety-probe"} - set(by_qid_arm.get(qid, {}))
        if missing:
            raise SystemExit(f"Incomplete live screen for {qid}: missing {sorted(missing)}")

    rows_out: List[dict] = []
    for c in cand.get("candidates", []):
        q = c["query"]
        qid = q["query_id"]
        cat = c["category"]
        constructable = constructable_ids(q)
        cbo = by_qid_arm.get(qid, {}).get("cbo") or {}
        bao = by_qid_arm.get(qid, {}).get("bao") or {}
        probe = by_qid_arm.get(qid, {}).get("pilot-safety-probe") or {}
        cbo_pid = cbo.get("plan_id")
        bao_pid = bao.get("plan_id")
        if not isinstance(cbo.get("safe_plan_ids"), list) or not isinstance(probe.get("safe_plan_ids"), list):
            raise SystemExit(f"Missing full safe_plan_ids for {qid}; rebuild the jar")
        cbo_safe: Set[str] = set(cbo["safe_plan_ids"])
        validated: Set[str] = set(probe["safe_plan_ids"])
        novel = [pid for pid in constructable if pid in validated and pid not in cbo_safe and pid != "P_FULL"]
        keep = False
        keep_reason = ""
        labels: List[str] = []

        if cat == "cbo_candidate_miss":
            if novel and cbo.get("plan_ok") is True and cbo_pid:
                keep = True
                keep_reason = "validated_non_full_not_in_complete_cbo_safe_set; offline novel=" + ",".join(novel[:5])
                labels.append("candidate_miss")
                # latency opportunity needs exec — mark only miss unless screen added exec later
                labels.append("latency_unverified")
            else:
                keep_reason = "no_novel_constructable_vs_cbo_or_cbo_failed"
        elif cat == "bao_tzh_full_risk":
            if bao_pid == "P_FULL" and any(pid != "P_FULL" for pid in validated):
                keep = True
                keep_reason = "bao_selected_P_FULL_with_index_constructable"
                labels.append("bao_full_tzh")
            else:
                keep_reason = f"bao_plan={bao_pid}; not P_FULL or no index family"
        elif cat == "normal_control":
            if cbo.get("plan_ok") is True and cbo_pid and cbo_pid != "P_FULL":
                keep = True
                keep_reason = "cbo_has_index_plan_control"
                labels.append("control")
            else:
                keep_reason = f"cbo_plan={cbo_pid}"

        rows_out.append({
            "query_id": qid,
            "category": cat,
            "reason_in": c.get("reason"),
            "query": q,
            "cbo_plan_id": cbo_pid,
            "bao_plan_id": bao_pid,
            "constructable": constructable,
            "cbo_safe_plan_ids": sorted(cbo_safe),
            "validated_plan_ids": sorted(validated),
            "novel_vs_cbo_selected": novel,
            "keep": keep,
            "keep_reason": keep_reason,
            "labels": labels,
            "note": "Novel means validated by pilot-safety-probe and absent from the complete CBO safe set",
        })

    out = {
        "seed": cand.get("seed"),
        "plan_jsonl": str(args.plan_jsonl).replace("\\", "/"),
        "screen_mode": "live_full_safe_set_validated",
        "n_rows": len(rows_out),
        "n_keep": sum(1 for r in rows_out if r["keep"]),
        "rows": rows_out,
    }
    args.out.write_text(json.dumps(out, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"screen keep={out['n_keep']}/{out['n_rows']} → {args.out}")


if __name__ == "__main__":
    main()
