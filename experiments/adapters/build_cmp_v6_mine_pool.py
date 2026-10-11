"""Build a long/wide mine pool workload (+suite) for cmp-v6 paired e2e."""
from __future__ import annotations

import json
from pathlib import Path

from admit_dual_rules import ROOT, TRAIN_KEY, _load, is_tiny

SOURCES = (
    "bound_ir_advantage_v2.json",
    "bound_ir_mine_newidx.json",
    "bound_ir_cmp_v5.json",
    "bound_ir_cmp_force_adv.json",
    "bound_ir_cbo_opportunity_candidates_v3.json",
    "bound_ir_cmp_v3.json",
)


def main() -> None:
    rules = _load(ROOT / "experiments/suites/dual_action_rules.json")
    ban = set(rules.get(TRAIN_KEY) or [])
    for name in ("bound_ir_holdout_v2_test.json", "bound_ir_holdout_v2_val.json"):
        ban |= {q["query_id"] for q in _load(ROOT / "experiments/workloads" / name)["queries"]}
    oidx = {}
    for p in sorted((ROOT / "experiments/workloads").glob("*.oracle.json")):
        try:
            doc = _load(p)
        except Exception:
            continue
        for a in doc.get("answers") or []:
            qid = a.get("query_id")
            if qid and qid not in oidx:
                oidx[qid] = a
    seen = set()
    queries = []
    answers = []
    for name in SOURCES:
        p = ROOT / "experiments/workloads" / name
        if not p.is_file():
            continue
        for q in _load(p).get("queries") or []:
            qid = q.get("query_id")
            if not qid or qid in seen or qid in ban or is_tiny(q) or qid not in oidx:
                continue
            seen.add(qid)
            queries.append(q)
            answers.append(oidx[qid])
    wl = {
        "manifest_id": "tdrive_v1_ready",
        "workload_id": "bound_ir_cmp_v6_mine",
        "evaluation_role": "cmp_v6_mine_pool",
        "semantics_version": "point_similarity_v2",
        "queries": queries,
    }
    ora = {"answers": answers}
    wp = ROOT / "experiments/workloads/bound_ir_cmp_v6_mine.json"
    op = ROOT / "experiments/workloads/bound_ir_cmp_v6_mine.oracle.json"
    wp.write_text(json.dumps(wl, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    op.write_text(json.dumps(ora, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    suite = (
        "kind: compare\n"
        "id: e2e-mine-cmp-v6\n"
        "workload: experiments/workloads/bound_ir_cmp_v6_mine.json\n"
        "oracle: experiments/workloads/bound_ir_cmp_v6_mine.oracle.json\n"
        "manifest: tdrive_v1_ready\n"
        "require_oracle: true\n"
        "trials: 1\n"
        "stages:\n"
        "  - e2e\n"
        "arms:\n"
        "  - kart\n"
        "  - cbo\n"
    )
    sp = ROOT / "experiments/suites/e2e-mine-cmp-v6.yaml"
    sp.write_text(suite, encoding="utf-8")
    print(json.dumps({"n_queries": len(queries), "workload": str(wp), "suite": str(sp)}))


if __name__ == "__main__":
    main()
