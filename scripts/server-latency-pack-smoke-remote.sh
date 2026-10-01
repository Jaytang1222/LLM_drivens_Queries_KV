#!/usr/bin/env bash
set -euo pipefail
source ~/.kart_server_env
export LANG=C.UTF-8 LC_ALL=C.UTF-8
# Keep socks props; force UTF-8 for JSON workloads
export JAVA_TOOL_OPTIONS="-DsocksProxyHost=127.0.0.1 -DsocksProxyPort=1080 -Dfile.encoding=UTF-8"
cd "$KART_DST"
ss -ltn | grep -q ':1080' && echo SOCKS_OK || { echo SOCKS_MISSING; exit 3; }
grep '^LLM_MODEL=' .env
test -f experiments/workloads/bound_ir_cbo_opportunity_dev_smoke_v1.json
test -f experiments/workloads/bound_ir_cbo_opportunity_dev_smoke_v1.oracle.json
test -f experiments/suites/plan-cbo-llm-latency-pack-dev.yaml
echo "=== plan smoke ==="
bash scripts/bench-plan.sh \
  --suite experiments/suites/plan-cbo-llm-latency-pack-dev.yaml \
  --run-id cbo-llm-latency-pack-dev-20260929 \
  --trials 1
echo PACK_DONE
# Quick extract of key fields
python3 - <<'PY'
import json
from pathlib import Path
root = Path("experiments/results/cbo-llm-latency-pack-dev-20260929")
plan = root / "plan.jsonl"
if not plan.is_file():
    # find latest
    cands = sorted(Path("experiments/results").glob("cbo-llm-latency-pack-dev-*/plan.jsonl"))
    plan = cands[-1] if cands else None
    root = plan.parent if plan else None
print("plan_path", plan)
rows = [json.loads(l) for l in plan.read_text(encoding="utf-8").splitlines() if l.strip()]
print("n_rows", len(rows))
for r in rows:
    arm = r.get("arm") or r.get("arm_id")
    print({
        "qid": r.get("query_id"),
        "arm": arm,
        "t_plan_ms": r.get("t_plan_ms"),
        "llm_calls": (r.get("extras") or {}).get("llm_calls", r.get("llm_calls")),
        "llm_triggered": (r.get("extras") or {}).get("llm_triggered", r.get("llm_triggered")),
        "llm_latency_ms": (r.get("extras") or {}).get("llm_latency_ms", r.get("llm_latency_ms")),
        "fallback_reason": (r.get("extras") or {}).get("fallback_reason", r.get("fallback_reason")),
        "proposal_plan_id": (r.get("extras") or {}).get("proposal_plan_id", r.get("proposal_plan_id")),
        "selected": (r.get("extras") or {}).get("selected_plan_id", r.get("plan_id")),
        "cache_hit": (r.get("extras") or {}).get("cache_hit", r.get("cache_hit")),
        "prompt_ver": (r.get("extras") or {}).get("proposal_prompt_version"),
    })
PY
