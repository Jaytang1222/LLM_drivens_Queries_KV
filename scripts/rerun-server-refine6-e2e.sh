#!/usr/bin/env bash
# Re-run refine_6 paired E2E with corrected overestimate p95 (full residual quantile).
set -euo pipefail
source ~/.kart_server_env
cd "$KART_DST"
case "$KART_DST" in
  /home/tyq/*) ;;
  *) echo "FAIL: KART_DST=$KART_DST"; exit 1 ;;
esac

export PATH="$HOME/.local/bin:$PATH"
export OLLAMA_HOST=127.0.0.1:11434
export OLLAMA_MODELS="${OLLAMA_MODELS:-$HOME/.ollama}"
MODEL="${OLLAMA_MODEL:-qwen2.5:1.5b-instruct}"
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
export KART_CBO_LLM_MAX_TOKENS=24
export KART_CBO_LLM_SPECULATE_K=1
export KART_CBO_LLM_PREPARE_MODE=safety_only
export KART_CBO_LLM_PROTOCOL=v10
export KART_CBO_LLM_ALWAYS=1
export KART_CBO_LLM_CBO_PARALLEL=1
export KART_CBO_LLM_SPECULATE=1
export KART_VALIDATOR_COVERAGE=indexed
export KART_CALIB_MIN_SPEC_S_MS=500
export KART_CALIB_MIN_ADOPT_S_MS=200
# Full-residual upper quantile (~1128); not positive-subset p95.
export KART_CALIB_OVERESTIMATE_P95_MS=1128
export KART_CALIB_UNCERTAINTY_MS=1128
export KART_PROBE_BUDGET_MS=50
export KART_PROBE_MAX_ROWS=200
export KART_PROBE_SEED=42
export JAVA_TOOL_OPTIONS=""
export no_proxy="127.0.0.1,localhost,${no_proxy:-}"
export NO_PROXY="127.0.0.1,localhost,${NO_PROXY:-}"

STAMP=$(date +%Y%m%d-%H%M%S)
OUT_ROOT="experiments/refine_6/refine6-${STAMP}"
mkdir -p "$OUT_ROOT"
{
  echo "build_id=refine6-${STAMP}"
  echo "host=$(hostname)"
  echo "pwd=$PWD"
  echo "time=$(date -Iseconds)"
  echo "note=e2e_rerun_full_residual_p95"
  ls -l target/kart.jar || true
  echo "model=$LLM_MODEL"
  echo "prompt=cbo_llm_action_v10"
  echo "calib_overest_p95=$KART_CALIB_OVERESTIMATE_P95_MS"
  echo "probe_budget_ms=$KART_PROBE_BUDGET_MS"
  echo "validator=indexed"
} | tee "$OUT_ROOT/identity.txt"

# reuse prior offline artifacts if present
PREV=$(ls -1dt experiments/refine_6/refine6-* 2>/dev/null | grep -v "$STAMP" | head -1 || true)
if [[ -n "${PREV:-}" ]]; then
  for f in feedback_calibration.md feedback_pairs.jsonl llm_action_eval.md llm_action_eval.jsonl calib_suggest.env patch.log; do
    [[ -f "$PREV/$f" ]] && cp -f "$PREV/$f" "$OUT_ROOT/" || true
  done
fi

# refresh calibration md with corrected script if present
if [[ -f experiments/adapters/eval_refine6_feedback_calibration.py ]]; then
  python3 experiments/adapters/eval_refine6_feedback_calibration.py \
    --measurements experiments/opportunity/opportunity-dev-v1/measurements.jsonl \
    --out-dir "$OUT_ROOT" | tee "$OUT_ROOT/calibration.log" || true
fi

copy_jsonl() {
  local run_id="$1" dest="$2"
  local j="experiments/results/$run_id/e2e.jsonl"
  [[ -f "$j" ]] || j="experiments/results/$run_id/plan.jsonl"
  if [[ -f "$j" ]]; then cp -f "$j" "$dest"; else : >"$dest"; fi
}

run_e2e() {
  local label="$1" suite="$2" run_id="$3" manifest="$4"
  export KART_EXPERIMENT_MANIFEST="$manifest"
  echo "=== $label $run_id protocol=$KART_CBO_LLM_PROTOCOL u=$KART_CALIB_UNCERTAINTY_MS ==="
  ./scripts/bench-e2e.sh --suite "$suite" --run-id "$run_id" --trials 1 --cache warm \
    --arm cbo,cbo-llm-proposal 2>&1 | tee "$OUT_ROOT/${label}.log"
  copy_jsonl "$run_id" "$OUT_ROOT/${label}.jsonl"
}

run_e2e "ais_e2e" experiments/suites/e2e-ais-llm-overhead-v1.yaml \
  "refine6-ais-e2e-${STAMP}" ais_v1_ready
run_e2e "td_e2e" experiments/suites/e2e-cbo-llm-overhead-v1.yaml \
  "refine6-td-e2e-${STAMP}" tdrive_v1_ready

{
  echo "OUT_ROOT=$OUT_ROOT"
  echo "STAMP=$STAMP"
  ls -1 "$OUT_ROOT"/*.jsonl 2>/dev/null || true
} | tee "$OUT_ROOT/paths.txt"
echo "REFINE6_DONE OUT_ROOT=$OUT_ROOT"
