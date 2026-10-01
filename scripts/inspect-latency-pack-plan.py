#!/usr/bin/env python3
import json
from pathlib import Path
p = Path("/home/tyq/projects/llm-kv/experiments/results/cbo-llm-latency-pack-dev-20260929/plan.jsonl")
rows = [json.loads(l) for l in p.read_text(encoding="utf-8").splitlines() if l.strip()]
for r in rows:
    if r.get("arm") != "cbo-llm-proposal":
        continue
    print(
        r.get("query_id"),
        "t_plan", r.get("t_plan_ms"),
        "calls", r.get("llm_calls"),
        "trig", r.get("llm_triggered"),
        "lat", r.get("llm_latency_ms"),
        "fb", r.get("fallback_reason"),
        "skip", r.get("llm_skip_reason"),
        "wl", r.get("n_whitelist"),
        "pv", r.get("proposal_prompt_version"),
        "prop", r.get("proposal_plan_id"),
        "sel", r.get("selected_plan_id") or r.get("plan_id"),
        "cache", r.get("cache_hit"),
    )
