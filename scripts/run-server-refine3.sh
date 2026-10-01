#!/usr/bin/env bash
# comparative_refine_3: patch → legacy/indexed scaling → LLM isolation → hint ablation → paired E2E
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
  >/tmp/ollama_warm_refine3.json || true

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
export KART_CBO_LLM_MAX_TOKENS="${KART_CBO_LLM_MAX_TOKENS:-96}"
export KART_CBO_LLM_SPECULATE_K=1
export KART_CBO_LLM_PREPARE_MODE=safety_only

export no_proxy="127.0.0.1,localhost,${no_proxy:-}"
export NO_PROXY="127.0.0.1,localhost,${NO_PROXY:-}"
_jto="${JAVA_TOOL_OPTIONS:-}"
_jto="$(printf '%s' "$_jto" | sed -E 's/-Dhttps\.proxyHost=[^ ]*//g; s/-Dhttps\.proxyPort=[^ ]*//g; s/-Dhttp\.proxyHost=[^ ]*//g; s/-Dhttp\.proxyPort=[^ ]*//g; s/  +/ /g; s/^ //; s/ $//')"
export JAVA_TOOL_OPTIONS="${_jto}"

STAMP=$(date +%Y%m%d-%H%M%S)
OUT_ROOT="experiments/refine_3/refine3-${STAMP}"
mkdir -p "$OUT_ROOT"
{
  echo "build_id=refine3-${STAMP}"
  echo "host=$(hostname)"
  echo "pwd=$PWD"
  echo "time=$(date -Iseconds)"
  echo "git_head=$(git rev-parse HEAD 2>/dev/null || echo unknown)"
  ls -l target/kart.jar || true
  echo "model=$LLM_MODEL"
  echo "prompt=cbo_llm_independent_v7"
  echo "validator_default=indexed"
} | tee "$OUT_ROOT/identity.txt"

bash scripts/server-patch-refine3.sh | tee "$OUT_ROOT/patch.log"

copy_jsonl() {
  local run_id="$1"
  local dest="$2"
  local d="experiments/results/$run_id"
  local j="$d/e2e.jsonl"
  [[ -f "$j" ]] || j="$d/plan.jsonl"
  cp -f "$j" "$dest"
}

run_e2e() {
  local label="$1"
  local suite="$2"
  local run_id="$3"
  local manifest="${4:-}"
  echo "=== $label run_id=$run_id cov=${KART_VALIDATOR_COVERAGE:-indexed} spec=${KART_CBO_LLM_SPECULATE:-1} hint=${KART_CBO_LLM_HINT:-on} ==="
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
    --arm cbo,cbo-llm-proposal \
    2>&1 | tee "$OUT_ROOT/${label}.log"
  copy_jsonl "$run_id" "$OUT_ROOT/${label}.jsonl"
}

# --- A/B: legacy vs indexed on AIS (safety_only prepare; validator is the variable) ---
export KART_CBO_LLM_SPECULATE=1
export KART_CBO_LLM_HINT=on
export KART_VALIDATOR_COVERAGE=legacy
run_e2e "ais_legacy" experiments/suites/e2e-ais-llm-overhead-v1.yaml \
  "refine3-ais-legacy-${STAMP}" ais_v1_ready

export KART_VALIDATOR_COVERAGE=indexed
run_e2e "ais_indexed" experiments/suites/e2e-ais-llm-overhead-v1.yaml \
  "refine3-ais-indexed-${STAMP}" ais_v1_ready

# --- C: LLM isolation on AIS ---
# 1) LLM alone (no speculative validate)
export KART_CBO_LLM_SPECULATE=0
export KART_VALIDATOR_COVERAGE=indexed
run_e2e "ais_llm_alone" experiments/suites/e2e-ais-llm-overhead-v1.yaml \
  "refine3-ais-llm-alone-${STAMP}" ais_v1_ready

# 2) LLM + legacy validate parallel (already have ais_legacy)
# 3) LLM + indexed parallel (already have ais_indexed)
cp -f "$OUT_ROOT/ais_legacy.jsonl" "$OUT_ROOT/ais_llm_par_legacy.jsonl"
cp -f "$OUT_ROOT/ais_indexed.jsonl" "$OUT_ROOT/ais_llm_par_indexed.jsonl"

# --- D: hint ablation (indexed, speculate on) ---
export KART_CBO_LLM_SPECULATE=1
export KART_VALIDATOR_COVERAGE=indexed
export KART_CBO_LLM_HINT=off
run_e2e "ais_hint_off" experiments/suites/e2e-ais-llm-overhead-v1.yaml \
  "refine3-ais-hint-off-${STAMP}" ais_v1_ready

export KART_CBO_LLM_HINT=shuffle
run_e2e "ais_hint_shuffle" experiments/suites/e2e-ais-llm-overhead-v1.yaml \
  "refine3-ais-hint-shuffle-${STAMP}" ais_v1_ready

# --- E: fair paired TD + AIS with new validator (indexed, hint on, speculate on) ---
export KART_CBO_LLM_HINT=on
export KART_CBO_LLM_SPECULATE=1
export KART_VALIDATOR_COVERAGE=indexed
run_e2e "td_e2e" experiments/suites/e2e-cbo-llm-overhead-v1.yaml \
  "refine3-td-e2e-${STAMP}" ""
# reuse ais_indexed as primary after E2E for AIS
cp -f "$OUT_ROOT/ais_indexed.jsonl" "$OUT_ROOT/ais_e2e.jsonl"
cp -f "$OUT_ROOT/td_e2e.jsonl" "$OUT_ROOT/td_paired.jsonl" 2>/dev/null || true

{
  echo "STAMP=$STAMP"
  echo "OUT_ROOT=$OUT_ROOT"
  echo "MODEL=$LLM_MODEL"
  echo "AIS_LEGACY=refine3-ais-legacy-${STAMP}"
  echo "AIS_INDEXED=refine3-ais-indexed-${STAMP}"
  echo "AIS_LLM_ALONE=refine3-ais-llm-alone-${STAMP}"
  echo "AIS_HINT_OFF=refine3-ais-hint-off-${STAMP}"
  echo "AIS_HINT_SHUFFLE=refine3-ais-hint-shuffle-${STAMP}"
  echo "TD_E2E=refine3-td-e2e-${STAMP}"
} | tee "$OUT_ROOT/paths.txt"

echo "REFINE3_DONE out=$OUT_ROOT"
