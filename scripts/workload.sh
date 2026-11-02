#!/usr/bin/env bash
# Formal Workload (xlsx Workload): SRQ dist×range on frozen workload_query.json.
# Suite ids (suites.yaml): workload-dist-{tdrive,porto} (skewed+gaussian+uniform in one file).
# Warm cache, trials≥5; summarize → experiments/results/comparativeWorkload-DATE.md
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck disable=SC1091
source "$ROOT/scripts/kart.sh" --lib
cd "$ROOT"

DATE="${KART_EXP_DATE:-$(date +%Y%m%d)}"
CACHE="${KART_CACHE:-warm}"
TRIALS="${KART_TRIALS:-5}"
DATASET="${KART_DATASET:-both}" # tdrive | porto | both
EXTRA=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    --date) DATE="$2"; shift 2 ;;
    --cache) CACHE="$2"; shift 2 ;;
    --trials) TRIALS="$2"; shift 2 ;;
    --dataset) DATASET="$2"; shift 2 ;;
    --limit) EXTRA+=(--limit "$2"); shift 2 ;;
    --arm) EXTRA+=(--arm "$2"); shift 2 ;;
    --fix) EXTRA+=(--fix "$2"); shift 2 ;;
    --keep-artifacts) EXTRA+=(--keep-artifacts); shift ;;
    --allow-unsafe) EXTRA+=(--allow-unsafe); shift ;;
    --fill-xlsx) FILL_XLSX=1; shift ;;
    *) EXTRA+=("$1"); shift ;;
  esac
done
FILL_XLSX="${FILL_XLSX:-0}"

export KART_ADVANCED_INDEXES="${KART_ADVANCED_INDEXES:-1}"
export KART_ADV_FIXED_PRIMARY="${KART_ADV_FIXED_PRIMARY:-1}"
export KART_ACTION_G1_MS="${KART_ACTION_G1_MS:-50}"
export KART_ADV_COST_MARGIN_RATIO="${KART_ADV_COST_MARGIN_RATIO:-0.28}"
if [[ "$CACHE" == "warm" ]]; then
  export KART_DROP_PAGE_CACHE="${KART_DROP_PAGE_CACHE:-0}"
  export KART_WARMUP_PASSES="${KART_WARMUP_PASSES:-2}"
else
  export KART_DROP_PAGE_CACHE="${KART_DROP_PAGE_CACHE:-1}"
fi

run_one() {
  local ds="$1"
  local suite run_id
  if [[ "$ds" == "porto" ]]; then
    export KART_DATASET_ID="${KART_DATASET_ID:-porto_v1}"
    export KART_WORLD_MANIFEST="${KART_WORLD_MANIFEST:-porto_v1_ready}"
    export KART_ADV_SHORT_TEMPORAL_MS="${KART_ADV_SHORT_TEMPORAL_MS:-60000}"
    export KART_COST_PATCH="${KART_COST_PATCH:-$ROOT/config/cost_patch_offline_llm_porto.json}"
    export KART_BAO_MODEL_DIR="${KART_BAO_MODEL_DIR:-$ROOT/experiments/adapters/bao/kart_bao_model_porto}"
    suite="workload-dist-porto"
  else
    export KART_DATASET_ID="${KART_DATASET_ID:-tdrive_v1}"
    export KART_WORLD_MANIFEST="${KART_WORLD_MANIFEST:-tdrive_v1_ready}"
    export KART_COST_PATCH="${KART_COST_PATCH:-$ROOT/config/cost_patch_offline_llm.json}"
    suite="workload-dist-tdrive"
  fi
  run_id="workload-dist-${ds}-${DATE}"
  echo "=== workload DATASET=$ds suite=$suite run_id=$run_id ==="
  kart_run_suite "$suite" "$run_id" --cache "$CACHE" --trials "$TRIALS" ${EXTRA[@]+"${EXTRA[@]}"}
}

FAIL=0
set +e
case "$DATASET" in
  tdrive) run_one tdrive; FAIL=$? ;;
  porto) run_one porto; FAIL=$? ;;
  both|all)
    run_one tdrive; e1=$?
    run_one porto; e2=$?
    [[ $e1 -eq 0 && $e2 -eq 0 ]] || FAIL=1
    ;;
  *) echo "unknown --dataset $DATASET (tdrive|porto|both)" >&2; exit 2 ;;
esac
set -e

SUM_ARGS=(--date "$DATE" --results-dir "$ROOT/experiments/results")
if [[ "$FILL_XLSX" == "1" ]]; then
  SUM_ARGS+=(--fill-xlsx --xlsx "$ROOT/experiments/results/experiment_design.xlsx")
fi
python3 "$ROOT/experiments/adapters/summarize_workload_dist.py" "${SUM_ARGS[@]}"

echo "=== workload done (fail=$FAIL) → experiments/results/comparativeWorkload-${DATE}.md ==="
exit "$FAIL"
