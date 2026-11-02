#!/usr/bin/env bash
# Formal comparative (xlsx 对比实验): E1 (NL→IR) + E3 e2e complex/simple + case study md.
# --dataset tdrive|porto. Workloads: datasets/{tdrive,porto}-query/{E1,complex,simple}_query.json
# E1: suite trials=1. E3: warm cache, trials≥5; paper md: plan/exec P50; e2e := plan+exec.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck disable=SC1091
source "$ROOT/scripts/kart.sh" --lib
cd "$ROOT"

DATE="${KART_EXP_DATE:-$(date +%Y%m%d)}"
# Non-E1 formal protocol (override with --cache / --trials / env).
CACHE="${KART_CACHE:-warm}"
TRIALS="${KART_TRIALS:-5}"
DATASET="${KART_DATASET:-tdrive}"
EXTRA=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    --date) DATE="$2"; shift 2 ;;
    --cache) CACHE="$2"; shift 2 ;;
    --trials) TRIALS="$2"; shift 2 ;;
    --dataset) DATASET="$2"; shift 2 ;;
    --limit) EXTRA+=(--limit "$2"); shift 2 ;;
    --arm) EXTRA+=(--arm "$2"); shift 2 ;;
    --factor) EXTRA+=(--factor "$2"); shift 2 ;;
    --keep-artifacts) EXTRA+=(--keep-artifacts); shift ;;
    --allow-unsafe) EXTRA+=(--allow-unsafe); shift ;;
    *) EXTRA+=("$1"); shift ;;
  esac
done

if [[ "$DATASET" == "porto" ]]; then
  export KART_DATASET_ID="${KART_DATASET_ID:-porto_v1}"
  export KART_WORLD_MANIFEST="${KART_WORLD_MANIFEST:-porto_v1_ready}"
  # Match ablation/formal Porto knobs (CostPatch + short-temporal ADV gate).
  export KART_ADV_COST_MARGIN_RATIO="${KART_ADV_COST_MARGIN_RATIO:-0.28}"
  export KART_ADV_SHORT_TEMPORAL_MS="${KART_ADV_SHORT_TEMPORAL_MS:-60000}"
  export KART_COST_PATCH="${KART_COST_PATCH:-$ROOT/config/cost_patch_offline_llm_porto.json}"
  export KART_BAO_MODEL_DIR="${KART_BAO_MODEL_DIR:-$ROOT/experiments/adapters/bao/kart_bao_model_porto}"
  PARSE_SUITE=parse-porto
  E3_CX_SUITE=e2e-porto
  E3_SP_SUITE=e2e-porto-simple
else
  export KART_DATASET_ID="${KART_DATASET_ID:-tdrive_v1}"
  export KART_WORLD_MANIFEST="${KART_WORLD_MANIFEST:-tdrive_v1_ready}"
  PARSE_SUITE=parse
  E3_CX_SUITE=e2e-learned
  E3_SP_SUITE=e2e-learned-simple
fi

export KART_ADVANCED_INDEXES="${KART_ADVANCED_INDEXES:-1}"
export KART_COST_PATCH="${KART_COST_PATCH:-$ROOT/config/cost_patch_offline_llm.json}"
export KART_ACTION_G1_MS="${KART_ACTION_G1_MS:-50}"
export KART_ADV_COST_MARGIN_RATIO="${KART_ADV_COST_MARGIN_RATIO:-0.28}"
# Warm protocol: do not drop OS page cache between trials.
if [[ "$CACHE" == "warm" ]]; then
  export KART_DROP_PAGE_CACHE="${KART_DROP_PAGE_CACHE:-0}"
  export KART_WARMUP_PASSES="${KART_WARMUP_PASSES:-2}"
else
  export KART_DROP_PAGE_CACHE="${KART_DROP_PAGE_CACHE:-1}"
fi

echo "=== comparative DATE=$DATE DATASET=$DATASET E3_CACHE=$CACHE E3_TRIALS=$TRIALS ADV=$KART_ADVANCED_INDEXES ==="

E1_RUN="comparativeE1-${DATASET}-${DATE}-run"
E3_CX="comparativeE3-${DATASET}-${DATE}-complex"
E3_SP="comparativeE3-${DATASET}-${DATE}-simple"
FAIL=0

# E1 NL→IR: suite defaults only (trials=1); do not pass warm/trials.
kart_run_suite "$PARSE_SUITE" "$E1_RUN" "${EXTRA[@]}" || FAIL=1
kart_run_suite "$E3_CX_SUITE" "$E3_CX" --cache "$CACHE" --trials "$TRIALS" "${EXTRA[@]}" || FAIL=1
kart_run_suite "$E3_SP_SUITE" "$E3_SP" --cache "$CACHE" --trials "$TRIALS" "${EXTRA[@]}" || FAIL=1

OUT_E1="$KART_ROOT/experiments/results/comparativeE1-${DATASET}-${DATE}.md"
OUT_E3="$KART_ROOT/experiments/results/comparativeE3-${DATASET}-${DATE}.md"
OUT_CS="$KART_ROOT/experiments/results/case_study-${DATASET}-${DATE}.md"
kart_paper_report e1 --run "$KART_ROOT/experiments/results/$E1_RUN" --out "$OUT_E1"
kart_paper_report e3 \
  --run "$KART_ROOT/experiments/results/$E3_CX" \
  --run "$KART_ROOT/experiments/results/$E3_SP" \
  --out "$OUT_E3"
# Case study: typical query per type + plan_id/index + plan/exec/e2e (md only).
kart_paper_report casestudy \
  --run "$KART_ROOT/experiments/results/$E3_CX" \
  --run "$KART_ROOT/experiments/results/$E3_SP" \
  --out "$OUT_CS"

echo "=== comparative done (fail=$FAIL) ==="
echo "  $OUT_E1"
echo "  $OUT_E3"
echo "  $OUT_CS"
echo "  raw: experiments/results/{$E1_RUN,$E3_CX,$E3_SP}/"
exit "$FAIL"
