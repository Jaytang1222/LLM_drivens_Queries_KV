#!/usr/bin/env bash
# Isolated jars: never modifies target/kart.jar or the frozen refine_6 evidence.
set -euo pipefail
source ~/.kart_server_env
cd "$KART_DST"
case "$KART_DST" in /home/tyq/*) ;; *) exit 2;; esac
MODE=${1:-after}
TRIALS=${2:-1}
case "$MODE" in before|after) ;; *) echo 'Expected before or after' >&2; exit 2;; esac
DEFAULT_MODEL=qwen2.5:1.5b-instruct
if [[ "$MODE" == after ]]; then DEFAULT_MODEL=kart-refine7-1p5b-t2; fi
STAMP=$(date +%Y%m%d-%H%M%S)
ROOT="$KART_DST/experiments/refine_7"
mkdir -p "$ROOT"
export LLM_PROVIDER=ollama LLM_BASE_URL=http://127.0.0.1:11434/v1 LLM_MODEL="${REFINE7_MODEL:-$DEFAULT_MODEL}" LLM_API_KEY=ollama LLM_JSON_MODE=false
# Use a task-local overlay; no existing environment files are overwritten.
OVERLAY="$ROOT/llm.env"
printf '%s\n' 'LLM_PROVIDER=ollama' 'LLM_BASE_URL=http://127.0.0.1:11434/v1' "LLM_MODEL=$LLM_MODEL" 'LLM_API_KEY=ollama' 'LLM_JSON_MODE=false' > "$OVERLAY"
export LLM_ENV_FILE="$OVERLAY"
export KART_CBO_LLM_ALWAYS=1 KART_CBO_LLM_CBO_PARALLEL=1 KART_CBO_LLM_SPECULATE=1 KART_CBO_LLM_SPECULATE_K=1
export KART_CBO_LLM_PREPARE_MODE=safety_only KART_VALIDATOR_COVERAGE=indexed
export KART_CALIB_MIN_SPEC_S_MS=500 KART_CALIB_MIN_ADOPT_S_MS=200 KART_CALIB_UNCERTAINTY_MS=1128
export KART_CBO_LLM_BUDGET_MS=120000 KART_CBO_LLM_MAX_TOKENS=24
export no_proxy=127.0.0.1,localhost NO_PROXY=127.0.0.1,localhost JAVA_TOOL_OPTIONS=''
if [[ "$MODE" == before ]]; then
  export KART_JAR="$ROOT/before.jar" KART_CBO_LLM_PROTOCOL=v10
  unset KART_ONLINE_BENEFIT_MODEL
else
  export KART_JAR="$ROOT/after.jar" KART_CBO_LLM_PROTOCOL=v11
  export KART_ONLINE_BENEFIT_MODEL="$ROOT/model_verified_linear.json"
fi
OUT="$ROOT/${MODE}-${STAMP}"
mkdir -p "$OUT"
sha256sum "$KART_JAR" > "$OUT/build.sha256"
if [[ "$MODE" != before ]]; then cp "$KART_ONLINE_BENEFIT_MODEL" "$OUT/benefit-model.json"; fi
printf 'mode=%s\ntrials=%s\nmodel=%s\nprotocol=%s\n' "$MODE" "$TRIALS" "$LLM_MODEL" "$KART_CBO_LLM_PROTOCOL" > "$OUT/config.txt"
curl -fsS --max-time 30 http://127.0.0.1:11434/api/show -d "{\"model\":\"$LLM_MODEL\"}" > "$OUT/model-show.json"
curl -fsS --max-time 120 http://127.0.0.1:11434/api/generate -d "{\"model\":\"$LLM_MODEL\",\"prompt\":\"Reply K\",\"stream\":false,\"keep_alive\":\"30m\",\"options\":{\"num_predict\":2}}" > "$OUT/warm.json"
for dataset in ais td; do
  if [[ "$dataset" == ais ]]; then
    export KART_EXPERIMENT_MANIFEST=ais_v1_ready
    SUITE=experiments/suites/e2e-ais-llm-overhead-v1.yaml
  else
    export KART_EXPERIMENT_MANIFEST=tdrive_v1_ready
    SUITE=experiments/suites/e2e-cbo-llm-overhead-v1.yaml
  fi
  RUN="refine7-${MODE}-${dataset}-${STAMP}"
  bash scripts/bench-e2e.sh --suite "$SUITE" --run-id "$RUN" --trials "$TRIALS" --cache warm --arm cbo,cbo-llm-proposal > "$OUT/$dataset.log" 2>&1
  cp "experiments/results/$RUN/e2e.jsonl" "$OUT/$dataset.jsonl"
done
echo "DONE $OUT"
