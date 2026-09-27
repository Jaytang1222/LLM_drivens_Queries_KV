#!/usr/bin/env python3
"""
Offline measure n_safe / CostCard relative gaps on holdout train+val BoundIR
for conditional LLM freeze (no test split tuning).

Uses Java SharedCandidatePool via plan-only suite is heavy; this script instead
invokes `kart` BenchSuite with --limit if available, OR documents that freeze
must use measured stats from a small plan-only run.

Prefer: run with HBase jar present:
  ./scripts/kart.sh run bench-suite --suite experiments/suites/plan-shared-pool.yaml \\
    --workload experiments/workloads/bound_ir_holdout_v2_train.json \\
    --arm pool-cbo --trials 1 --plan-only ...

This helper computes trigger statistics from a plan.jsonl produced that way.
"""
from __future__ import annotations

import argparse
import json
import statistics
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple


def _rel_gap(cards: List[dict]) -> Optional[float]:
    costs = []
    for c in cards or []:
        if c is None:
            continue
        ms = c.get("estimated_ms")
        if ms is None:
            continue
        costs.append(float(ms))
    if len(costs) < 2:
        return None
    costs.sort()
    best, second = costs[0], costs[1]
    if best <= 0:
        return None
    return (second - best) / best


def measure_jsonl(path: Path) -> Dict[str, Any]:
    gaps: List[float] = []
    n_safe_list: List[int] = []
    high_unc = 0
    rows = 0
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line:
            continue
        row = json.loads(line)
        if row.get("arm") not in ("pool-cbo", "cbo", "kart-conditional-llm"):
            continue
        rows += 1
        n_safe = row.get("n_safe")
        if n_safe is None and row.get("n_cost_cards") is not None:
            n_safe = row.get("n_cost_cards")
        if isinstance(n_safe, int):
            n_safe_list.append(n_safe)
        cat = row.get("shared_pool_catalog") or {}
        cards = cat.get("cards") if isinstance(cat, dict) else None
        if not cards and isinstance(row.get("cost_cards"), list):
            cards = row["cost_cards"]
        g = _rel_gap(cards or [])
        if g is not None:
            gaps.append(g)
        for c in cards or []:
            unc = c.get("uncertainty") if isinstance(c, dict) else None
            label = unc if isinstance(unc, str) else (unc or {}).get("label") if isinstance(unc, dict) else None
            if label == "HIGH":
                high_unc += 1
                break
    out: Dict[str, Any] = {
        "source": str(path),
        "n_rows": rows,
        "n_safe_mean": statistics.mean(n_safe_list) if n_safe_list else None,
        "n_safe_min": min(n_safe_list) if n_safe_list else None,
        "rel_gap_p50": statistics.median(gaps) if gaps else None,
        "rel_gap_p90": (
            sorted(gaps)[max(0, int(0.9 * len(gaps)) - 1)] if gaps else None
        ),
        "rows_with_high_uncertainty": high_unc,
        "suggested_rel_gap": None,
    }
    # Keep default 0.15 if gaps cluster around it; else use p50 of observed gaps
    # clamped to [0.05, 0.30] so freeze is data-informed but conservative.
    if gaps:
        sug = statistics.median(gaps)
        sug = max(0.05, min(0.30, sug))
        out["suggested_rel_gap"] = round(sug, 4)
    else:
        out["suggested_rel_gap"] = 0.15
    return out


def write_freeze(meas_train: dict, meas_val: dict, freeze_path: Path) -> dict:
    sug = meas_train.get("suggested_rel_gap") or 0.15
    # Prefer the more conservative (smaller) gap so LLM triggers when either split is uncertain.
    sug_v = meas_val.get("suggested_rel_gap")
    if isinstance(sug_v, (int, float)):
        sug = min(float(sug), float(sug_v))
    freeze = {
        "schema": "kart.conditional_llm_freeze/v1",
        "status": "frozen_for_test",
        "notes": [
            "Frozen from holdout v2 train/val shared-pool / CostCard gap measurement",
            "Do not retune after locking the holdout v2 test split",
            "Measurement sources recorded in measurement block",
        ],
        "rel_gap": float(sug),
        "max_llm": 1,
        "holdout_version": "v2",
        "tune_splits": ["train", "val"],
        "eval_split": "test",
        "measurement": {"train": meas_train, "val": meas_val},
    }
    freeze_path.write_text(json.dumps(freeze, indent=2) + "\n", encoding="utf-8")
    return freeze


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--train-jsonl", required=True)
    ap.add_argument("--val-jsonl", required=True)
    ap.add_argument(
        "--freeze-out",
        default="experiments/suites/conditional_llm_freeze.json",
    )
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()
    train = measure_jsonl(Path(args.train_jsonl))
    val = measure_jsonl(Path(args.val_jsonl))
    print(json.dumps({"train": train, "val": val}, indent=2))
    if args.dry_run:
        return 0
    freeze = write_freeze(train, val, Path(args.freeze_out))
    print("wrote", args.freeze_out, "rel_gap=", freeze["rel_gap"], "status=", freeze["status"])
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
