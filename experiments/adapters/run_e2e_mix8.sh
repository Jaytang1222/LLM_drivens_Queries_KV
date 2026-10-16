#!/usr/bin/env bash
set -euo pipefail
source ~/.kart_server_env
cd "$KART_DST"
export KART_ADVANCED_INDEXES=1
export KART_ACTION_MODE=dual-rules
# Hybrid XZT Final-cost extract dominates t_plan; keep shortlist tight.
export KART_ADV_VALIDATION_CAP=4
unset KART_FIXED_PLAN_ID || true
unset KART_ACTION_FORCE || true
RUN=adv-mix-e2e-$(date +%Y%m%d-%H%M%S)
echo "run=$RUN" | tee /tmp/e2e_mix8_run.txt
# 5 trials: median e2e; short vs RBO same-template needs ≥3 tight samples.
bash scripts/bench-e2e.sh --suite experiments/suites/e2e-adv-mix.yaml \
  --run-id "$RUN" --cache warm --trials 5 \
  >"/tmp/e2e_mix8_${RUN}.log" 2>&1
echo DONE_E2E $(date -Is) | tee -a /tmp/e2e_mix8_run.txt
echo "$RUN" > /tmp/last_mix_e2e_run.txt
