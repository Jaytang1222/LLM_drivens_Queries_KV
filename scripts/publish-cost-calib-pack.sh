#!/usr/bin/env bash
# Cost calibration pack: optional gen-IR → collect pairs → fit-cost → merge planner → archive.
# Usage:
#   ./scripts/publish-cost-calib-pack.sh [pairs_dir]
#   ./scripts/publish-cost-calib-pack.sh --gen-ir [out_dir]
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck disable=SC1091
source "$ROOT/scripts/kart-env.sh"


kart_gen_cost_ir() {
  local out="${1:-$ROOT/experiments/workloads/cost_calib}"
  local mid="${2:-${KART_EXPERIMENT_MANIFEST}}"
  python3 - "$out" "$mid" <<'PY'
import json
from pathlib import Path
import sys
out = Path(sys.argv[1])
manifest_id = sys.argv[2]
out.mkdir(parents=True, exist_ok=True)
times = [
    (1_230_768_000_000, 1_230_854_400_000),
    (1_230_768_000_000, 1_231_200_000_000),
]
spaces = [
    (439000.0, 4415000.0, 450000.0, 4425000.0),
    (400000.0, 4390000.0, 500000.0, 4450000.0),
]
metrics = ["DTW", "FRECHET", "HAUSDORFF"]
modes = [("TRAJECTORY_IDS", None), ("TOP_K", 5), ("TOP_K", 20)]
templates = []
q = 0
for t0, t1 in times:
    for s in spaces:
        for mode, k in modes:
            for metric in metrics if mode == "TOP_K" else [None]:
                q += 1
                result = {"mode": mode, "tie_breaker": "TID_ASC"}
                if k is not None:
                    result["k"] = k
                ir = {
                    "ir_version": "1.0",
                    "query_id": f"cost_calib_{q:03d}",
                    "source": {"dataset_id": "tdrive", "entity": "trajectory"},
                    "temporal": {"start_ms": t0, "end_ms": t1},
                    "spatial": {
                        "min_x": s[0], "min_y": s[1], "max_x": s[2], "max_y": s[3],
                        "relation": "INTERSECTS", "boundary": "INCLUDED",
                    },
                    "predicates": [],
                    "semantics": {"mode": "OBSERVED_POINT", "coupling": "SAME_POINT"},
                    "result": result,
                    "snapshot": {
                        "manifest_id": manifest_id,
                        "semantics_version": "point_similarity_v2",
                    },
                }
                if mode == "TOP_K":
                    ir["similarity"] = {
                        "metric": metric,
                        "reference_tid": 1,
                        "scope": "FULL_TRAJECTORY",
                        "exclude_reference": True,
                        "local_distance": "EUCLIDEAN",
                        "normalization": "NONE",
                    }
                path = out / f"{ir['query_id']}.bound_ir.json"
                path.write_text(json.dumps(ir, indent=2), encoding="utf-8")
                templates.append(str(path))
print(f"wrote {len(templates)} IRs under {out}")
PY
}

kart_merge_cost_coeffs() {
  local coeffs="$1"
  local planner="${2:-$ROOT/config/planner.yaml}"
  python3 - "$coeffs" "$planner" <<'PY'
import json, re, sys
from pathlib import Path
coeffs_path, planner_path = Path(sys.argv[1]), Path(sys.argv[2])
named = json.loads(coeffs_path.read_text(encoding="utf-8"))
if not isinstance(named, dict):
    raise SystemExit("coeffs JSON must be an object")
filter_rate = named.get("filter_pass_rate")
text = planner_path.read_text(encoding="utf-8")
text = re.sub(r"(?m)^(\s*calibrated:\s*)\w+", r"\g<1>true", text, count=1)
for k, v in named.items():
    if k in ("calibrated", "model_version", "filter_pass_rate",
             "filter_pass_samples", "filter_pass_hits"):
        continue
    pat = re.compile(rf"(?m)^(\s*{re.escape(str(k))}:\s*)[^\n]+")
    if pat.search(text):
        text = pat.sub(rf"\g<1>{v}", text, count=1)
    else:
        text = re.sub(
            r"(?m)^(\s*calibrated:\s*true\s*)$",
            rf"\1\n  {k}: {v}",
            text,
            count=1,
        )
if "model_version:" in text:
    mv = named.get("model_version", "cost_v2_rs_sched")
    text = re.sub(
        r"(?m)^(\s*model_version:\s*)[^\n]+",
        rf"\g<1>{mv}",
        text,
        count=1,
    )
if re.search(r"(?m)^\s*eta_cell_dtw:", text):
    text = re.sub(r"(?m)^(\s*eta_cell_dtw:\s*)[^\n]+", r"\g<1>0.0", text, count=1)
else:
    text = re.sub(
        r"(?m)^(\s*eta_cell:\s*[^\n]+)$",
        r"\1\n  eta_cell_dtw: 0.0",
        text,
        count=1,
    )
planner_path.write_text(text, encoding="utf-8")
if filter_rate is not None:
    side = planner_path.parent.parent / "experiments" / "results" / "filter_pass_update.json"
    side.parent.mkdir(parents=True, exist_ok=True)
    side.write_text(
        json.dumps({"filter_pass_rate": filter_rate}, indent=2) + "\n",
        encoding="utf-8",
    )
print(json.dumps({
    "merged_planner": str(planner_path),
    "coeffs": str(coeffs_path),
    "keys": sorted(k for k in named.keys() if k not in (
        "filter_pass_rate", "filter_pass_samples", "filter_pass_hits")),
}, indent=2))
PY
}

kart_collect_cost_traces() {
  local OUT="${1:-$ROOT/runs/cost_calib}"
  local MANIFEST="${KART_EXPERIMENT_MANIFEST}"
  mkdir -p "$OUT"
  local FAILED=0 SKIPPED=0 OK=0

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

  local IR_LIST=()
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
  local f
  for f in "${IR_LIST[@]}"; do
    collect_one "$f"
  done

  echo "done collecting under $OUT (ok=$OK skipped=$SKIPPED failures=$FAILED)"
  local COMPLETE
  COMPLETE=$(find "$OUT" -mindepth 1 -maxdepth 1 -type d -exec test -f '{}/cost_features.json' \; -exec test -f '{}/trace.json' \; -print | wc -l | tr -d ' ')
  echo "complete_pairs=$COMPLETE"
  if [[ "$COMPLETE" -lt 6 ]]; then
    echo "ERROR: need >=6 complete pairs for train/val/test; got $COMPLETE (failures=$FAILED)" >&2
    return 1
  fi
  if [[ "$FAILED" -gt 0 ]]; then
    echo "WARN: $FAILED queries failed but $COMPLETE complete pairs available — continuing"
  fi
}

if [[ "${1:-}" == "--gen-ir" ]]; then
  shift || true
  kart_gen_cost_ir "$@"
  exit 0
fi

MANIFEST="${KART_EXPERIMENT_MANIFEST}"
PAIRS_LIVE="${1:-$ROOT/runs/cost_calib}"
PACK_ID="${KART_COST_PACK_ID:-cost_calib_pack_$(date -u +%Y%m%d)}"
PACK_DIR="$ROOT/experiments/results/$PACK_ID"
REPORT="$ROOT/experiments/results/cost_calibration_report.json"
COEFFS="$ROOT/experiments/results/cost_coeffs_calibrated.json"

echo "=== cost calib: collect → fit → merge → pack ==="
echo "root=$ROOT manifest=$MANIFEST pairs=$PAIRS_LIVE pack=$PACK_DIR"

kart_collect_cost_traces "$PAIRS_LIVE"

kart_java fit-cost \
  --pairs "$PAIRS_LIVE" \
  --report "$REPORT" \
  --coeffs "$COEFFS" \
  --manifest "$MANIFEST" \
  --catalog catalog

kart_merge_cost_coeffs "$COEFFS" "$ROOT/config/planner.yaml"

mkdir -p "$PACK_DIR/pairs"
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

cp -f "$PACK_DIR/cost_calibration_report.json" "$ROOT/experiments/results/cost_calibration_report.json"
cp -f "$PACK_DIR/cost_coeffs_calibrated.json" "$ROOT/experiments/results/cost_coeffs_calibrated.json"
cp -f "$PACK_DIR/meta.json" "$ROOT/experiments/results/cost_calibration_meta.json"

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
