#!/usr/bin/env bash
# Full comparative batch (background). Logs + PID under experiments/results/<run-id>/.
# Stages: E1 parse → E2 plan complex/simple → E2 shared-pool → E3 warm/cold complex+simple
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck disable=SC1091
source "$ROOT/scripts/kart-env.sh"
cd "$ROOT"

RUN_ID="${RUN_ID:-cmp-full-cs-$(date +%Y%m%d-%H%M%S)}"
OUT="$ROOT/experiments/results/$RUN_ID"
mkdir -p "$OUT"
LOG="$OUT/batch.log"
PID_FILE="$OUT/batch.pid"
STATUS="$OUT/batch.status.json"

echo "$$" > "$PID_FILE"

write_status() {
  local stage="$1" state="$2" note="${3:-}"
  python3 - "$STATUS" "$RUN_ID" "$stage" "$state" "$note" <<'PY'
import json, sys, time
from pathlib import Path
path, run_id, stage, state, note = sys.argv[1:6]
prev = {}
p = Path(path)
if p.is_file():
    try:
        prev = json.loads(p.read_text(encoding="utf-8"))
    except Exception:
        prev = {}
stages = prev.get("stages") or {}
stages[stage] = {"state": state, "note": note, "ts": time.strftime("%Y-%m-%dT%H:%M:%S")}
out = {
    "run_id": run_id,
    "pid": int(Path(path).with_name("batch.pid").read_text().strip() or 0),
    "started_at": prev.get("started_at") or time.strftime("%Y-%m-%dT%H:%M:%S"),
    "updated_at": time.strftime("%Y-%m-%dT%H:%M:%S"),
    "current_stage": stage,
    "overall": state if stage == "all" else prev.get("overall", "running"),
    "stages": stages,
}
if stage == "all":
    out["overall"] = state
    out["finished_at"] = time.strftime("%Y-%m-%dT%H:%M:%S") if state in ("ok", "failed") else None
p.write_text(json.dumps(out, indent=2) + "\n", encoding="utf-8")
PY
}

log() { echo "[$(date '+%F %T')] $*" | tee -a "$LOG"; }

run_step() {
  local name="$1"; shift
  write_status "$name" "running"
  log "=== START $name ==="
  set +e
  "$@" >>"$LOG" 2>&1
  local ec=$?
  set -e
  if [[ "$ec" -eq 0 ]]; then
    write_status "$name" "ok"
    log "=== OK $name ==="
    return 0
  fi
  write_status "$name" "failed" "exit=$ec"
  log "=== FAIL $name exit=$ec (continuing) ==="
  return 0
}

log "=== batch start run_id=$RUN_ID root=$ROOT ADV=${KART_ADVANCED_INDEXES:-} ==="
write_status "init" "running" "batch started"

set +e
kart_load_llm_env >>"$LOG" 2>&1
LLM_EC=$?
set -e
if [[ "$LLM_EC" -ne 0 ]]; then
  log "WARN: LLM env not fully loaded (exit=$LLM_EC); LLM arms may fail"
fi

kart_ensure_jar >>"$LOG" 2>&1

for f in \
  experiments/workloads/nl_ir_v3.json \
  experiments/workloads/bound_ir_complex_v2.json \
  experiments/workloads/bound_ir_complex_v2.oracle.json \
  experiments/workloads/bound_ir_simple_v2.json \
  experiments/workloads/bound_ir_simple_v2.oracle.json \
  experiments/suites/plan-simple.yaml \
  experiments/suites/e2e-simple.yaml
do
  if [[ ! -f "$f" ]]; then
    log "ERROR: missing $f"
    write_status "all" "failed" "missing $f"
    exit 2
  fi
done

# E1 formal deep arms (nl_ir_v3), trials=5
run_step e1_parse \
  bash "$ROOT/scripts/bench-parse.sh" \
    --run-id "$RUN_ID" \
    --arm kart,direct-draftir,din-sql-spider,din-sql-bird,sag-mql-nofeedback \
    --trials 5

# E2 native plan — complex
run_step e2_plan_complex \
  bash "$ROOT/scripts/bench-plan.sh" \
    --run-id "${RUN_ID}-plan-complex" \
    --suite experiments/suites/plan.yaml \
    --trials 5

# E2 native plan — simple
run_step e2_plan_simple \
  bash "$ROOT/scripts/bench-plan.sh" \
    --run-id "${RUN_ID}-plan-simple" \
    --suite experiments/suites/plan-simple.yaml \
    --trials 5

# E2 shared-pool (complex only; separate table)
run_step e2_shared_pool \
  bash "$ROOT/scripts/bench-plan.sh" \
    --run-id "${RUN_ID}-shared-pool" \
    --suite experiments/suites/plan-shared-pool.yaml \
    --trials 5

# E3 warm — complex
run_step e3_warm_complex \
  bash "$ROOT/scripts/bench-e2e.sh" \
    --run-id "${RUN_ID}-e3-warm-complex" \
    --suite experiments/suites/e2e.yaml \
    --cache warm \
    --trials 5

# E3 warm — simple
run_step e3_warm_simple \
  bash "$ROOT/scripts/bench-e2e.sh" \
    --run-id "${RUN_ID}-e3-warm-simple" \
    --suite experiments/suites/e2e-simple.yaml \
    --cache warm \
    --trials 5

# E3 cold first-run — complex
run_step e3_cold_complex \
  bash "$ROOT/scripts/bench-e2e.sh" \
    --run-id "${RUN_ID}-e3-cold-complex" \
    --suite experiments/suites/e2e.yaml \
    --cache cold \
    --trials 1

# E3 cold first-run — simple
run_step e3_cold_simple \
  bash "$ROOT/scripts/bench-e2e.sh" \
    --run-id "${RUN_ID}-e3-cold-simple" \
    --suite experiments/suites/e2e-simple.yaml \
    --cache cold \
    --trials 1

python3 - "$STATUS" <<'PY' >>"$LOG" 2>&1
import json, sys
from pathlib import Path
st = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
stages = st.get("stages") or {}
failed = [k for k,v in stages.items() if k != "all" and v.get("state") == "failed"]
ok = [k for k,v in stages.items() if k != "all" and v.get("state") == "ok"]
print("stages_ok=", ok)
print("stages_failed=", failed)
PY

FAILED=$(python3 -c "import json; s=json.load(open('$STATUS')); print(sum(1 for k,v in s.get('stages',{}).items() if k!='all' and v.get('state')=='failed'))")
if [[ "$FAILED" -gt 0 ]]; then
  write_status "all" "failed" "failed_stages=$FAILED"
  log "=== batch DONE with failures failed_stages=$FAILED → $OUT ==="
  exit 1
fi
write_status "all" "ok"
log "=== batch DONE ok → $OUT ==="
exit 0
