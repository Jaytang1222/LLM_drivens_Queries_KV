#!/usr/bin/env bash
# CBO opportunity census (development diagnostic; no LLM).
# See docs/cbo_opportunity_census_guide.md
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
# shellcheck disable=SC1091
source scripts/kart-env.sh
kart_ensure_jar

POOL="experiments/workloads/bound_ir_advantage_v2.json"
EXTRA="experiments/workloads/bound_ir_cbo_llm_pilot_v1.candidates.json"
ORACLE=""
EXCLUDE="experiments/workloads/bound_ir_holdout_v2_test.json"
RUN_ID="opportunity-dev-$(date +%Y%m%d-%H%M%S)"
CACHE="warm"
MANIFEST="tdrive_v1_ready"
INITIAL_TRIALS=1
REPEAT_TRIALS=3
MAX_EXEC_MS=60000
MAX_WALL_MINUTES=180
REPEAT_GAIN_THRESHOLD_MS=""
HYBRID_EXTRA_PLAN_MS=""
LIMIT=""
EXTRA_FLAGS=()

while [[ $# -gt 0 ]]; do
  case "$1" in
    --pool) POOL="$2"; shift 2 ;;
    --extra-pool) EXTRA="$2"; shift 2 ;;
    --no-extra-pool) EXTRA=""; shift ;;
    --oracle) ORACLE="$2"; shift 2 ;;
    --exclude) EXCLUDE="$2"; shift 2 ;;
    --run-id) RUN_ID="$2"; shift 2 ;;
    --cache) CACHE="$2"; shift 2 ;;
    --manifest) MANIFEST="$2"; shift 2 ;;
    --initial-trials) INITIAL_TRIALS="$2"; shift 2 ;;
    --repeat-trials) REPEAT_TRIALS="$2"; shift 2 ;;
    --max-exec-ms) MAX_EXEC_MS="$2"; shift 2 ;;
    --max-wall-minutes) MAX_WALL_MINUTES="$2"; shift 2 ;;
    --repeat-gain-threshold-ms) REPEAT_GAIN_THRESHOLD_MS="$2"; shift 2 ;;
    --hybrid-extra-plan-ms) HYBRID_EXTRA_PLAN_MS="$2"; shift 2 ;;
    --limit) LIMIT="$2"; shift 2 ;;
    --search-only|--include-fullscan-exec)
      EXTRA_FLAGS+=("$1"); shift ;;
    *)
      echo "Unknown arg: $1" >&2
      exit 2
      ;;
  esac
done

ARGS=(
  opportunity-census
  --pool "$POOL"
  --exclude "$EXCLUDE"
  --run-id "$RUN_ID"
  --cache "$CACHE"
  --manifest "$MANIFEST"
  --initial-trials "$INITIAL_TRIALS"
  --repeat-trials "$REPEAT_TRIALS"
  --max-exec-ms "$MAX_EXEC_MS"
  --max-wall-minutes "$MAX_WALL_MINUTES"
)
if [[ -n "$EXTRA" ]]; then
  ARGS+=(--extra-pool "$EXTRA")
fi
if [[ -n "$ORACLE" ]]; then
  ARGS+=(--oracle "$ORACLE")
fi
if [[ -n "$REPEAT_GAIN_THRESHOLD_MS" ]]; then
  ARGS+=(--repeat-gain-threshold-ms "$REPEAT_GAIN_THRESHOLD_MS")
fi
if [[ -n "$HYBRID_EXTRA_PLAN_MS" ]]; then
  ARGS+=(--hybrid-extra-plan-ms "$HYBRID_EXTRA_PLAN_MS")
fi
if [[ -n "$LIMIT" ]]; then
  ARGS+=(--limit "$LIMIT")
fi
ARGS+=("${EXTRA_FLAGS[@]}")

echo "Starting opportunity census run_id=$RUN_ID"
kart_java "${ARGS[@]}"

OUT="experiments/opportunity/${RUN_ID}"
python3 experiments/adapters/summarize_opportunity_census.py --run "$OUT"
echo "Census outputs → $OUT"
