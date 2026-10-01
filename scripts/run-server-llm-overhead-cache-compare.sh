#!/usr/bin/env bash
# Server: re-run T-Drive 4 census plan overhead with cached arm; compare to frozen baseline.
# Does not change metric schema — same fields as run-server-llm-overhead.sh.
set -euo pipefail
source ~/.kart_server_env
cd "$KART_DST"
set -a
# shellcheck disable=SC1091
source .env
set +a
# Force DeepSeek profile for baseline-comparable cold path (ignore LLM_PROVIDER=google).
unset LLM_PROVIDER || true
export LLM_BASE_URL="${LLM_BASE_URL:-https://api.deepseek.com/v1}"
export LLM_MODEL="${LLM_MODEL:-deepseek-flash}"
export KART_CBO_LLM_BUDGET_MS="${KART_CBO_LLM_BUDGET_MS:-120000}"
echo "PROXY=$HTTPS_PROXY budget_ms=$KART_CBO_LLM_BUDGET_MS model=$LLM_MODEL base=$LLM_BASE_URL"
ss -ltn | grep -E ':17890\s' || { echo "FAIL: Clash HTTP reverse :17890 missing"; exit 1; }

RUN_ID="cbo-llm-overhead-cache-v1-$(date +%Y%m%d-%H%M%S)"
echo "run_id=$RUN_ID"
./scripts/bench-plan.sh \
  --suite experiments/suites/plan-cbo-llm-overhead-cache-v1.yaml \
  --run-id "$RUN_ID" \
  --trials 2 \
  --arm cbo,cbo-llm-proposal,cbo-llm-proposal-cached

OUT="experiments/results/$RUN_ID"
export OUT
COMPARE_PY="${KART_DST}/scripts/_compare_overhead_cache.py"
python3 "$COMPARE_PY"
