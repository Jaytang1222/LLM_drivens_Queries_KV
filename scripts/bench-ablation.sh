#!/usr/bin/env bash
# Ablation experiment suite entry (thin wrapper → kart bench-suite)
# See spec/ablation_experiment.md
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck disable=SC1091
source "$ROOT/scripts/kart-env.sh"
cd "$ROOT"
kart_ensure_jar
kart_load_llm_env || true

SUITE="${BENCH_SUITE:-experiments/suites/ablation.yaml}"
RUN_ID=""
CACHE="cold"
EXTRA=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    --suite) SUITE="$2"; shift 2 ;;
    --workload) EXTRA+=(--workload "$2"); shift 2 ;;
    --oracle) EXTRA+=(--oracle "$2"); shift 2 ;;
    --run-id) RUN_ID="$2"; shift 2 ;;
    --factor) EXTRA+=(--factor "$2"); shift 2 ;;
    --arm) EXTRA+=(--arm "$2"); shift 2 ;;
    --cache) CACHE="$2"; shift 2 ;;
    --limit) EXTRA+=(--limit "$2"); shift 2 ;;
    --trials) EXTRA+=(--trials "$2"); shift 2 ;;
    --allow-unsafe) EXTRA+=(--allow-unsafe); shift ;;
    --no-pair-full) EXTRA+=(--no-pair-full); shift ;;
    --keep-artifacts) EXTRA+=(--keep-artifacts); shift ;;
    *) EXTRA+=("$1"); shift ;;
  esac
done
ARGS=(bench-suite --suite "$SUITE" --cache "$CACHE")
if [[ -n "$RUN_ID" ]]; then
  ARGS+=(--run-id "$RUN_ID")
fi
ARGS+=("${EXTRA[@]}")
exec java -Dkart.root="$KART_ROOT" -jar "$KART_JAR" "${ARGS[@]}"
