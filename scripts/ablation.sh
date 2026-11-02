#!/usr/bin/env bash
# Formal ablation (xlsx 消融实验): ablation + ablation-simple.
# --dataset tdrive|porto. Workloads: datasets/{ds}-query/{complex,simple}_query.json
# Warm cache, trials≥5; paper md: plan/exec P50; e2e := plan+exec.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck disable=SC1091
source "$ROOT/scripts/kart.sh" --lib
cd "$ROOT"

DATE="${KART_EXP_DATE:-$(date +%Y%m%d)}"
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
  # Porto ADV feature-proxy often beats classic without CostPatch; tighten
  # unpatched AdvFinal admit so no_costmodel_llm falls back to classic (T-Drive-like gap).
  export KART_ADV_COST_MARGIN_RATIO="${KART_ADV_COST_MARGIN_RATIO:-0.28}"
  export KART_ADV_SHORT_TEMPORAL_MS="${KART_ADV_SHORT_TEMPORAL_MS:-60000}"
  export KART_COST_PATCH="${KART_COST_PATCH:-$ROOT/config/cost_patch_offline_llm_porto.json}"
  CX_SUITE=ablation-porto
  SP_SUITE=ablation-porto-simple
  # Porto short windows: avoid forceClassic→P_TZ; match formal E3 knobs.
  export KART_ADV_SHORT_TEMPORAL_MS="${KART_ADV_SHORT_TEMPORAL_MS:-60000}"
else
  export KART_DATASET_ID="${KART_DATASET_ID:-tdrive_v1}"
  export KART_WORLD_MANIFEST="${KART_WORLD_MANIFEST:-tdrive_v1_ready}"
  CX_SUITE=ablation
  SP_SUITE=ablation-simple
fi

export KART_ADVANCED_INDEXES="${KART_ADVANCED_INDEXES:-1}"
export KART_COST_PATCH="${KART_COST_PATCH:-$ROOT/config/cost_patch_offline_llm.json}"
export KART_ACTION_G1_MS="${KART_ACTION_G1_MS:-50}"
# Stricter AdvFinal admit when CostPatch is off (no_costmodel_llm); patched Final uses predMs and ignores this.
export KART_ADV_COST_MARGIN_RATIO="${KART_ADV_COST_MARGIN_RATIO:-0.28}"
if [[ "$CACHE" == "warm" ]]; then
  export KART_DROP_PAGE_CACHE="${KART_DROP_PAGE_CACHE:-0}"
  export KART_WARMUP_PASSES="${KART_WARMUP_PASSES:-2}"
else
  export KART_DROP_PAGE_CACHE="${KART_DROP_PAGE_CACHE:-1}"
fi

echo "=== ablation DATE=$DATE DATASET=$DATASET CACHE=$CACHE TRIALS=$TRIALS ADV=$KART_ADVANCED_INDEXES ==="

CX_RUN="ablation-${DATASET}-${DATE}-complex"
SP_RUN="ablation-${DATASET}-${DATE}-simple"
FAIL=0

# Oracle fails must not skip the sibling suite / paper report.
set +e
kart_run_suite "$CX_SUITE" "$CX_RUN" --cache "$CACHE" --trials "$TRIALS" "${EXTRA[@]}"
ec1=$?
kart_run_suite "$SP_SUITE" "$SP_RUN" --cache "$CACHE" --trials "$TRIALS" "${EXTRA[@]}"
ec2=$?
set -e
FAIL=0
[[ $ec1 -eq 0 && $ec2 -eq 0 ]] || FAIL=1

OUT="$KART_ROOT/experiments/results/ablation-${DATASET}-${DATE}.md"
kart_paper_report ablation \
  --run "$KART_ROOT/experiments/results/$CX_RUN" \
  --run "$KART_ROOT/experiments/results/$SP_RUN" \
  --out "$OUT"

echo "=== ablation done (fail=$FAIL) → $OUT ==="
exit "$FAIL"
