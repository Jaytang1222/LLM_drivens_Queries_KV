#!/usr/bin/env bash
set -euo pipefail
source ~/.kart_server_env
cd "$KART_DST"
echo "PWD=$PWD"
jar tf target/kart.jar | grep -E 'FixedPlan|BenefitCalib' || true
echo '--- meta ---'
cat experiments/results/refine4-iso-P_T-alone-t1-20261001-185700/meta.json
echo
echo '--- summary ---'
cat experiments/results/refine4-iso-P_T-alone-t1-20261001-185700/summary.md
echo '--- suite arms ---'
sed -n '1,40p' experiments/suites/e2e-ais-llm-overhead-v1.yaml
echo '--- dry run fixed-plan ---'
export KART_FIXED_PLAN_ID=P_T
export KART_FIXED_PLAN_PARALLEL_LLM=0
export LLM_PROVIDER=ollama
export LLM_BASE_URL=http://127.0.0.1:11434/v1
export LLM_MODEL=qwen2.5:1.5b-instruct
export LLM_API_KEY=ollama
export KART_EXPERIMENT_MANIFEST=ais_v1_ready
./scripts/bench-e2e.sh \
  --suite experiments/suites/e2e-ais-llm-overhead-v1.yaml \
  --run-id refine4-diag-fixed \
  --trials 1 \
  --cache warm \
  --arm fixed-plan 2>&1 | tail -80
