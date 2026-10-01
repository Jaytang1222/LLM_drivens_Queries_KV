#!/usr/bin/env python3
"""Summarize AIS E2E hybrid vs CBO with separate t_plan / t_exec / net_gain."""
from __future__ import annotations

import argparse
import json
from glob import glob
from pathlib import Path
from statistics import median


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--results-glob", required=True)
    args = ap.parse_args()
    files = sorted(glob(args.results_glob))
    if not files:
        raise SystemExit(f"no files for {args.results_glob}")
    path = Path(files[-1])
    rows = [json.loads(l) for l in path.read_text(encoding="utf-8").splitlines() if l.strip()]
    cbo = {r["query_id"]: r for r in rows if r.get("arm") == "cbo"}
    hyb = {r["query_id"]: r for r in rows if str(r.get("arm", "")).startswith("cbo-llm-proposal")}
    print("jsonl=", path)
    print(f"{'query':24} {'t_plan_c':>8} {'t_plan_h':>8} {'d_plan':>8} {'t_exec_c':>8} {'t_exec_h':>8} {'d_exec':>8} {'net':>8} {'fb':12}")
    nets, dplans, dexecs = [], [], []
    timeouts = 0
    for qid in sorted(cbo):
        c, h = cbo[qid], hyb[qid]
        dp = int(h["t_plan_ms"]) - int(c["t_plan_ms"])
        de = int(h["t_exec_ms"]) - int(c["t_exec_ms"])
        net = (-de) - dp  # exec saving minus extra plan
        dplans.append(dp)
        dexecs.append(de)
        nets.append(net)
        fb = h.get("fallback_reason")
        if fb == "llm_timeout":
            timeouts += 1
        print(
            f"{qid:24} {c.get('t_plan_ms')!s:>8} {h.get('t_plan_ms')!s:>8} {dp:>8} "
            f"{c.get('t_exec_ms')!s:>8} {h.get('t_exec_ms')!s:>8} {de:>8} {net:>8} {str(fb):12}"
        )
    print("delta_t_plan median/mean", median(dplans), round(sum(dplans) / len(dplans), 1))
    print("delta_t_exec median/mean", median(dexecs), round(sum(dexecs) / len(dexecs), 1))
    print("net_gain median/mean", median(nets), round(sum(nets) / len(nets), 1),
          "(positive => LLM exec advantage covers plan cost)")
    print("llm_timeout_count", timeouts)
    print("positive_net_queries", sum(1 for n in nets if n > 0), "/", len(nets))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
