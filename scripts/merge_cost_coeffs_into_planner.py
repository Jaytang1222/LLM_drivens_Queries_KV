#!/usr/bin/env python3
"""Merge Java FitCostCmd / FeedbackCalibrator coeffs JSON into config/planner.yaml."""
from __future__ import annotations

import argparse
import json
import re
from pathlib import Path
from typing import Any, Dict, Optional


def merge_planner_yaml(planner: Path, named: Dict[str, Any], filter_rate: Optional[float]) -> None:
    text = planner.read_text(encoding="utf-8")
    text = re.sub(r"(?m)^(\s*calibrated:\s*)\w+", r"\g<1>true", text, count=1)
    for k, v in named.items():
        if k in ("calibrated", "model_version", "filter_pass_rate",
                 "filter_pass_samples", "filter_pass_hits"):
            continue
        pat = re.compile(rf"(?m)^(\s*{re.escape(str(k))}:\s*)[^\n]+")
        if pat.search(text):
            text = pat.sub(rf"\g<1>{v}", text, count=1)
        else:
            text = re.sub(
                r"(?m)^(\s*calibrated:\s*true\s*)$",
                rf"\1\n  {k}: {v}",
                text,
                count=1,
            )
    if "model_version:" in text:
        mv = named.get("model_version", "cost_v2_rs_sched")
        text = re.sub(
            r"(?m)^(\s*model_version:\s*)[^\n]+",
            rf"\g<1>{mv}",
            text,
            count=1,
        )
    if re.search(r"(?m)^\s*eta_cell_dtw:", text):
        text = re.sub(r"(?m)^(\s*eta_cell_dtw:\s*)[^\n]+", r"\g<1>0.0", text, count=1)
    else:
        text = re.sub(
            r"(?m)^(\s*eta_cell:\s*[^\n]+)$",
            r"\1\n  eta_cell_dtw: 0.0",
            text,
            count=1,
        )
    planner.write_text(text, encoding="utf-8")
    if filter_rate is not None:
        side = planner.parent.parent / "experiments" / "results" / "filter_pass_update.json"
        side.parent.mkdir(parents=True, exist_ok=True)
        side.write_text(
            json.dumps({"filter_pass_rate": filter_rate}, indent=2) + "\n",
            encoding="utf-8",
        )


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--coeffs", type=Path, required=True,
                    help="Java fit-cost coeffs JSON (named §13.4 keys)")
    ap.add_argument("--planner", type=Path, default=Path("config/planner.yaml"))
    args = ap.parse_args()
    named = json.loads(args.coeffs.read_text(encoding="utf-8"))
    if not isinstance(named, dict):
        raise SystemExit("coeffs JSON must be an object")
    fr = named.get("filter_pass_rate")
    merge_planner_yaml(args.planner, named, float(fr) if fr is not None else None)
    print(json.dumps({
        "merged_planner": str(args.planner),
        "coeffs": str(args.coeffs),
        "keys": sorted(k for k in named.keys() if k not in (
            "filter_pass_rate", "filter_pass_samples", "filter_pass_hits")),
    }, indent=2))


if __name__ == "__main__":
    main()
