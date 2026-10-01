#!/usr/bin/env bash
# On server: evaluate FullScan oracle for AIS complex workload, then census + E2E.
set -euo pipefail
source ~/.kart_server_env
cd "$KART_DST"
set -a
# shellcheck disable=SC1091
[[ -f .env ]] && source .env
set +a
export KART_CBO_LLM_BUDGET_MS="${KART_CBO_LLM_BUDGET_MS:-120000}"

echo "=== build-oracle-cache evaluate AIS complex ==="
./scripts/kart.sh run build-oracle-cache \
  --evaluate-workload \
  --manifest ais_v1_ready \
  --data datasets/ais \
  --format ais \
  --crs EPSG:32634 \
  --workload-in experiments/workloads/bound_ir_ais_complex_v1.json \
  --oracle-out experiments/workloads/bound_ir_ais_complex_v1.oracle.json

echo "=== opportunity census (AIS) ==="
bash scripts/run-opportunity-census.sh \
  --pool experiments/workloads/bound_ir_ais_complex_v1.json \
  --no-extra-pool \
  --oracle experiments/workloads/bound_ir_ais_complex_v1.oracle.json \
  --exclude experiments/workloads/bound_ir_holdout_v2_test.json \
  --manifest ais_v1_ready \
  --run-id opportunity-ais-complex-v1 \
  --cache warm \
  --initial-trials 1 \
  --repeat-trials 3 \
  --max-exec-ms 120000 \
  --max-wall-minutes 120 \
  --hybrid-extra-plan-ms 5000

python3 experiments/adapters/freeze_ais_opportunity_v1.py \
  --census experiments/opportunity/opportunity-ais-complex-v1

echo "=== E2E hybrid vs CBO on frozen AIS opportunities ==="
ss -ltn | grep -E ':17890\s' || echo "WARN: no Clash :17890 (LLM may fail)"
./scripts/bench-e2e.sh \
  --suite experiments/suites/e2e-ais-llm-overhead-v1.yaml \
  --run-id "ais-llm-e2e-$(date +%Y%m%d-%H%M%S)" \
  --trials 1 \
  --cache warm \
  --arm cbo,cbo-llm-proposal

python3 experiments/adapters/summarize_ais_e2e_netgain.py \
  --results-glob 'experiments/results/ais-llm-e2e-*/e2e.jsonl'
echo AIS_PIPELINE_DONE
