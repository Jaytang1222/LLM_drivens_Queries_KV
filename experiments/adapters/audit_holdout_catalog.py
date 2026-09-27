#!/usr/bin/env python3
"""
Offline catalog audit for holdout v2 (no HBase / no FullScan).

Reports split/group/layer coverage and whether intended conditional-LLM
hypothesis layers exist *by name*. Does NOT measure n_safe / CostCard gaps.
"""
from __future__ import annotations

import argparse
import json
from collections import Counter, defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--version", default="v2")
    args = ap.parse_args()
    wl_path = ROOT / f"experiments/workloads/bound_ir_holdout_{args.version}.json"
    wl = json.loads(wl_path.read_text(encoding="utf-8"))
    cat = wl.get("catalog") or {}
    by_split: dict = defaultdict(list)
    layers = Counter()
    for qid, meta in cat.items():
        by_split[meta.get("split")].append(
            {
                "query_id": qid,
                "group": meta.get("group"),
                "family": meta.get("family"),
                "layer": meta.get("layer"),
            }
        )
        layers[meta.get("layer")] += 1

    report = {
        "workload": str(wl_path.relative_to(ROOT)).replace("\\", "/"),
        "evaluation_role": wl.get("evaluation_role"),
        "n_queries": len(cat),
        "n_by_split": {k: len(v) for k, v in sorted(by_split.items())},
        "layers": dict(layers),
        "groups_by_split": {
            sp: sorted({r["group"] for r in rows}) for sp, rows in sorted(by_split.items())
        },
        "hypothesis_layers_named": {
            "multi_index": layers.get("multi_index", 0),
            "multi_index_uncertain": layers.get("multi_index_uncertain", 0),
        },
        "caveat": (
            "Layer counts are catalog labels only. Measure n_safe / CostCard gap on "
            "train/val with HBase before freezing conditional_llm_freeze.json."
        ),
    }
    out = ROOT / f"experiments/workloads/bound_ir_holdout_{args.version}.catalog_audit.json"
    out.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2))
    print("wrote", out)


if __name__ == "__main__":
    main()
