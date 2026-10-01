#!/usr/bin/env bash
# Offline screen for cbo-llm pilot candidates (no new LLM arm).
# Requires WSL HBase READY + rebuilt jar.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
# shellcheck disable=SC1091
source scripts/kart-env.sh
kart_ensure_jar

CAND="${1:-experiments/workloads/bound_ir_cbo_llm_pilot_v1.candidates.json}"
OUT_WL="experiments/workloads/_pilot_screen_workload.json"
RUN_ID="cbo-llm-pilot-screen-$(date +%Y%m%d-%H%M%S)"
SCREEN_LOG="experiments/workloads/bound_ir_cbo_llm_pilot_v1.screen_log.json"

python3 - "$CAND" "$OUT_WL" <<'PY'
import json, sys
from pathlib import Path
cand = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
queries = [c["query"] for c in cand["candidates"]]
doc = {
  "manifest_id": cand.get("manifest_id", "tdrive_v1_ready"),
  "semantics_version": cand.get("semantics_version", "point_dtw_v1"),
  "workload_id": "cbo_llm_pilot_screen",
  "seed": cand.get("seed"),
  "queries": queries,
}
Path(sys.argv[2]).write_text(json.dumps(doc, indent=2) + "\n", encoding="utf-8")
print("screen workload queries", len(queries))
PY

# Plan-only CBO + Bao (no LLM proposal arm)
bash scripts/bench-plan.sh \
  --suite experiments/suites/plan-cbo-llm-screen.yaml \
  --workload "$OUT_WL" \
  --run-id "$RUN_ID" \
  --trials 1 \
  --cache warm

PLAN_JSONL="experiments/results/${RUN_ID}/plan.jsonl"
python3 experiments/adapters/screen_cbo_llm_pilot.py \
  --candidates "$CAND" \
  --plan-jsonl "$PLAN_JSONL" \
  --out "$SCREEN_LOG"

echo "Screen log → $SCREEN_LOG"
echo "Next: fill FullScan oracle for kept queries, then:"
echo "  python3 experiments/adapters/generate_cbo_llm_pilot_v1.py --freeze-from-screen $SCREEN_LOG --oracle <oracle.json>"
