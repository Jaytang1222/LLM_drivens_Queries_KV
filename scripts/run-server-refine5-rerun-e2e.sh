#!/usr/bin/env bash
# Hotfix for choice:N parse issue → re-run synthetic + AIS/TD E2E into existing OUT_ROOT.
set -euo pipefail
source ~/.kart_server_env
cd "$KART_DST"
case "$KART_DST" in
  /home/tyq/*) ;;
  *) echo "FAIL: KART_DST=$KART_DST"; exit 1 ;;
esac

OUT_ROOT="${1:-}"
if [[ -z "$OUT_ROOT" ]]; then
  OUT_ROOT=$(ls -1d experiments/refine_5/refine5-* 2>/dev/null | sort | tail -1)
fi
[[ -d "$OUT_ROOT" ]] || { echo "FAIL: no OUT_ROOT"; exit 1; }
STAMP="${OUT_ROOT##*refine5-}"
echo "RERUN out=$OUT_ROOT stamp=$STAMP"

MODEL="${OLLAMA_MODEL:-qwen2.5:1.5b-instruct}"
export PATH="$HOME/.local/bin:$PATH"
export OLLAMA_HOST=127.0.0.1:11434
export OLLAMA_MODELS="${OLLAMA_MODELS:-$HOME/.ollama}"
ss -ltn | grep -qE ':11434\s' || { echo "FAIL: ollama not listening"; exit 1; }
OLLAMA_ENV="${OLLAMA_MODELS}/kart_llm_overlay.env"
cat >"$OLLAMA_ENV" <<EOF
LLM_PROVIDER=ollama
LLM_BASE_URL=http://127.0.0.1:11434/v1
LLM_MODEL=${MODEL}
LLM_API_KEY=ollama
LLM_JSON_MODE=false
EOF
export LLM_ENV_FILE="$OLLAMA_ENV"
export LLM_PROVIDER=ollama
export LLM_BASE_URL="http://127.0.0.1:11434/v1"
export LLM_MODEL="$MODEL"
export LLM_API_KEY="ollama"
export LLM_JSON_MODE=false
export KART_CBO_LLM_BUDGET_MS=120000
export KART_CBO_LLM_MAX_TOKENS=16
export KART_CBO_LLM_SPECULATE_K=1
export KART_CBO_LLM_PREPARE_MODE=safety_only
export KART_CBO_LLM_PROTOCOL=v9
export KART_CBO_LLM_ALWAYS=1
export KART_CBO_LLM_CBO_PARALLEL=1
export KART_CBO_LLM_EST_CRITICAL_MS=1200
export KART_VALIDATOR_COVERAGE=indexed
export KART_CALIB_MIN_SPEC_S_MS=500
export KART_CALIB_MIN_ADOPT_S_MS=200
export KART_CALIB_UNCERTAINTY_MS=1000
export KART_CBO_LLM_SPECULATE=1
export JAVA_TOOL_OPTIONS=""
export no_proxy="127.0.0.1,localhost,${no_proxy:-}"
export NO_PROXY="127.0.0.1,localhost,${NO_PROXY:-}"

bash scripts/server-patch-refine5.sh | tee "$OUT_ROOT/patch_rerun.log"

python3 experiments/adapters/eval_refine5_synthetic_decisions.py \
  --base-url "$LLM_BASE_URL" --model "$LLM_MODEL" \
  --out-jsonl "$OUT_ROOT/decision_synthetic_eval.jsonl" \
  --out-md "$OUT_ROOT/decision_synthetic_eval.md" \
  | tee "$OUT_ROOT/synthetic_rerun.log"

copy_jsonl() {
  local run_id="$1" dest="$2"
  local j="experiments/results/$run_id/e2e.jsonl"
  [[ -f "$j" ]] || j="experiments/results/$run_id/plan.jsonl"
  [[ -f "$j" ]] && cp -f "$j" "$dest" || : >"$dest"
}

run_e2e() {
  local label="$1" suite="$2" run_id="$3" manifest="$4"
  export KART_EXPERIMENT_MANIFEST="$manifest"
  echo "=== $label $run_id ==="
  ./scripts/bench-e2e.sh --suite "$suite" --run-id "$run_id" --trials 1 --cache warm \
    --arm cbo,cbo-llm-proposal 2>&1 | tee "$OUT_ROOT/${label}_rerun.log"
  copy_jsonl "$run_id" "$OUT_ROOT/${label}.jsonl"
}

run_e2e "ais_e2e" experiments/suites/e2e-ais-llm-overhead-v1.yaml \
  "refine5-ais-e2e-rerun-${STAMP}" ais_v1_ready
run_e2e "td_e2e" experiments/suites/e2e-cbo-llm-overhead-v1.yaml \
  "refine5-td-e2e-rerun-${STAMP}" tdrive_v1_ready

echo "RERUN_DONE OUT_ROOT=$OUT_ROOT"
