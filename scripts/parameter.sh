#!/usr/bin/env bash
# Formal parameter OAT (xlsx 参数实验): param + param-simple.
# --dataset tdrive|porto. Workloads: datasets/{ds}-query/{complex,simple}_query.json
# Porto uses cost_patch_offline_llm_porto_param.json + ADV inject off.
# Warm cache, trials≥5; paper md: per-query median then overall median.
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
  export KART_ADV_COST_MARGIN_RATIO="${KART_ADV_COST_MARGIN_RATIO:-0.28}"
  export KART_ADV_SHORT_TEMPORAL_MS="${KART_ADV_SHORT_TEMPORAL_MS:-60000}"
  # Param OAT only (see datasets/porto-query/PARAM_SENSITIVITY.md):
  # - ADV inject off: budgets bind (inject bypasses max_candidates).
  # - Empty action rules: no offline rewrite short-circuit (beam=1 otherwise may win on e2e).
  # - Param-only CostPatch (includes P_XZTZ); comparative/ablation keep porto.json.
  # Force param-only patch + empty rules (do not inherit comparative porto.json / dual rules).
  export KART_COST_PATCH="$ROOT/config/cost_patch_offline_llm_porto_param.json"
  export KART_ADV_INJECT=0
  export KART_ACTION_RULES="$ROOT/experiments/suites/dual_action_rules_empty.json"
  # Param-only mc bind (default on). Set KART_PARAM_CANDIDATE_BIND=0 to match pre-bind runs (e.g. v5).
  export KART_PARAM_CANDIDATE_BIND="${KART_PARAM_CANDIDATE_BIND:-1}"
  CX_SUITE=param-porto
  SP_SUITE=param-porto-simple
  CX_RUN="parameter-porto-${DATE}-complex"
  SP_RUN="parameter-porto-${DATE}-simple"
  OUT_NAME="parameter-porto-${DATE}.md"
else
  export KART_DATASET_ID="${KART_DATASET_ID:-tdrive_v1}"
  export KART_WORLD_MANIFEST="${KART_WORLD_MANIFEST:-tdrive_v1_ready}"
  CX_SUITE=param
  SP_SUITE=param-simple
  CX_RUN="parameter-${DATE}-complex"
  SP_RUN="parameter-${DATE}-simple"
  OUT_NAME="parameter-${DATE}.md"
fi

# Formal param always needs ADV indexes; do not inherit a stale 0 from the shell.
export KART_ADVANCED_INDEXES=1
export KART_COST_PATCH="${KART_COST_PATCH:-$ROOT/config/cost_patch_offline_llm.json}"
export KART_ACTION_G1_MS="${KART_ACTION_G1_MS:-50}"
export KART_ADV_COST_MARGIN_RATIO="${KART_ADV_COST_MARGIN_RATIO:-0.28}"
if [[ "$CACHE" == "warm" ]]; then
  export KART_DROP_PAGE_CACHE="${KART_DROP_PAGE_CACHE:-0}"
  export KART_WARMUP_PASSES="${KART_WARMUP_PASSES:-2}"
else
  export KART_DROP_PAGE_CACHE="${KART_DROP_PAGE_CACHE:-1}"
fi

echo "=== parameter DATE=$DATE DATASET=$DATASET CACHE=$CACHE TRIALS=$TRIALS ADV=$KART_ADVANCED_INDEXES ==="

FAIL=0
ec1=0
ec2=0
set +e
kart_run_suite "$CX_SUITE" "$CX_RUN" --cache "$CACHE" --trials "$TRIALS" ${EXTRA[@]+"${EXTRA[@]}"}
ec1=$?
# re-source helper in case the long JVM run left the shell without functions
# shellcheck disable=SC1091
source "$ROOT/scripts/kart.sh" --lib
kart_run_suite "$SP_SUITE" "$SP_RUN" --cache "$CACHE" --trials "$TRIALS" ${EXTRA[@]+"${EXTRA[@]}"}
ec2=$?
set -e
FAIL=0
[[ $ec1 -eq 0 && $ec2 -eq 0 ]] || FAIL=1

OUT="$KART_ROOT/experiments/results/$OUT_NAME"
kart_paper_report parameter \
  --run "$KART_ROOT/experiments/results/$CX_RUN" \
  --run "$KART_ROOT/experiments/results/$SP_RUN" \
  --out "$OUT"

echo "=== parameter done (fail=$FAIL) → $OUT ==="
exit "$FAIL"
