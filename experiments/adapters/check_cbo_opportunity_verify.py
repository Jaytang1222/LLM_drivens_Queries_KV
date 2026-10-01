#!/usr/bin/env python3
"""Read-only gate for the CBO opportunity verification inputs.

``smoke`` accepts the existing regression scaffold. ``verify`` requires a
genuinely new, complete opportunity set before any full E2/E3 run.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def load(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def semantic_key(query: dict) -> str:
    body = {k: v for k, v in query.items() if k != "query_id"}
    return json.dumps(body, sort_keys=True, separators=(",", ":"), ensure_ascii=False)


def check(root: Path, mode: str, prefix: str = "bound_ir_cbo_opportunity_verify_v1") -> tuple[list[str], dict]:
    wl = root / "experiments/workloads"
    paths = {
        "workload": wl / f"{prefix}.json",
        "oracle": wl / f"{prefix}.oracle.json",
        "provenance": wl / f"{prefix}.provenance.json",
        "advantage_v2": wl / "bound_ir_advantage_v2.json",
    }
    missing = [name for name, path in paths.items() if not path.is_file()]
    if missing:
        return ["missing files: " + ", ".join(missing)], {}
    data = {name: load(path) for name, path in paths.items()}
    queries = data["workload"].get("queries") or []
    answers = data["oracle"].get("answers") or []
    provenance = data["provenance"]
    errors: list[str] = []
    qids = [q.get("query_id") for q in queries]
    aids = [a.get("query_id") for a in answers]
    if len(qids) != len(set(qids)) or not all(qids):
        errors.append("workload query IDs are missing or duplicated")
    if len(aids) != len(set(aids)) or set(qids) != set(aids):
        errors.append("Oracle IDs are missing, duplicated, or differ from workload IDs")
    if data["oracle"].get("oracle_status") != "frozen":
        errors.append("Oracle is not marked frozen")
    for name in ("workload", "oracle"):
        expected = (provenance.get("sha256") or {}).get(name)
        actual = hashlib.sha256(paths[name].read_bytes()).hexdigest()
        if expected != actual:
            errors.append(f"{name} SHA256 differs from provenance")
    semantic = [semantic_key(q) for q in queries]
    if len(semantic) != len(set(semantic)):
        errors.append("duplicate semantic queries within workload")
    prior = {semantic_key(q) for q in data["advantage_v2"].get("queries") or []}
    reused = [qid for qid, key in zip(qids, semantic) if key in prior]
    counts = provenance.get("categories") or {}
    if mode == "verify":
        if reused:
            errors.append("semantic copies of advantage_v2: " + ", ".join(reused))
        if len(queries) != 8:
            errors.append(f"expected 8 new BoundIR queries; found {len(queries)}")
        for category in ("large_opportunity", "cost_misestimate", "semantic_boundary", "normal_control"):
            if counts.get(category) != 2:
                errors.append(f"{category} quota is {counts.get(category, 0)}/2")
        if provenance.get("vacancies"):
            errors.append("opportunity quotas still have vacancies")
        if str(data["oracle"].get("source", "")).startswith("remapped_from_"):
            errors.append("Oracle was copied from an earlier workload; regenerate independently")
    return errors, {"queries": len(queries), "reused_prior_semantics": reused, "categories": counts}


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--mode", choices=("smoke", "verify"), default="verify")
    ap.add_argument("--prefix", default="bound_ir_cbo_opportunity_verify_v1",
                    help="Workload basename under experiments/workloads (use a new version for server validation)")
    ap.add_argument("--root", type=Path, default=ROOT)
    args = ap.parse_args()
    errors, info = check(args.root, args.mode, args.prefix)
    print(json.dumps({"mode": args.mode, "ready": not errors, "details": info, "errors": errors},
                     indent=2, ensure_ascii=False))
    return 0 if not errors else 1


if __name__ == "__main__":
    raise SystemExit(main())
