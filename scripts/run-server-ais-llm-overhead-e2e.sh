#!/usr/bin/env bash
# Small AIS E2E: LLM-advantage queries, report t_plan / t_exec vs CBO.
set -euo pipefail
source ~/.kart_server_env
cd "$KART_DST"
set -a
# shellcheck disable=SC1091
source .env
set +a
export KART_CBO_LLM_BUDGET_MS="${KART_CBO_LLM_BUDGET_MS:-120000}"
echo "PROXY=$HTTPS_PROXY budget=$KART_CBO_LLM_BUDGET_MS model=$LLM_MODEL"
ss -ltn | grep -E ':17890\s' || { echo "FAIL: no Clash :17890"; exit 1; }

python3 - <<'PY'
import json
from pathlib import Path
w=json.loads(Path("experiments/workloads/bound_ir_ais_opportunity_v1.json").read_text())
print("queries", [q["query_id"] for q in w["queries"]])
PY

RUN_ID="ais-llm-overhead-e2e-$(date +%Y%m%d-%H%M%S)"
echo "run_id=$RUN_ID"
./scripts/bench-e2e.sh \
  --suite experiments/suites/e2e-ais-llm-overhead-v1.yaml \
  --run-id "$RUN_ID" \
  --trials 1 \
  --cache warm \
  --arm cbo,cbo-llm-proposal

python3 experiments/adapters/summarize_ais_e2e_netgain.py \
  --results-glob "experiments/results/${RUN_ID}/e2e.jsonl"
echo OUT=experiments/results/$RUN_ID
