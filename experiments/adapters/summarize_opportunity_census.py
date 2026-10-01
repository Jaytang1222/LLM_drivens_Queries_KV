#!/usr/bin/env python3
"""
Corrected CBO opportunity census summarizer (docs/kart_cbo_opportunity_followup.md §2).

Rules:
- Stable exec gain only from phase=repeat_t1/t2/t3 pairs with status=OK and ok_oracle=true.
- Coarse is screening only — never labels stable opportunity.
- fullscan_not_selected_budget_skip → fullscan_unmeasured (not whole-query censored).
- missing_oracle counted separately (not execution_error).
- Median of three paired diffs; report raw diffs, min, fail counts.
"""
from __future__ import annotations

import argparse
import json
import statistics
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple


def load_jsonl(path: Path) -> List[dict]:
    rows = []
    if not path.is_file():
        return rows
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.strip():
            rows.append(json.loads(line))
    return rows


def median(xs: List[float]) -> Optional[float]:
    if not xs:
        return None
    return float(statistics.median(xs))


def pair_repeat_savings(meas: List[dict], hybrid_extra_plan_ms: float = 900.0) -> Dict[str, dict]:
    """query_id -> analysis for non-full candidates vs CBO selected."""
    # q -> phase -> (plan_id, signature) -> row; variants must not be merged.
    by: Dict[str, Dict[str, Dict[Tuple[str, str], dict]]] = defaultdict(lambda: defaultdict(dict))
    for r in meas:
        ph = str(r.get("phase") or "")
        if not ph.startswith("repeat_t"):
            continue
        q = r.get("query_id")
        pid = r.get("plan_id")
        if not q or not pid:
            continue
        key = (pid, str(r.get("plan_signature") or ""))
        if key not in by[q][ph]:
            by[q][ph][key] = r

    out: Dict[str, dict] = {}
    for q, phases in by.items():
        cbo_key = None
        cbo_sig = None
        for ph, plans in phases.items():
            for key, r in plans.items():
                if r.get("cbo_selected"):
                    cbo_key = key
                    cbo_sig = r.get("plan_signature")
                    break
            if cbo_key:
                break
        if not cbo_key:
            out[q] = {"error": "no_cbo_selected_in_repeats"}
            continue

        cand_info = {}
        cand_keys = set()
        for ph, plans in phases.items():
            for key, r in plans.items():
                if r.get("cbo_selected"):
                    continue
                if key[0] == "P_FULL":
                    continue
                cand_keys.add(key)
                cand_info[key] = {
                    "in_cbo_safe_set": r.get("in_cbo_safe_set"),
                    "plan_signature": r.get("plan_signature"),
                }

        candidates = []
        for key in sorted(cand_keys):
            pid = key[0]
            diffs = []
            fails = 0
            raw_pairs = []
            for ph in sorted(phases.keys()):
                c = phases[ph].get(cbo_key)
                a = phases[ph].get(key)
                if (
                    c
                    and a
                    and c.get("status") == "OK"
                    and a.get("status") == "OK"
                    and c.get("ok_oracle") is True
                    and a.get("ok_oracle") is True
                    and c.get("t_exec_ms") is not None
                    and a.get("t_exec_ms") is not None
                ):
                    d = float(c["t_exec_ms"]) - float(a["t_exec_ms"])
                    diffs.append(d)
                    raw_pairs.append(
                        {
                            "phase": ph,
                            "cbo_t_exec_ms": c["t_exec_ms"],
                            "cand_t_exec_ms": a["t_exec_ms"],
                            "saving_ms": d,
                        }
                    )
                else:
                    fails += 1
            info = cand_info.get(key, {})
            miss_class = (
                "selection_miss"
                if info.get("in_cbo_safe_set")
                else "candidate_miss"
            )
            stable = len(diffs) == 3 and all(d > 0 for d in diffs)
            candidates.append(
                {
                    "plan_id": pid,
                    "plan_signature": info.get("plan_signature"),
                    "miss_class": miss_class,
                    "in_cbo_safe_set": info.get("in_cbo_safe_set"),
                    "n_paired": len(diffs),
                    "n_fail_or_missing": fails,
                    "savings_ms": diffs,
                    "saving_median_ms": median(diffs),
                    "saving_min_ms": min(diffs) if diffs else None,
                    "stable_faster": stable,
                    "pairs": raw_pairs,
                }
            )

        best_stable = None
        for c in candidates:
            if c["stable_faster"]:
                if best_stable is None or (c["saving_median_ms"] or 0) > (
                    best_stable["saving_median_ms"] or 0
                ):
                    best_stable = c

        labels = []
        has_valid_pairs = any(c["n_paired"] > 0 for c in candidates)
        has_complete_pairs = any(c["n_paired"] == 3 for c in candidates)
        oracle_missing = any(
            r.get("oracle_msg") == "missing_oracle"
            for plans in phases.values() for r in plans.values()
        )
        if best_stable:
            if best_stable["miss_class"] == "candidate_miss":
                labels.append("search_miss_with_exec_gain")
            else:
                labels.append("selection_miss_with_exec_gain")
            # provisional hybrid overhead from pilot (~900); not net gain claim
            if (best_stable["saving_median_ms"] or 0) < hybrid_extra_plan_ms:
                labels.append("candidate_fast_but_overhead_dominates")
        elif oracle_missing:
            labels.append("missing_oracle")
        elif not has_complete_pairs:
            labels.append("insufficient_repeats")
        else:
            labels.append("no_faster_safe_plan")

        out[q] = {
            "cbo_selected_plan_id": cbo_key[0],
            "cbo_plan_signature": cbo_sig,
            "candidates": candidates,
            "best_stable": best_stable,
            "has_valid_pairs": has_valid_pairs,
            "labels": labels,
        }
    return out


def summarize(run: Path) -> dict:
    meta = json.loads((run / "meta.json").read_text(encoding="utf-8")) if (run / "meta.json").is_file() else {}
    search = load_jsonl(run / "search.jsonl")
    meas = load_jsonl(run / "measurements.jsonl")

    missing_oracle = sorted(
        {
            r.get("query_id")
            for r in meas
            if r.get("oracle_msg") == "missing_oracle" and r.get("query_id")
        }
    )
    fullscan_unmeasured = sum(
        1
        for r in meas
        if r.get("censor_reason") == "fullscan_not_selected_budget_skip"
    )
    true_censor = [
        r
        for r in meas
        if r.get("status") == "censored"
        and r.get("censor_reason") != "fullscan_not_selected_budget_skip"
    ]

    overhead = float(meta.get("hybrid_extra_plan_ms_provisional") or 900)
    paired = pair_repeat_savings(meas, overhead)
    label_counts: Counter = Counter()
    stable_rows = []
    queries_out = []
    for q, info in sorted(paired.items()):
        labs = info.get("labels") or []
        for l in labs:
            label_counts[l] += 1
        if info.get("best_stable"):
            bs = info["best_stable"]
            stable_rows.append(
                {
                    "query_id": q,
                    "plan_id": bs["plan_id"],
                    "miss_class": bs["miss_class"],
                    "saving_median_ms": bs["saving_median_ms"],
                    "saving_min_ms": bs["saving_min_ms"],
                    "savings_ms": bs["savings_ms"],
                }
            )
        queries_out.append({"query_id": q, **info})

    # oracle coverage from search cbo rows
    cbo_queries = sorted(
        {
            r.get("query_id")
            for r in search
            if r.get("kind") == "cbo_search" and r.get("query_id")
        }
    )

    summary = {
        "development_diagnostic": True,
        "summary_version": "opportunity_census_corrected/v1",
        "run_id": meta.get("run_id") or run.name,
        "source_meta": {
            "cache": meta.get("cache"),
            "max_wall_minutes": meta.get("max_wall_minutes"),
            "hybrid_extra_plan_ms_provisional": meta.get(
                "hybrid_extra_plan_ms_provisional", 900
            ),
            "git": meta.get("git"),
        },
        "n_queries_in_search": len(cbo_queries),
        "n_queries_with_repeat_pairs": sum(1 for info in paired.values() if info.get("has_valid_pairs")),
        "n_missing_oracle_queries": len(missing_oracle),
        "missing_oracle_query_ids": missing_oracle,
        "n_fullscan_unmeasured_rows": fullscan_unmeasured,
        "n_true_censor_rows": len(true_censor),
        "note_fullscan": "fullscan_not_selected_budget_skip counted as fullscan_unmeasured, not whole-query censored",
        "note_stable": "stable gain requires 3 repeat phases all saving>0; coarse excluded",
        "note_net_gain": "LLM not run; provisional 900ms hybrid overhead is not measured net_gain",
        "label_counts": dict(label_counts),
        "n_stable_search_or_selection_miss": len(stable_rows),
        "stable_opportunities": sorted(
            stable_rows, key=lambda x: -(x.get("saving_median_ms") or 0)
        ),
        "queries": queries_out,
        "search_rows": len(search),
        "measurement_rows": len(meas),
    }
    return summary


def write_md(path: Path, summary: dict) -> None:
    lines = []
    lines.append(f"# Corrected opportunity census — {summary.get('run_id')}")
    lines.append("")
    lines.append("> development_diagnostic; stable labels from repeat pairs only.")
    lines.append("")
    lines.append(f"- queries in search: {summary.get('n_queries_in_search')}")
    lines.append(f"- queries with repeat pairs: {summary.get('n_queries_with_repeat_pairs')}")
    lines.append(f"- missing_oracle queries: {summary.get('n_missing_oracle_queries')}")
    lines.append(f"- fullscan_unmeasured rows: {summary.get('n_fullscan_unmeasured_rows')}")
    lines.append(f"- true censor rows: {summary.get('n_true_censor_rows')}")
    lines.append(f"- label_counts: {summary.get('label_counts')}")
    lines.append("")
    lines.append("## Stable opportunities (3/3 repeat faster)")
    lines.append("")
    lines.append("| query_id | plan | miss_class | median_saving_ms | min | raw |")
    lines.append("|---|---|---|---:|---:|---|")
    for s in summary.get("stable_opportunities") or []:
        lines.append(
            f"| {s['query_id']} | {s['plan_id']} | {s['miss_class']} | "
            f"{s['saving_median_ms']} | {s['saving_min_ms']} | {s['savings_ms']} |"
        )
    if not summary.get("stable_opportunities"):
        lines.append("| _(none)_ | | | | | |")
    lines.append("")
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--run", required=True, help="experiments/opportunity/<run-id>")
    ap.add_argument(
        "--out-dir",
        default=None,
        help="Write corrected summary here (default: <run>/corrected/)",
    )
    args = ap.parse_args()
    run = Path(args.run)
    out = Path(args.out_dir) if args.out_dir else run / "corrected"
    out.mkdir(parents=True, exist_ok=True)
    summary = summarize(run)
    (out / "summary.json").write_text(
        json.dumps(summary, indent=2, ensure_ascii=False) + "\n", encoding="utf-8"
    )
    write_md(out / "summary.md", summary)
    print(json.dumps({k: summary[k] for k in [
        "n_queries_in_search",
        "n_missing_oracle_queries",
        "n_fullscan_unmeasured_rows",
        "n_true_censor_rows",
        "label_counts",
        "n_stable_search_or_selection_miss",
        "stable_opportunities",
    ]}, indent=2, ensure_ascii=False))
    print(f"wrote {out / 'summary.json'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
