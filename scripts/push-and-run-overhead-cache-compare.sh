#!/usr/bin/env bash
# Push cache-compare suite/scripts/docs and run on server.
set -euo pipefail
KEY="${HOME}/.ssh/id_ed25519_kart_server"
HOST=tyq@10.242.104.108
ROOT=/mnt/f/Projects/LLM_KV

scp -i "$KEY" -o IdentitiesOnly=yes -o BatchMode=yes \
  "$ROOT/experiments/suites/plan-cbo-llm-overhead-cache-v1.yaml" \
  "$ROOT/scripts/run-server-llm-overhead-cache-compare.sh" \
  "$ROOT/scripts/_compare_overhead_cache.py" \
  "$ROOT/docs/cbo_llm_plan_overhead_cache.md" \
  "$HOST:/tmp/"

ssh -i "$KEY" -o IdentitiesOnly=yes -o BatchMode=yes "$HOST" 'bash -s' <<'REMOTE'
set -euo pipefail
source ~/.kart_server_env
cd "$KART_DST"
cp /tmp/plan-cbo-llm-overhead-cache-v1.yaml experiments/suites/
cp /tmp/run-server-llm-overhead-cache-compare.sh scripts/
cp /tmp/_compare_overhead_cache.py scripts/
mkdir -p docs
cp /tmp/cbo_llm_plan_overhead_cache.md docs/
sed -i 's/\r$//' experiments/suites/plan-cbo-llm-overhead-cache-v1.yaml \
  scripts/run-server-llm-overhead-cache-compare.sh \
  scripts/_compare_overhead_cache.py \
  docs/cbo_llm_plan_overhead_cache.md
chmod +x scripts/run-server-llm-overhead-cache-compare.sh
bash scripts/run-server-llm-overhead-cache-compare.sh
REMOTE
