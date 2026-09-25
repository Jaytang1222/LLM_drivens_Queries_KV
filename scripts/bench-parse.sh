#!/usr/bin/env bash
# E1: LLM → IR
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck disable=SC1091
source "$ROOT/scripts/kart-env.sh"
cd "$ROOT"
kart_ensure_jar
kart_load_llm_env || true

SUITE="${BENCH_SUITE:-experiments/suites/parse.yaml}"
RUN_ID=""
EXTRA=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    --suite) SUITE="$2"; shift 2 ;;
    --workload) EXTRA+=(--workload "$2"); shift 2 ;;
    --oracle) EXTRA+=(--oracle "$2"); shift 2 ;;
    --run-id) RUN_ID="$2"; shift 2 ;;
    --arm) EXTRA+=(--arm "$2"); shift 2 ;;
    --limit) EXTRA+=(--limit "$2"); shift 2 ;;
    --keep-artifacts) EXTRA+=(--keep-artifacts); shift ;;
    *) EXTRA+=("$1"); shift ;;
  esac
done
ARGS=(bench-suite --suite "$SUITE")
[[ -n "$RUN_ID" ]] && ARGS+=(--run-id "$RUN_ID")
ARGS+=("${EXTRA[@]}")
exec java -Dkart.root="$KART_ROOT" -jar "$KART_JAR" "${ARGS[@]}"
