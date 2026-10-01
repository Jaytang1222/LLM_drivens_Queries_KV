#!/usr/bin/env bash
set -euo pipefail
KEY="${HOME}/.ssh/id_ed25519_kart_server"
ssh -i "$KEY" -o IdentitiesOnly=yes -o BatchMode=yes tyq@10.242.104.108 'bash -s' <<'REMOTE'
set -euo pipefail
cd ~/projects/llm-kv
python3 - <<'PY'
import json
import collections
from pathlib import Path
p = Path("experiments/results/cbo-llm-overhead-cache-v1-20260929-153931/plan.jsonl")
rows = [json.loads(line) for line in p.read_text().splitlines() if line.strip()]
print("n", len(rows))
print("arms", collections.Counter(
    (rec.get("arm"), rec.get("trial"), rec.get("cache_hit"), rec.get("llm_calls"))
    for rec in rows
))
for rec in rows[:6]:
    keys = ["query_id", "arm", "trial", "t_plan_ms", "llm_latency_ms", "cache_hit", "llm_calls"]
    print({k: rec.get(k) for k in keys})
PY
REMOTE
