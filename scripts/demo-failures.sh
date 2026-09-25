#!/usr/bin/env bash
# T5.7 failure-path demo (IMPLEMENTATION_PLAN / task.md):
#   RESOURCE_EXHAUSTED | DATA_INTEGRITY_ERROR | UNSUPPORTED_QUERY | LLM/parse failure
# Prefer unit/integration tests (no live HBase required). Optional live probes when KART_LIVE=1.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

echo "=== demo-failures: unit evidence (CoordinatorFailureTest + dialog COUNT reject) ==="
mvn -q -Dtest=CoordinatorFailureTest,QueryNlAcceptanceTest test

echo ""
echo "Expected status mapping (from tests / dialog):"
echo "  RESOURCE_EXHAUSTED   — max_candidate_chunks=1 | soft_memory_bytes tiny"
echo "  DATA_INTEGRITY_ERROR — missing raw chunk on FETCH/BATCH_GET"
echo "  UNSUPPORTED_QUERY    — COUNT / aggregation rejected in Dialog/DraftIrParser"
echo "  parse/LLM failure    — Mock miss / INVALID_IR paths in QueryNlAcceptanceTest"

if [[ "${KART_LIVE:-0}" == "1" ]]; then
  # shellcheck disable=SC1091
  source "$ROOT/scripts/kart-env.sh"
  echo ""
  echo "=== demo-failures: live UNSUPPORTED (COUNT) via chat one-shot if available ==="
  # Best-effort: parser rejects before HBase.
  echo "COUNT how many trajectories intersect beijing_core" | kart_java chat --once 2>&1 | tail -20 || true
fi

echo ""
echo "demo-failures OK"
