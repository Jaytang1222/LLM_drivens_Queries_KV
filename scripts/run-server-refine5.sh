#!/usr/bin/env bash
# comparative_refine_5: synthetic → residual → isolation timing → paired E2E
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
ss -ltn | grep -qE ':11434\s' || { echo "FAIL: ollama not listening"; exit 1; }

MODEL="${OLLAMA_MODEL:-qwen2.5:1.5b-instruct}"
echo "=== warm ollama model=$MODEL ==="
curl -sS -m 120 http://127.0.0.1:11434/api/generate \
  -d "{\"model\":\"$MODEL\",\"prompt\":\"ping\",\"stream\":false,\"options\":{\"num_predict\":8}}" \
  >/tmp/ollama_warm_refine5.json || true

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
export KART_CBO_LLM_BUDGET_MS="${KART_CBO_LLM_BUDGET_MS:-120000}"
export KART_CBO_LLM_MAX_TOKENS="${KART_CBO_LLM_MAX_TOKENS:-16}"
export KART_CBO_LLM_SPECULATE_K=1
export KART_CBO_LLM_PREPARE_MODE=safety_only
export KART_CBO_LLM_PROTOCOL=v9
export KART_CBO_LLM_ALWAYS=1
export KART_CBO_LLM_CBO_PARALLEL=1
export KART_CBO_LLM_EST_CRITICAL_MS="${KART_CBO_LLM_EST_CRITICAL_MS:-1200}"
export KART_VALIDATOR_COVERAGE=indexed
export KART_CALIB_MIN_SPEC_S_MS="${KART_CALIB_MIN_SPEC_S_MS:-500}"
export KART_CALIB_MIN_ADOPT_S_MS="${KART_CALIB_MIN_ADOPT_S_MS:-200}"
export KART_CALIB_UNCERTAINTY_MS="${KART_CALIB_UNCERTAINTY_MS:-1000}"

export no_proxy="127.0.0.1,localhost,${no_proxy:-}"
export NO_PROXY="127.0.0.1,localhost,${NO_PROXY:-}"
export JAVA_TOOL_OPTIONS=""

STAMP=$(date +%Y%m%d-%H%M%S)
OUT_ROOT="experiments/refine_5/refine5-${STAMP}"
mkdir -p "$OUT_ROOT"
{
  echo "build_id=refine5-${STAMP}"
  echo "host=$(hostname)"
  echo "pwd=$PWD"
  echo "time=$(date -Iseconds)"
  echo "git_head=$(git rev-parse HEAD 2>/dev/null || echo unknown)"
  ls -l target/kart.jar || true
  echo "model=$LLM_MODEL"
  echo "prompt=cbo_llm_short_v9"
  echo "calib=calib_loglin_v1"
  echo "uncertainty=$KART_CALIB_UNCERTAINTY_MS"
  echo "min_spec_s=$KART_CALIB_MIN_SPEC_S_MS"
  echo "min_adopt_s=$KART_CALIB_MIN_ADOPT_S_MS"
  echo "cbo_parallel=1"
  echo "validator=indexed"
} | tee "$OUT_ROOT/identity.txt"

bash scripts/server-patch-refine5.sh | tee "$OUT_ROOT/patch.log"

# --- A: synthetic protocol (no DB) ---
python3 experiments/adapters/eval_refine5_synthetic_decisions.py \
  --base-url "$LLM_BASE_URL" \
  --model "$LLM_MODEL" \
  --out-jsonl "$OUT_ROOT/decision_synthetic_eval.jsonl" \
  --out-md "$OUT_ROOT/decision_synthetic_eval.md" \
  | tee "$OUT_ROOT/synthetic.log"

# --- B: saving residual ---
python3 experiments/adapters/eval_refine5_saving_residual.py \
  --measurements experiments/opportunity/opportunity-dev-v1/measurements.jsonl \
  --out-md "$OUT_ROOT/saving_residual_validation.md" \
  | tee "$OUT_ROOT/residual.log"

copy_jsonl() {
  local run_id="$1"
  local dest="$2"
  local d="experiments/results/$run_id"
  local j="$d/e2e.jsonl"
  if [[ ! -f "$j" ]]; then
    j="$d/plan.jsonl"
  fi
  if [[ -f "$j" ]]; then
    cp -f "$j" "$dest"
  else
    echo "WARN: missing jsonl for $run_id" >&2
    : >"$dest"
  fi
}

run_e2e() {
  local label="$1"
  local suite="$2"
  local run_id="$3"
  local manifest="${4:-}"
  local arms="${5:-cbo,cbo-llm-proposal}"
  echo "=== $label run_id=$run_id arms=$arms protocol=${KART_CBO_LLM_PROTOCOL:-} fixed=${KART_FIXED_PLAN_ID:-} par=${KART_FIXED_PLAN_PARALLEL_LLM:-0} ==="
  if [[ -n "$manifest" ]]; then
    export KART_EXPERIMENT_MANIFEST="$manifest"
  else
    unset KART_EXPERIMENT_MANIFEST || true
  fi
  ./scripts/bench-e2e.sh \
    --suite "$suite" \
    --run-id "$run_id" \
    --trials 1 \
    --cache warm \
    --arm "$arms" \
    2>&1 | tee "$OUT_ROOT/${label}.log"
  copy_jsonl "$run_id" "$OUT_ROOT/${label}.jsonl"
}

# --- C: isolation timing audit (real short prompt; 3 alone + 3 par) ---
export KART_CBO_LLM_SPECULATE=0
export KART_FIXED_PLAN_REAL_PROMPT=1
for plan in P_T P_TZ; do
  export KART_FIXED_PLAN_ID="$plan"
  export KART_FIXED_PLAN_PARALLEL_LLM=0
  for t in 1 2 3; do
    run_e2e "iso_${plan}_alone_t${t}" experiments/suites/e2e-ais-fixed-plan-diag.yaml \
      "refine5-iso-${plan}-alone-t${t}-${STAMP}" ais_v1_ready fixed-plan
  done
  export KART_FIXED_PLAN_PARALLEL_LLM=1
  for t in 1 2 3; do
    run_e2e "iso_${plan}_par_t${t}" experiments/suites/e2e-ais-fixed-plan-diag.yaml \
      "refine5-iso-${plan}-par-t${t}-${STAMP}" ais_v1_ready fixed-plan
  done
done
unset KART_FIXED_PLAN_ID || true
unset KART_FIXED_PLAN_PARALLEL_LLM || true

# --- D/E: paired E2E with v9 + CBO-parallel KEEP ---
export KART_CBO_LLM_SPECULATE=1
export KART_CBO_LLM_PROTOCOL=v9
export KART_CBO_LLM_ALWAYS=1
export KART_CBO_LLM_CBO_PARALLEL=1
export KART_VALIDATOR_COVERAGE=indexed

run_e2e "ais_e2e" experiments/suites/e2e-ais-llm-overhead-v1.yaml \
  "refine5-ais-e2e-${STAMP}" ais_v1_ready cbo,cbo-llm-proposal

run_e2e "td_e2e" experiments/suites/e2e-cbo-llm-overhead-v1.yaml \
  "refine5-td-e2e-${STAMP}" tdrive_v1_ready cbo,cbo-llm-proposal

{
  echo "OUT_ROOT=$OUT_ROOT"
  echo "STAMP=$STAMP"
  ls -1 "$OUT_ROOT"/*.jsonl 2>/dev/null || true
} | tee "$OUT_ROOT/paths.txt"

echo "REFINE5_DONE OUT_ROOT=$OUT_ROOT"
