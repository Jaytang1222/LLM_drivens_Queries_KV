#!/usr/bin/env python3
"""
Split holdout BoundIR into train/val/test workloads (fail-closed integrity).

Does NOT run FullScan. Oracle fill is gated:
  ./scripts/fill-holdout-oracle.sh --version v2

Usage:
  python3 experiments/adapters/split_holdout_workloads.py --version v2
  python3 experiments/adapters/split_holdout_workloads.py --version v1   # legacy leaky set
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import sys
from pathlib import Path

ROOT = Path(os.environ.get("KART_HOLDOUT_ROOT") or Path(__file__).resolve().parents[2]).resolve()
sys.path.insert(0, str(Path(__file__).resolve().parent))

from holdout_integrity import assert_holdout_integrity, summarize_splits  # noqa: E402

OUT_DIR = ROOT / "experiments/workloads"


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--version", default="v2", choices=["v1", "v2"])
    ap.add_argument(
        "--allow-leaky",
        action="store_true",
        help="Permit v1 cross-split group leakage (diagnostic only).",
    )
    args = ap.parse_args()
    prov = OUT_DIR / f"bound_ir_holdout_{args.version}.provenance.json"
    if prov.is_file() and json.loads(prov.read_text(encoding="utf-8")).get("oracle_status") == "frozen":
        raise SystemExit(f"refuse to split frozen holdout {args.version}; create a new version")
    if any((OUT_DIR / f"bound_ir_holdout_{args.version}_{split}.oracle.json").exists()
           for split in ("train", "val", "test")):
        raise SystemExit(f"refuse to split holdout {args.version} with existing oracles")
    src = OUT_DIR / f"bound_ir_holdout_{args.version}.json"
    if not src.is_file():
        raise SystemExit(f"missing {src}")

    wl = json.loads(src.read_text(encoding="utf-8"))
    catalog = wl.get("catalog") or {}
    queries = wl.get("queries") or []

    if args.version == "v2" or not args.allow_leaky:
        try:
            assert_holdout_integrity(queries, catalog)
        except ValueError as e:
            if args.version == "v1":
                raise SystemExit(
                    f"v1 integrity failed ({e}). Re-run with --allow-leaky "
                    "only for diagnostic splits; prefer --version v2."
                )
            raise SystemExit(f"holdout integrity failed: {e}")

    by_split = {"train": [], "val": [], "test": []}
    for q in queries:
        qid = q.get("query_id")
        meta = catalog.get(qid) or {}
        split = meta.get("split")
        if split not in by_split:
            raise SystemExit(f"unknown split for {qid}: {split}")
        by_split[split].append(q)

    written = {}
    role = wl.get("evaluation_role") or (
        "diagnostic_leaky" if args.version == "v1" else "diagnostic_generalization_stress"
    )
    for split, qs in by_split.items():
        out = {
            "manifest_id": wl.get("manifest_id"),
            "semantics_version": wl.get("semantics_version"),
            "holdout": True,
            "holdout_version": args.version,
            "holdout_split": split,
            "seed": wl.get("seed"),
            "anchor": wl.get("anchor"),
            "evaluation_role": role,
            "queries": qs,
            "catalog": {q["query_id"]: catalog[q["query_id"]] for q in qs},
            "parent_workload": str(src.relative_to(ROOT)).replace("\\", "/"),
            "oracle_status": "pending_fullscan_fill",
            "notes": [
                f"Split from bound_ir_holdout_{args.version}.json",
                "Do not retune thresholds on test",
                "Oracle SHA256 frozen only after fill-holdout-oracle.sh",
            ],
        }
        path = OUT_DIR / f"bound_ir_holdout_{args.version}_{split}.json"
        blob = json.dumps(out, indent=2, ensure_ascii=False) + "\n"
        path.write_text(blob, encoding="utf-8")
        written[split] = {
            "path": str(path.relative_to(ROOT)).replace("\\", "/"),
            "n": len(qs),
            "sha256": hashlib.sha256(blob.encode("utf-8")).hexdigest(),
            "query_ids": [q["query_id"] for q in qs],
            "groups": sorted(
                {
                    catalog[q["query_id"]].get("group")
                    for q in qs
                    if catalog.get(q["query_id"])
                }
            ),
        }
        print("wrote", path, "n=", len(qs))

    summary = summarize_splits(catalog)
    meta_path = OUT_DIR / f"bound_ir_holdout_{args.version}.splits.json"
    meta = {
        "parent": str(src.relative_to(ROOT)).replace("\\", "/"),
        "holdout_version": args.version,
        "seed": wl.get("seed"),
        "evaluation_role": role,
        "splits": written,
        "groups_by_split": summary["groups_by_split"],
        "oracle_status": "pending_fullscan_fill",
        "policy": {
            "train": "may tune KART_COND_REL_GAP / cost coeffs",
            "val": "select thresholds once",
            "test": "evaluate once after freeze; never retune",
        },
    }
    meta_path.write_text(json.dumps(meta, indent=2) + "\n", encoding="utf-8")
    print("wrote", meta_path)


if __name__ == "__main__":
    main()
