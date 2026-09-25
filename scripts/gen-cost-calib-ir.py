#!/usr/bin/env python3
"""Generate BoundIR grid for cost calibration (corpus A)."""
from __future__ import annotations

import argparse
import json
from pathlib import Path


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", type=Path, default=Path("experiments/workloads/cost_calib"))
    ap.add_argument("--manifest-id", default="tdrive_v1_ready")
    args = ap.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)

    templates = []
    # Time widths (ms)
    times = [
        (1_230_768_000_000, 1_230_854_400_000),  # ~1 day
        (1_230_768_000_000, 1_231_200_000_000),  # ~5 days
    ]
    spaces = [
        (439000.0, 4415000.0, 450000.0, 4425000.0),
        (400000.0, 4390000.0, 500000.0, 4450000.0),
    ]
    metrics = ["DTW", "FRECHET", "HAUSDORFF"]
    modes = [("TRAJECTORY_IDS", None), ("TOP_K", 5), ("TOP_K", 20)]

    q = 0
    for t0, t1 in times:
        for s in spaces:
            for mode, k in modes:
                for metric in metrics if mode == "TOP_K" else [None]:
                    q += 1
                    result = {"mode": mode, "tie_breaker": "TID_ASC"}
                    if k is not None:
                        result["k"] = k
                    ir = {
                        "ir_version": "1.0",
                        "query_id": f"cost_calib_{q:03d}",
                        "source": {"dataset_id": "tdrive", "entity": "trajectory"},
                        "temporal": {"start_ms": t0, "end_ms": t1},
                        "spatial": {
                            "min_x": s[0],
                            "min_y": s[1],
                            "max_x": s[2],
                            "max_y": s[3],
                            "relation": "INTERSECTS",
                            "boundary": "INCLUDED",
                        },
                        "predicates": [],
                        "semantics": {"mode": "OBSERVED_POINT", "coupling": "SAME_POINT"},
                        "result": result,
                        "snapshot": {
                            "manifest_id": args.manifest_id,
                            "semantics_version": "point_similarity_v2",
                        },
                    }
                    if mode == "TOP_K":
                        ir["similarity"] = {
                            "metric": metric,
                            "reference_tid": 1,
                            "scope": "FULL_TRAJECTORY",
                            "exclude_reference": True,
                            "local_distance": "EUCLIDEAN",
                            "normalization": "NONE",
                        }
                    path = args.out / f"{ir['query_id']}.bound_ir.json"
                    path.write_text(json.dumps(ir, indent=2), encoding="utf-8")
                    templates.append(str(path))
    print(f"wrote {len(templates)} IRs under {args.out}")


if __name__ == "__main__":
    main()
