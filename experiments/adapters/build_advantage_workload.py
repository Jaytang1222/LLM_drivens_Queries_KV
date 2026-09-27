#!/usr/bin/env python3
"""
Build bound_ir_advantage_v1 — E2/E3 operating-region slice.

Selects multi-predicate / multi-candidate BoundIRs from bound_ir_v1 and
holdout v2. Reuses existing FullScan oracle answers. Does NOT run HBase.

Role: kart_operating_region (not confirmatory holdout, not the 65-query regression set).
"""
from __future__ import annotations

import hashlib
import json
from pathlib import Path
from typing import Dict, List, Tuple

ROOT = Path(__file__).resolve().parents[2]
WL_DIR = ROOT / "experiments/workloads"

V1_WL = WL_DIR / "bound_ir_v1.json"
V1_ORA = WL_DIR / "bound_ir_v1.oracle.json"
V2_WL = WL_DIR / "bound_ir_holdout_v2.json"
V2_ORAS = [
    WL_DIR / "bound_ir_holdout_v2_train.oracle.json",
    WL_DIR / "bound_ir_holdout_v2_val.oracle.json",
    WL_DIR / "bound_ir_holdout_v2_test.oracle.json",
]
OUT_WL = WL_DIR / "bound_ir_advantage_v1.json"
OUT_ORA = WL_DIR / "bound_ir_advantage_v1.oracle.json"
OUT_PROV = WL_DIR / "bound_ir_advantage_v1.provenance.json"

V1_FAMILIES = {"TZ", "TH", "ZH", "TZH"}
V1_SKIP_SELECTIVITY = {"empty", "boundary"}
V2_LAYERS = {
    "multi_index",
    "multi_index_uncertain",
    "topk_metric",
}
ROLE = "kart_operating_region"


def load(p: Path) -> dict:
    return json.loads(p.read_text(encoding="utf-8"))


def oracle_rows(ora: dict) -> List[dict]:
    ans = ora.get("answers")
    if isinstance(ans, list):
        return [r for r in ans if isinstance(r, dict) and r.get("query_id")]
    if isinstance(ans, dict):
        out = []
        for qid, row in ans.items():
            if isinstance(row, dict):
                r = dict(row)
                r.setdefault("query_id", qid)
                out.append(r)
        return out
    return []


def index_oracle(*paths: Path) -> Dict[str, dict]:
    by: Dict[str, dict] = {}
    for p in paths:
        if not p.is_file():
            raise SystemExit(f"missing oracle {p}")
        for row in oracle_rows(load(p)):
            qid = str(row["query_id"])
            if qid not in by:
                by[qid] = row
    return by


def keep_v1(qid: str, meta: dict) -> bool:
    fam = str(meta.get("family") or "")
    sel = str(meta.get("selectivity") or "")
    if sel in V1_SKIP_SELECTIVITY:
        return False
    if fam in {"T", "Z", "H"}:
        return False
    if str(qid).startswith("topk_s_"):
        return False
    if fam in V1_FAMILIES:
        return True
    return False


def keep_v2(qid: str, meta: dict) -> bool:
    # Keep the locked v2 test split out of the repeatedly used operating-region
    # slice.  Test is evaluated once through an explicit override, never through
    # the default E2/E3 suites.
    if str(meta.get("split") or "") == "test":
        return False
    layer = str(meta.get("layer") or "")
    fam = str(meta.get("family") or "")
    if fam in {"T", "Z", "H"} and layer not in V2_LAYERS:
        return False
    return layer in V2_LAYERS


def sha256_file(p: Path) -> str:
    return hashlib.sha256(p.read_bytes()).hexdigest()


def main() -> None:
    v1 = load(V1_WL)
    v2 = load(V2_WL)
    ora = index_oracle(V1_ORA, *V2_ORAS)

    queries: List[dict] = []
    catalog: Dict[str, dict] = {}
    sources: Dict[str, str] = {}
    excluded: List[Tuple[str, str, str]] = []

    v1_cat = v1.get("catalog") or {}
    for q in v1.get("queries") or []:
        qid = q.get("query_id")
        if not qid:
            continue
        meta = dict(v1_cat.get(qid) or {})
        if keep_v1(qid, meta):
            queries.append(q)
            meta["source_workload"] = "bound_ir_v1"
            catalog[qid] = meta
            sources[qid] = "v1"
        else:
            excluded.append((qid, str(meta.get("family")), str(meta.get("selectivity"))))

    v2_cat = v2.get("catalog") or {}
    for q in v2.get("queries") or []:
        qid = q.get("query_id")
        if not qid:
            continue
        if qid in catalog:
            continue
        meta = dict(v2_cat.get(qid) or {})
        if keep_v2(qid, meta):
            queries.append(q)
            meta["source_workload"] = "bound_ir_holdout_v2"
            catalog[qid] = meta
            sources[qid] = "v2"
        else:
            excluded.append((qid, str(meta.get("family")), str(meta.get("layer"))))

    missing = [q["query_id"] for q in queries if q["query_id"] not in ora]
    if missing:
        raise SystemExit("oracle missing for: " + ", ".join(missing))

    families = sorted({str(catalog[q["query_id"]].get("family")) for q in queries})
    bad_fam = [qid for qid, m in catalog.items() if m.get("family") in {"T", "Z", "H"}]
    # TopK is allowed; T/Z/H single-predicate must not appear
    if bad_fam:
        raise SystemExit("single-index families leaked: " + ", ".join(bad_fam))
    empty_sel = [
        qid
        for qid, m in catalog.items()
        if m.get("selectivity") in V1_SKIP_SELECTIVITY or m.get("layer") == "empty_boundary"
    ]
    if empty_sel:
        raise SystemExit("empty/boundary leaked: " + ",".join(empty_sel))

    answers = [ora[q["query_id"]] for q in queries]
    non_empty = sum(
        1
        for a in answers
        if (a.get("trajectory_ids") or a.get("ids") or [])
    )

    wl = {
        "manifest_id": v1.get("manifest_id") or "tdrive_v1_ready",
        "semantics_version": v1.get("semantics_version") or "point_dtw_v1",
        "workload_id": "bound_ir_advantage_v1",
        "evaluation_role": ROLE,
        "seed": "2026-09-27-advantage-v1",
        "anchor": v1.get("anchor"),
        "queries": queries,
        "catalog": catalog,
        "query_count": len(queries),
    }
    ora_out = {
        "manifest_id": wl["manifest_id"],
        "semantics_version": wl["semantics_version"],
        "answers": answers,
        "total": len(answers),
        "non_empty": non_empty,
        "source": "merged bound_ir_v1.oracle.json + holdout v2 split oracles",
    }

    OUT_WL.write_text(json.dumps(wl, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    OUT_ORA.write_text(json.dumps(ora_out, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")

    prov = {
        "path": "experiments/workloads/bound_ir_advantage_v1.json",
        "oracle_path": "experiments/workloads/bound_ir_advantage_v1.oracle.json",
        "evaluation_role": ROLE,
        "n_queries": len(queries),
        "n_from_v1": sum(1 for s in sources.values() if s == "v1"),
        "n_from_v2": sum(1 for s in sources.values() if s == "v2"),
        "families": families,
        "include_v1_families": sorted(V1_FAMILIES),
        "include_v2_layers": sorted(V2_LAYERS),
        "exclude": [
            "single-predicate T/Z/H",
            "selectivity empty/boundary",
            "spatial-only Top-K (topk_s_*)",
            "v2 easy_single_index / easy_hash / empty_boundary / hard_large_window",
            "v2 split=test (reserved for locked one-shot confirmation)",
        ],
        "query_ids": [q["query_id"] for q in queries],
        "sha256": sha256_file(OUT_WL),
        "oracle_sha256": sha256_file(OUT_ORA),
        "oracle_status": "merged_existing",
        "generator": "experiments/adapters/build_advantage_workload.py",
        "notes": [
            "Operating-region slice for E2 plan select and E3 e2e defaults.",
            "Not a confirmatory holdout; bound_ir_v1.json remains regression/oracle-diff.",
            "Only holdout v2 train/val are included; v2 test remains the locked confirmation set (not the suite default).",
        ],
    }
    OUT_PROV.write_text(json.dumps(prov, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(
        "OK n=%d v1=%d v2=%d families=%s oracle_non_empty=%d"
        % (len(queries), prov["n_from_v1"], prov["n_from_v2"], families, non_empty)
    )


if __name__ == "__main__":
    main()
