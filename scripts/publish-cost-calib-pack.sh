#!/usr/bin/env bash
# Authoritative cost calibration pack:
#   collect pairs → Java fit-cost → merge planner.yaml → archive under experiments/results/
# Requires HBase + READY manifest. Run inside WSL on canonical DST after sync.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck disable=SC1091
source "$ROOT/scripts/kart-env.sh"

MANIFEST="${KART_EXPERIMENT_MANIFEST}"
PAIRS_LIVE="${1:-$ROOT/runs/cost_calib}"
PACK_ID="${KART_COST_PACK_ID:-cost_calib_pack_$(date -u +%Y%m%d)}"
PACK_DIR="$ROOT/experiments/results/$PACK_ID"
REPORT="$ROOT/experiments/results/cost_calibration_report.json"
COEFFS="$ROOT/experiments/results/cost_coeffs_calibrated.json"

echo "=== cost calib: collect → fit → merge → pack ==="
echo "root=$ROOT manifest=$MANIFEST pairs=$PAIRS_LIVE pack=$PACK_DIR"

bash "$ROOT/scripts/collect-cost-traces.sh" "$PAIRS_LIVE"

kart_java fit-cost \
  --pairs "$PAIRS_LIVE" \
  --report "$REPORT" \
  --coeffs "$COEFFS" \
  --manifest "$MANIFEST" \
  --catalog catalog

python3 "$ROOT/scripts/merge_cost_coeffs_into_planner.py" \
  --coeffs "$COEFFS" \
  --planner "$ROOT/config/planner.yaml"

mkdir -p "$PACK_DIR/pairs"
# Archive replayable pairs (feature+trace only)
while IFS= read -r -d '' d; do
  qid="$(basename "$d")"
  if [[ -f "$d/cost_features.json" && -f "$d/trace.json" ]]; then
    mkdir -p "$PACK_DIR/pairs/$qid"
    cp -f "$d/cost_features.json" "$PACK_DIR/pairs/$qid/"
    cp -f "$d/trace.json" "$PACK_DIR/pairs/$qid/"
    [[ -f "$d/bound_ir.json" ]] && cp -f "$d/bound_ir.json" "$PACK_DIR/pairs/$qid/" || true
  fi
done < <(find "$PAIRS_LIVE" -mindepth 1 -maxdepth 1 -type d -print0)

cp -f "$REPORT" "$PACK_DIR/cost_calibration_report.json"
cp -f "$COEFFS" "$PACK_DIR/cost_coeffs_calibrated.json"
cp -f "$ROOT/config/planner.yaml" "$PACK_DIR/planner.yaml.frozen"

GIT_HEAD="$(cd "$ROOT" && git rev-parse HEAD 2>/dev/null || echo n/a)"
GIT_SHORT="$(cd "$ROOT" && git rev-parse --short HEAD 2>/dev/null || echo n/a)"
PAIR_N="$(find "$PACK_DIR/pairs" -mindepth 1 -maxdepth 1 -type d | wc -l | tr -d ' ')"

python3 - <<PY
import json
from pathlib import Path
pack = Path(r"$PACK_DIR")
report = json.loads((pack / "cost_calibration_report.json").read_text(encoding="utf-8"))
meta = {
  "pack_id": "$PACK_ID",
  "utc": __import__("datetime").datetime.utcnow().strftime("%Y-%m-%dT%H:%M:%SZ"),
  "git_commit": "$GIT_HEAD",
  "git_commit_short": "$GIT_SHORT",
  "manifest": "$MANIFEST",
  "model_version": report.get("modelVersion") or report.get("model_version"),
  "calibrated": report.get("calibrated"),
  "pair_count": int("$PAIR_N"),
  "train_size": report.get("trainSize"),
  "val_size": report.get("valSize"),
  "test_size": report.get("testSize"),
  "train_mae": report.get("trainMae"),
  "val_mae": report.get("valMae"),
  "test_mae": report.get("testMae"),
  "named_coeffs": report.get("namedCoeffs") or report.get("named_coeffs"),
  "authority": "kart.cli.FitCostCmd / FeedbackCalibrator (CostModel+ScheduleEstimate MAE)",
  "note": "Freeze planner.yaml during experiment window; do not hot-reload mid-query.",
}
(pack / "meta.json").write_text(json.dumps(meta, indent=2) + "\n", encoding="utf-8")
print(json.dumps(meta, indent=2))
PY

# Also refresh the "current" pointers under experiments/results/
cp -f "$PACK_DIR/cost_calibration_report.json" "$ROOT/experiments/results/cost_calibration_report.json"
cp -f "$PACK_DIR/cost_coeffs_calibrated.json" "$ROOT/experiments/results/cost_coeffs_calibrated.json"
cp -f "$PACK_DIR/meta.json" "$ROOT/experiments/results/cost_calibration_meta.json"

# Mirror pack + planner back to Windows SRC if we are on DST
if [[ "$ROOT" == "$KART_DST" && -d "$KART_SRC" ]]; then
  mkdir -p "$KART_SRC/experiments/results/$PACK_ID"
  rsync -a "$PACK_DIR/" "$KART_SRC/experiments/results/$PACK_ID/"
  cp -f "$ROOT/config/planner.yaml" "$KART_SRC/config/planner.yaml"
  cp -f "$REPORT" "$KART_SRC/experiments/results/cost_calibration_report.json"
  cp -f "$COEFFS" "$KART_SRC/experiments/results/cost_coeffs_calibrated.json"
  cp -f "$PACK_DIR/meta.json" "$KART_SRC/experiments/results/cost_calibration_meta.json"
  echo "mirrored pack + planner → $KART_SRC"
fi

echo "=== cost calib pack ready: $PACK_DIR ==="
echo "Replay: kart fit-cost --pairs $PACK_DIR/pairs --report /tmp/r.json --coeffs /tmp/c.json"
