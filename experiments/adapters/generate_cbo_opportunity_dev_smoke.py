#!/usr/bin/env python3
"""Freeze a six-query *development* diagnostic from the already-seen v2 pool.

This deliberately reuses the existing FullScan Oracle. It is useful for a
quick mechanism check, never as an independent KART advantage result.
"""
from __future__ import annotations

import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
WL = ROOT / "experiments/workloads"
PREFIX = "bound_ir_cbo_opportunity_dev_smoke_v1"
IDS = (
    "a2_tz_hard_d",
    "a2_topk_dtw_wide",
    "topk_st_3",
    "a2_tz_hard_a",
    "a2_th_unc_a",
    "a2_th_unc_b",
)


def main() -> None:
    source = json.loads((WL / "bound_ir_advantage_v2.json").read_text(encoding="utf-8"))
    oracle = json.loads((WL / "bound_ir_advantage_v2.oracle.json").read_text(encoding="utf-8"))
    queries = {q["query_id"]: q for q in source["queries"]}
    answers = {a["query_id"]: a for a in oracle["answers"]}
    if any(qid not in queries or qid not in answers for qid in IDS):
        raise SystemExit("six-query source or Oracle incomplete")
    out_workload = {
        "manifest_id": source["manifest_id"],
        "semantics_version": source["semantics_version"],
        "workload_id": PREFIX,
        "evaluation_role": "already_seen_development_mechanism_check_only",
        "note": "Four census opportunities plus two controls; not independent or a win-rate sample.",
        "queries": [queries[qid] for qid in IDS],
    }
    out_oracle = {
        "manifest_id": oracle["manifest_id"],
        "semantics_version": oracle["semantics_version"],
        "oracle_status": "frozen",
        "source": "subset_of_existing_bound_ir_advantage_v2_FullScan_oracle",
        "answers": [answers[qid] for qid in IDS],
    }
    outputs = {
        WL / f"{PREFIX}.json": out_workload,
        WL / f"{PREFIX}.oracle.json": out_oracle,
    }
    encoded = {
        path: (json.dumps(data, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
        for path, data in outputs.items()
    }
    for path, content in encoded.items():
        if path.exists() and path.read_bytes() != content:
            raise SystemExit(f"refusing to overwrite changed input: {path}")
    for path, content in encoded.items():
        if not path.exists():
            path.write_bytes(content)
    provenance = {
        "role": "development_diagnostic_only",
        "source_workload": "bound_ir_advantage_v2.json",
        "source_oracle": "bound_ir_advantage_v2.oracle.json",
        "census_opportunities": list(IDS[:4]),
        "normal_controls": list(IDS[4:]),
        "query_count": len(IDS),
        "sha256": {path.name: hashlib.sha256(content).hexdigest()
                   for path, content in encoded.items()},
    }
    prov_path = WL / f"{PREFIX}.provenance.json"
    prov_bytes = (json.dumps(provenance, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
    if prov_path.exists() and prov_path.read_bytes() != prov_bytes:
        raise SystemExit(f"refusing to overwrite changed provenance: {prov_path}")
    if not prov_path.exists():
        prov_path.write_bytes(prov_bytes)
    print(f"frozen {len(IDS)} already-seen development queries: {PREFIX}")


if __name__ == "__main__":
    main()
