#!/usr/bin/env bash
# comparative_refine_2: patch → prepare_breakdown (full) → paired E2E (safety_only).
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

# Warm model once (Phase E: distinguish cold vs steady).
MODEL="${OLLAMA_MODEL:-qwen2.5:1.5b-instruct}"
echo "=== warm ollama model=$MODEL ==="
curl -sS -m 120 http://127.0.0.1:11434/api/generate \
  -d "{\"model\":\"$MODEL\",\"prompt\":\"ping\",\"stream\":false,\"options\":{\"num_predict\":8}}" \
  >/tmp/ollama_warm_refine2.json || true
python3 - <<'PY'
import json
from pathlib import Path
p=Path("/tmp/ollama_warm_refine2.json")
if p.exists():
  try:
    j=json.loads(p.read_text())
    print("warm_ok total_ms=", j.get("total_duration",0)/1e6)
  except Exception as e:
    print("warm_parse_err", e)
else:
  print("warm_missing")
PY

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
export KART_CBO_LLM_SPECULATE="${KART_CBO_LLM_SPECULATE:-1}"
export KART_CBO_LLM_SPECULATE_K="${KART_CBO_LLM_SPECULATE_K:-1}"
export KART_CBO_LLM_SPECULATE_PT="$KART_CBO_LLM_SPECULATE"

export no_proxy="127.0.0.1,localhost,${no_proxy:-}"
export NO_PROXY="127.0.0.1,localhost,${NO_PROXY:-}"
_jto="${JAVA_TOOL_OPTIONS:-}"
_jto="$(printf '%s' "$_jto" | sed -E 's/-Dhttps\.proxyHost=[^ ]*//g; s/-Dhttps\.proxyPort=[^ ]*//g; s/-Dhttp\.proxyHost=[^ ]*//g; s/-Dhttp\.proxyPort=[^ ]*//g; s/  +/ /g; s/^ //; s/ $//')"
export JAVA_TOOL_OPTIONS="${_jto}"

STAMP=$(date +%Y%m%d-%H%M%S)
OUT_ROOT="experiments/refine_2/refine2-${STAMP}"
mkdir -p "$OUT_ROOT"
{
  echo "build_id=refine2-${STAMP}"
  echo "host=$(hostname)"
  echo "pwd=$PWD"
  echo "time=$(date -Iseconds)"
  echo "git_head=$(git rev-parse HEAD 2>/dev/null || echo unknown)"
  ls -l target/kart.jar || true
  echo "model=$LLM_MODEL"
  echo "prompt=cbo_llm_independent_v7"
  echo "speculate=$KART_CBO_LLM_SPECULATE k=$KART_CBO_LLM_SPECULATE_K"
  echo "max_tokens=$KART_CBO_LLM_MAX_TOKENS"
} | tee "$OUT_ROOT/identity.txt"

bash scripts/server-patch-refine2.sh | tee "$OUT_ROOT/patch.log"

# --- Phase A: prepare_breakdown with FULL prepare (instrumentation) ---
echo "=== Phase A: AIS prepare_breakdown FULL ==="
export KART_CBO_LLM_PREPARE_MODE=full
export KART_EXPERIMENT_MANIFEST=ais_v1_ready
BR_ID="refine2-prepare-full-${STAMP}"
./scripts/bench-e2e.sh \
  --suite experiments/suites/e2e-ais-llm-overhead-v1.yaml \
  --run-id "$BR_ID" \
  --trials 1 \
  --cache warm \
  --arm cbo,cbo-llm-proposal \
  2>&1 | tee "$OUT_ROOT/prepare_full.log" || true
BR_DIR="experiments/results/$BR_ID"
BR_JSONL="$BR_DIR/e2e.jsonl"
[[ -f "$BR_JSONL" ]] || BR_JSONL="$BR_DIR/plan.jsonl"
if [[ -f "$BR_JSONL" ]]; then
  cp -f "$BR_JSONL" "$OUT_ROOT/prepare_full_ais.jsonl"
fi

# --- Phase B+C: paired E2E with safety_only prepare ---
echo "=== Phase B/C: T-Drive E2E safety_only ==="
export KART_CBO_LLM_PREPARE_MODE=safety_only
unset KART_EXPERIMENT_MANIFEST || true
TD_ID="refine2-td-e2e-${STAMP}"
./scripts/bench-e2e.sh \
  --suite experiments/suites/e2e-cbo-llm-overhead-v1.yaml \
  --run-id "$TD_ID" \
  --trials 1 \
  --cache warm \
  --arm cbo,cbo-llm-proposal \
  2>&1 | tee "$OUT_ROOT/td_e2e.log"
TD_DIR="experiments/results/$TD_ID"
TD_JSONL="$TD_DIR/e2e.jsonl"
[[ -f "$TD_JSONL" ]] || TD_JSONL="$TD_DIR/plan.jsonl"
cp -f "$TD_JSONL" "$OUT_ROOT/td_e2e.jsonl"

echo "=== Phase B/C: AIS E2E safety_only ==="
export KART_EXPERIMENT_MANIFEST=ais_v1_ready
AIS_ID="refine2-ais-e2e-${STAMP}"
./scripts/bench-e2e.sh \
  --suite experiments/suites/e2e-ais-llm-overhead-v1.yaml \
  --run-id "$AIS_ID" \
  --trials 1 \
  --cache warm \
  --arm cbo,cbo-llm-proposal \
  2>&1 | tee "$OUT_ROOT/ais_e2e.log"
AIS_DIR="experiments/results/$AIS_ID"
AIS_JSONL="$AIS_DIR/e2e.jsonl"
[[ -f "$AIS_JSONL" ]] || AIS_JSONL="$AIS_DIR/plan.jsonl"
cp -f "$AIS_JSONL" "$OUT_ROOT/ais_e2e.jsonl"

{
  echo "PREPARE_FULL=$BR_ID"
  echo "TD_E2E=$TD_ID"
  echo "AIS_E2E=$AIS_ID"
  echo "OUT_ROOT=$OUT_ROOT"
  echo "MODEL=$LLM_MODEL"
  echo "PREPARE_MODE_E2E=safety_only"
} | tee "$OUT_ROOT/paths.txt"

echo "REFINE2_DONE out=$OUT_ROOT"
