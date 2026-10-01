#!/usr/bin/env python3
"""Freeze AIS opportunity subset from census corrected summary for E2E hybrid test."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
WL = ROOT / "experiments" / "workloads"
SRC = WL / "bound_ir_ais_complex_v1.json"
SRC_O = WL / "bound_ir_ais_complex_v1.oracle.json"
OUT_PREFIX = "bound_ir_ais_opportunity_v1"


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--census", required=True, help="experiments/opportunity/<run-id>")
    args = ap.parse_args()
    census = Path(args.census)
    if not census.is_absolute():
        census = ROOT / census
    summary = census / "corrected" / "summary.json"
    if not summary.is_file():
        summary = census / "summary.json"
    if not summary.is_file():
        raise SystemExit(f"missing census summary under {census}")

    sj = json.loads(summary.read_text(encoding="utf-8"))
    # Prefer stable opportunities with largest median saving
    opps = []
    # corrected summary shapes vary; also scan label table from summary.md-like structures
    if isinstance(sj.get("stable_opportunities"), list):
        opps = sj["stable_opportunities"]
    elif isinstance(sj.get("queries"), list):
        for q in sj["queries"]:
            labels = q.get("labels") or q.get("label") or []
            if isinstance(labels, str):
                labels = [labels]
            if "search_miss_with_exec_gain" in labels or "selection_miss_with_exec_gain" in labels:
                opps.append(q)
    # Fallback: parse measurements for positive savings
    if not opps:
        meas = census / "measurements.jsonl"
        if meas.is_file():
            by_q = {}
            for line in meas.read_text(encoding="utf-8").splitlines():
                if not line.strip():
                    continue
                r = json.loads(line)
                if r.get("t_exec_ms") is None:
                    continue
                qid = r.get("query_id")
                by_q.setdefault(qid, []).append(r)
            # can't easily get CBO vs candidate without search.jsonl; use summary label_counts path
            pass

    # Also read summary.md table if JSON lacks stable list
    if not opps and (census / "corrected" / "summary.md").is_file():
        md = (census / "corrected" / "summary.md").read_text(encoding="utf-8")
        for line in md.splitlines():
            if "| " in line and "P_" in line and "candidate_miss" in line:
                parts = [p.strip() for p in line.strip("|").split("|")]
                if len(parts) >= 4:
                    try:
                        saving = float(parts[3])
                    except ValueError:
                        continue
                    opps.append({"query_id": parts[0], "median_saving_ms": saving, "plan": parts[1]})

    opps = sorted(opps, key=lambda x: float(x.get("median_saving_ms") or x.get("median_exec_saving_ms") or 0), reverse=True)
    pick_ids = [str(o.get("query_id")) for o in opps[:6] if o.get("query_id")]
    # Always include one control if present in source
    src = json.loads(SRC.read_text(encoding="utf-8"))
    src_o = json.loads(SRC_O.read_text(encoding="utf-8"))
    by_q = {q["query_id"]: q for q in src["queries"]}
    answers = src_o.get("answers") or []
    if isinstance(answers, list):
        by_a = {a["query_id"]: a for a in answers if isinstance(a, dict)}
    else:
        by_a = answers

    if not pick_ids:
        # No census opportunities — freeze top-k heavy queries as diagnostic set
        pick_ids = [q["query_id"] for q in src["queries"] if "topk" in q["query_id"]][:4]
        pick_ids += [q["query_id"] for q in src["queries"] if "control" in q["query_id"]][:1]
        note = "fallback_no_stable_census_opportunity; heavy topk diagnostic"
    else:
        controls = [q["query_id"] for q in src["queries"] if "control" in q["query_id"]]
        for c in controls[:1]:
            if c not in pick_ids:
                pick_ids.append(c)
        note = "census stable opportunities + control"

    pick_ids = [i for i in pick_ids if i in by_q][:8]
    if not pick_ids:
        raise SystemExit("no queries to freeze")

    out_w = {
        "manifest_id": "ais_v1_ready",
        "semantics_version": src.get("semantics_version"),
        "workload_id": OUT_PREFIX,
        "evaluation_role": "ais_opportunity_e2e_dev",
        "note": note,
        "queries": [by_q[i] for i in pick_ids],
    }
    missing = [i for i in pick_ids if i not in by_a]
    if missing:
        raise SystemExit(f"oracle missing answers for {missing}")
    out_o = {
        "manifest_id": "ais_v1_ready",
        "semantics_version": src_o.get("semantics_version"),
        "oracle_status": "frozen",
        "source": "subset_of_bound_ir_ais_complex_v1.oracle.json",
        "answers": [by_a[i] for i in pick_ids],
    }
    for path, obj in (
        (WL / f"{OUT_PREFIX}.json", out_w),
        (WL / f"{OUT_PREFIX}.oracle.json", out_o),
    ):
        path.write_text(json.dumps(obj, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    prov = {
        "census": str(census.relative_to(ROOT)),
        "query_ids": pick_ids,
        "note": note,
        "sha256": {
            f"{OUT_PREFIX}.json": hashlib.sha256((WL / f"{OUT_PREFIX}.json").read_bytes()).hexdigest(),
            f"{OUT_PREFIX}.oracle.json": hashlib.sha256((WL / f"{OUT_PREFIX}.oracle.json").read_bytes()).hexdigest(),
        },
    }
    (WL / f"{OUT_PREFIX}.provenance.json").write_text(
        json.dumps(prov, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    print("frozen", pick_ids)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
