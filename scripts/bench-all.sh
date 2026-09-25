#!/usr/bin/env bash
# Comparative: E1 → E2 → E3
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck disable=SC1091
source "$ROOT/scripts/kart-env.sh"
cd "$ROOT"

RUN_ID=""
SKIP_PARSE=0
SKIP_PLAN=0
SKIP_E2E=0
CONTINUE=0
PARSE_ARM=()
PLAN_ARM=()
E2E_ARM=()
PARSE_WL=()
PLAN_WL=()
E2E_WL=()
CACHE="cold"
FORWARD=()

while [[ $# -gt 0 ]]; do
  case "$1" in
    --run-id) RUN_ID="$2"; shift 2 ;;
    --skip-parse) SKIP_PARSE=1; shift ;;
    --skip-plan) SKIP_PLAN=1; shift ;;
    --skip-e2e) SKIP_E2E=1; shift ;;
    --continue-on-error) CONTINUE=1; shift ;;
    --parse-arm) PARSE_ARM=(--arm "$2"); shift 2 ;;
    --plan-arm) PLAN_ARM=(--arm "$2"); shift 2 ;;
    --e2e-arm) E2E_ARM=(--arm "$2"); shift 2 ;;
    --parse-workload) PARSE_WL=(--workload "$2"); shift 2 ;;
    --plan-workload) PLAN_WL=(--workload "$2"); shift 2 ;;
    --e2e-workload) E2E_WL=(--workload "$2"); shift 2 ;;
    --cache) CACHE="$2"; shift 2 ;;
    --skip-compare|--skip-ablation|--skip-param) shift ;;
    *) FORWARD+=("$1"); shift ;;
  esac
done

[[ -z "$RUN_ID" ]] && RUN_ID="bench-all-$(date +%Y%m%d-%H%M%S)"

run_step() {
  local name="$1"; shift
  echo "=== $name ==="
  if bash "$@"; then
    return 0
  fi
  local ec=$?
  if [[ "$CONTINUE" -eq 1 ]]; then
    echo "WARN: $name failed exit=$ec (continuing)" >&2
    return 0
  fi
  return "$ec"
}

echo "=== bench-all run_id=$RUN_ID ==="
if [[ "$SKIP_PARSE" -eq 0 ]]; then
  run_step parse "$ROOT/scripts/bench-parse.sh" --run-id "$RUN_ID" "${PARSE_ARM[@]}" "${PARSE_WL[@]}" "${FORWARD[@]}"
fi
if [[ "$SKIP_PLAN" -eq 0 ]]; then
  run_step plan "$ROOT/scripts/bench-plan.sh" --run-id "$RUN_ID" "${PLAN_ARM[@]}" "${PLAN_WL[@]}" "${FORWARD[@]}"
fi
if [[ "$SKIP_E2E" -eq 0 ]]; then
  run_step e2e "$ROOT/scripts/bench-e2e.sh" --run-id "$RUN_ID" --cache "$CACHE" "${E2E_ARM[@]}" "${E2E_WL[@]}" "${FORWARD[@]}"
fi
echo "=== done → experiments/results/$RUN_ID/ (meta.json summary.md *.jsonl) ==="
