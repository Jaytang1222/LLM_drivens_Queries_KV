#!/usr/bin/env bash
# Collect cost features + traces for calibration corpus (pre-exec features, post-exec trace).
# Requires real cost_features.json from query-ir artifacts — never substitutes cost_card.json.
# Skips pair dirs that already have both cost_features.json and trace.json (resume-safe).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck disable=SC1091
source "$ROOT/scripts/kart-env.sh"
OUT="${1:-$ROOT/runs/cost_calib}"
MANIFEST="${KART_EXPERIMENT_MANIFEST}"
mkdir -p "$OUT"
FAILED=0
SKIPPED=0
OK=0

collect_one() {
  local ir="$1"
  local qid
  qid="$(basename "$ir" .bound_ir.json)"
  qid="${qid%.json}"
  local dest="$OUT/$qid"
  mkdir -p "$dest"
  if [[ -f "$dest/cost_features.json" && -f "$dest/trace.json" ]]; then
    echo "skip $qid (already complete)"
    SKIPPED=$((SKIPPED + 1))
    return 0
  fi
  echo "collect $qid"
  if ! "$ROOT/scripts/kart.sh" run query-ir --ir "$ir" --manifest "$MANIFEST" --runs "$dest" >/tmp/kart_cost_collect_$qid.log 2>&1; then
    echo "ERROR: query-ir failed for $qid (see /tmp/kart_cost_collect_$qid.log)"
    FAILED=$((FAILED + 1))
    return 0
  fi
  local run
  run="$(find "$dest" -type d -name 'q_*' 2>/dev/null | head -1 || true)"
  if [[ -z "${run:-}" ]]; then
    run="$(find "$dest" -type f -name 'trace.json' -printf '%h\n' 2>/dev/null | head -1 || true)"
  fi
  if [[ -z "${run:-}" ]]; then
    echo "ERROR: no run artifacts for $qid"
    FAILED=$((FAILED + 1))
    return 0
  fi
  if [[ ! -f "$run/cost_features.json" ]]; then
    echo "ERROR: missing cost_features.json for $qid (refusing cost_card substitute)"
    FAILED=$((FAILED + 1))
    return 0
  fi
  if [[ ! -f "$run/trace.json" ]]; then
    echo "ERROR: missing trace.json for $qid"
    FAILED=$((FAILED + 1))
    return 0
  fi
  cp "$run/cost_features.json" "$dest/cost_features.json"
  cp "$run/trace.json" "$dest/trace.json"
  [[ -f "$run/bound_ir.json" ]] && cp "$run/bound_ir.json" "$dest/bound_ir.json" || true
  OK=$((OK + 1))
}

# Prefer cost_calib corpus; fall back to smoke/hard if present.
IR_LIST=()
if [[ -d "$ROOT/experiments/workloads/cost_calib" ]]; then
  while IFS= read -r f; do IR_LIST+=("$f"); done < <(find "$ROOT/experiments/workloads/cost_calib" -name '*.bound_ir.json' | sort)
fi
if [[ ${#IR_LIST[@]} -eq 0 && -d "$ROOT/testdata/smoke" ]]; then
  while IFS= read -r f; do IR_LIST+=("$f"); done < <(find "$ROOT/testdata/smoke" -name '*.json' | sort)
fi
if [[ ${#IR_LIST[@]} -eq 0 && -d "$ROOT/experiments/workloads/hard" ]]; then
  while IFS= read -r f; do IR_LIST+=("$f"); done < <(find "$ROOT/experiments/workloads/hard" -name '*.json' | sort)
fi

echo "collecting ${#IR_LIST[@]} IRs → $OUT (manifest=$MANIFEST)"
for f in "${IR_LIST[@]}"; do
  collect_one "$f"
done

echo "done collecting under $OUT (ok=$OK skipped=$SKIPPED failures=$FAILED)"
COMPLETE=$(find "$OUT" -mindepth 1 -maxdepth 1 -type d -exec test -f '{}/cost_features.json' \; -exec test -f '{}/trace.json' \; -print | wc -l | tr -d ' ')
echo "complete_pairs=$COMPLETE"
if [[ "$COMPLETE" -lt 6 ]]; then
  echo "ERROR: need >=6 complete pairs for train/val/test; got $COMPLETE (failures=$FAILED)" >&2
  exit 1
fi
if [[ "$FAILED" -gt 0 ]]; then
  echo "WARN: $FAILED queries failed but $COMPLETE complete pairs available — continuing"
fi
