#!/usr/bin/env bash
# Formal Scalability (xlsx Scalability / GATE_v2).
# TDrive scales: x1 x10 x50 x100 → Data Scale columns.
# Porto scales:  s100 s50 s25 s10 → SUB Data Scale ×1/×0.5/×0.25/×0.1.
# Env: experiments/workloads/scalability/formal_env_v2.sh (CostPatch + ADV primary).
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck disable=SC1091
source "$ROOT/scripts/kart.sh" --lib
cd "$ROOT"

DATE="${KART_EXP_DATE:-$(date +%Y%m%d)}"
CACHE="${KART_CACHE:-warm}"
TRIALS="${KART_TRIALS:-5}"
DATASET="${KART_DATASET:-both}" # tdrive | porto | both
SCALES="${KART_SCALES:-}"       # optional CSV override, e.g. x1,x10 or s100,s50
SCORE="${KART_SCALE_SCORE:-1}"  # 1=run score_scalability_gate.py per scale
EXTRA=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    --date) DATE="$2"; shift 2 ;;
    --cache) CACHE="$2"; shift 2 ;;
    --trials) TRIALS="$2"; shift 2 ;;
    --dataset) DATASET="$2"; shift 2 ;;
    --scales) SCALES="$2"; shift 2 ;;
    --no-score) SCORE=0; shift ;;
    --limit) EXTRA+=(--limit "$2"); shift 2 ;;
    --arm) EXTRA+=(--arm "$2"); shift 2 ;;
    --fix) EXTRA+=(--fix "$2"); shift 2 ;;
    --keep-artifacts) EXTRA+=(--keep-artifacts); shift ;;
    --allow-unsafe) EXTRA+=(--allow-unsafe); shift ;;
    *) EXTRA+=("$1"); shift ;;
  esac
done

# shellcheck disable=SC1091
source "$ROOT/experiments/workloads/scalability/formal_env_v2.sh"
# formal_env sets KART_COST_PATCH relative to PWD
export KART_COST_PATCH="${KART_COST_PATCH:-$ROOT/config/cost_patch_scalability.json}"

if [[ "$CACHE" == "warm" ]]; then
  export KART_DROP_PAGE_CACHE="${KART_DROP_PAGE_CACHE:-0}"
  export KART_WARMUP_PASSES="${KART_WARMUP_PASSES:-2}"
else
  export KART_DROP_PAGE_CACHE="${KART_DROP_PAGE_CACHE:-1}"
fi

tdrive_scales_default=(x1 x10 x50 x100)
porto_scales_default=(s100 s50 s25 s10)

IFS=',' read -r -a SCALE_ARR <<<"${SCALES}"

pick_scales() {
  local ds="$1"
  if [[ -n "${SCALES}" ]]; then
    echo "${SCALE_ARR[@]}"
    return
  fi
  if [[ "$ds" == "porto" ]]; then
    echo "${porto_scales_default[@]}"
  else
    echo "${tdrive_scales_default[@]}"
  fi
}

run_scale() {
  local ds="$1" scale="$2"
  local suite run_id gate_out e2e
  # Suite id in src/main/resources/experiments/suites.yaml (answers embedded in *_v2.json).
  suite="scalability-${ds}-${scale}-v2"
  if [[ "$ds" == "porto" ]]; then
    case "$scale" in
      s100) export KART_WORLD_MANIFEST=porto_v1_ready; export KART_DATASET_ID=porto_v1 ;;
      s50)  export KART_WORLD_MANIFEST=porto_s50_ready; export KART_DATASET_ID=porto_s50 ;;
      s25)  export KART_WORLD_MANIFEST=porto_s25_ready; export KART_DATASET_ID=porto_s25 ;;
      s10)  export KART_WORLD_MANIFEST=porto_s10_ready; export KART_DATASET_ID=porto_s10 ;;
      *) echo "bad porto scale $scale" >&2; return 2 ;;
    esac
    export KART_ADV_SHORT_TEMPORAL_MS="${KART_ADV_SHORT_TEMPORAL_MS:-60000}"
    export KART_BAO_MODEL_DIR="${KART_BAO_MODEL_DIR:-$ROOT/experiments/adapters/bao/kart_bao_model_porto}"
  else
    case "$scale" in
      x1)   export KART_WORLD_MANIFEST=tdrive_v1_ready; export KART_DATASET_ID=tdrive_v1 ;;
      x10)  export KART_WORLD_MANIFEST=tdrive_x10_ready; export KART_DATASET_ID=tdrive_x10 ;;
      x50)  export KART_WORLD_MANIFEST=tdrive_x50_ready; export KART_DATASET_ID=tdrive_x50 ;;
      x100) export KART_WORLD_MANIFEST=tdrive_x100_ready; export KART_DATASET_ID=tdrive_x100 ;;
      *) echo "bad tdrive scale $scale" >&2; return 2 ;;
    esac
  fi
  run_id="scalability-${ds}-${scale}-v2-${DATE}"
  echo "=== scalability DATASET=$ds SCALE=$scale suite=$suite run_id=$run_id ==="
  kart_run_suite "$suite" "$run_id" --cache "$CACHE" --trials "$TRIALS" ${EXTRA[@]+"${EXTRA[@]}"}
  local ec=$?
  if [[ "$SCORE" == "1" && $ec -eq 0 ]]; then
    e2e="$ROOT/experiments/results/${run_id}/e2e.jsonl"
    gate_out="$ROOT/experiments/workloads/scalability/${ds}_${scale}_v2_gate.json"
    python3 "$ROOT/experiments/adapters/score_scalability_gate.py" \
      --e2e "$e2e" --out "$gate_out" --fs-geo-min 1.5 || ec=$?
    echo "  gate → $gate_out"
  fi
  return $ec
}

run_dataset() {
  local ds="$1"
  local fail=0 scale
  # shellcheck disable=SC2207
  local scales=($(pick_scales "$ds"))
  for scale in "${scales[@]}"; do
    [[ -z "$scale" ]] && continue
    set +e
    run_scale "$ds" "$scale"
    local ec=$?
    set -e
    [[ $ec -eq 0 ]] || fail=1
  done
  return $fail
}

echo "=== scalability DATE=$DATE DATASET=$DATASET CACHE=$CACHE TRIALS=$TRIALS ==="
FAIL=0
set +e
case "$DATASET" in
  tdrive) run_dataset tdrive; FAIL=$? ;;
  porto) run_dataset porto; FAIL=$? ;;
  both|all)
    run_dataset tdrive; e1=$?
    run_dataset porto; e2=$?
    [[ $e1 -eq 0 && $e2 -eq 0 ]] || FAIL=1
    ;;
  *) echo "unknown --dataset $DATASET (tdrive|porto|both)" >&2; exit 2 ;;
esac
set -e

echo "=== scalability done (fail=$FAIL) ==="
exit "$FAIL"
