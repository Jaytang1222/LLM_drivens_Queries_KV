#!/usr/bin/env bash
# Ablation experiment suite entry (thin wrapper → kart bench-suite)
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck disable=SC1091
source "$ROOT/scripts/kart-env.sh"
cd "$ROOT"
kart_ensure_jar
kart_load_llm_env || true

SUITE="${BENCH_SUITE:-experiments/suites/ablation.yaml}"
RUN_ID=""
EXTRA=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    --suite) SUITE="$2"; shift 2 ;;
    --run-id) RUN_ID="$2"; shift 2 ;;
    *) EXTRA+=("$1"); shift ;;
  esac
done
ARGS=(bench-suite --suite "$SUITE")
if [[ -n "$RUN_ID" ]]; then
  ARGS+=(--run-id "$RUN_ID")
fi
ARGS+=("${EXTRA[@]}")
exec java -Dkart.root="$KART_ROOT" -jar "$KART_JAR" "${ARGS[@]}"
