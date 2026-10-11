"""Pad temporal/spatial windows of cmp-v6 seed queries for remine."""
from __future__ import annotations

import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from admit_dual_rules import ROOT, TRAIN_KEY, _load, _scale_spatial, is_tiny


def _pad_temporal(q: dict, factor: float, new_id: str):
    out = json.loads(json.dumps(q))
    out["query_id"] = new_id
    t = out.get("temporal")
    if not t or t.get("start_ms") is None or t.get("end_ms") is None:
        return None
    mid = (t["start_ms"] + t["end_ms"]) / 2.0
    half = (t["end_ms"] - t["start_ms"]) * factor / 2.0
    out["temporal"] = {"start_ms": int(mid - half), "end_ms": int(mid + half)}
    return out


def _pad_both(q: dict, t_factor: float, s_factor: float, new_id: str):
    q2 = _pad_temporal(q, t_factor, new_id)
    if q2 is None:
        return None
    return _scale_spatial(q2, s_factor, new_id)


def main() -> None:
    seeds_path = Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / "experiments/workloads/bound_ir_cmp_v6_pad_seeds.json"
    seeds = _load(seeds_path)
    rules = _load(ROOT / "experiments/suites/dual_action_rules.json")
    ban = set(rules.get(TRAIN_KEY) or [])
    for name in ("bound_ir_holdout_v2_test.json", "bound_ir_holdout_v2_val.json"):
        ban |= {q["query_id"] for q in _load(ROOT / "experiments/workloads" / name)["queries"]}
    seen = set(ban)
    queries = []
    for q in seeds.get("queries") or []:
        qid = q["query_id"]
        if qid in seen or is_tiny(q):
            continue
        seen.add(qid)
        variants = [
            _pad_temporal(q, 1.2, f"{qid}__tpad12"),
            _pad_temporal(q, 1.5, f"{qid}__tpad15"),
            _scale_spatial(q, 1.2, f"{qid}__spad12"),
            _scale_spatial(q, 1.4, f"{qid}__spad14"),
            _pad_both(q, 1.25, 1.2, f"{qid}__tspad"),
        ]
        for v in variants:
            if v is None or v["query_id"] in seen or is_tiny(v):
                continue
            seen.add(v["query_id"])
            queries.append(v)
    wl = {
        "manifest_id": "tdrive_v1_ready",
        "workload_id": "bound_ir_cmp_v6_pad",
        "evaluation_role": "cmp_v6_pad_expand",
        "semantics_version": "point_similarity_v2",
        "queries": queries,
    }
    wp = ROOT / "experiments/workloads/bound_ir_cmp_v6_pad.json"
    wp.write_text(json.dumps(wl, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    suite = (
        "kind: compare\n"
        "id: e2e-mine-cmp-v6-pad\n"
        "workload: experiments/workloads/bound_ir_cmp_v6_pad.json\n"
        "oracle: experiments/workloads/bound_ir_cmp_v6_pad.oracle.json\n"
        "manifest: tdrive_v1_ready\n"
        "require_oracle: true\n"
        "trials: 1\n"
        "stages:\n"
        "  - e2e\n"
        "arms:\n"
        "  - kart\n"
        "  - cbo\n"
    )
    sp = ROOT / "experiments/suites/e2e-mine-cmp-v6-pad.yaml"
    sp.write_text(suite, encoding="utf-8")
    print(json.dumps({"n_queries": len(queries), "workload": str(wp), "suite": str(sp)}))


if __name__ == "__main__":
    main()
