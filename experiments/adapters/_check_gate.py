import json
from pathlib import Path

root = Path("/home/jaytang/projects/llm-kv/experiments/results")
for run in ["gate-fixed"]:
    p = root / run / "e2e.jsonl"
    if not p.exists():
        print("missing", p)
        continue
    rows = [json.loads(l) for l in p.read_text().splitlines() if l.strip()]
    print("RUN", run, "n", len(rows))
    for r in rows:
        print(
            r["arm"],
            r["query_id"],
            r.get("plan_id"),
            "ncand",
            r.get("n_candidates"),
            "ncards",
            r.get("n_cost_cards"),
            "search",
            r.get("candidate_search"),
            "ok",
            r.get("ok_oracle"),
        )
    meta = json.loads((root / run / "meta.json").read_text())
    print(
        "meta cache_enforced",
        meta.get("cache_enforced"),
        "dirty",
        meta.get("git_worktree_dirty"),
        "commit",
        meta.get("git_commit"),
    )
