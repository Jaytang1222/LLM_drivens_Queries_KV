#!/usr/bin/env bash
# Full comparative batch (background). Logs + PID under experiments/results/<run-id>/.
# Stages: E1 parse → E2 plan → E2 shared-pool → E3 warm → E3 cold
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck disable=SC1091
source "$ROOT/scripts/kart-env.sh"
cd "$ROOT"

RUN_ID="${RUN_ID:-cmp-full-adv2-$(date +%Y%m%d-%H%M%S)}"
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

log "=== batch start run_id=$RUN_ID root=$ROOT ==="
write_status "init" "running" "batch started"

# Ensure LLM env for E1 / conditional LLM arms
set +e
kart_load_llm_env >>"$LOG" 2>&1
LLM_EC=$?
set -e
if [[ "$LLM_EC" -ne 0 ]]; then
  log "WARN: LLM env not fully loaded (exit=$LLM_EC); LLM arms may fail"
fi

kart_ensure_jar >>"$LOG" 2>&1

# Sync check: advantage workload must exist
if [[ ! -f experiments/workloads/bound_ir_advantage_v2.json ]]; then
  log "ERROR: missing bound_ir_advantage_v2.json — sync required"
  write_status "all" "failed" "missing advantage_v2 workload"
  exit 2
fi

# E1 formal deep arms, trials=5
run_step e1_parse \
  bash "$ROOT/scripts/bench-parse.sh" \
    --run-id "$RUN_ID" \
    --arm kart,direct-draftir,din-sql-spider,din-sql-bird,sag-mql-nofeedback \
    --trials 5

# E2 native plan (suite default = advantage_v2)
run_step e2_plan \
  bash "$ROOT/scripts/bench-plan.sh" \
    --run-id "$RUN_ID" \
    --suite experiments/suites/plan.yaml \
    --trials 5

# E2 shared-pool (separate suite/table)
run_step e2_shared_pool \
  bash "$ROOT/scripts/bench-plan.sh" \
    --run-id "${RUN_ID}-shared-pool" \
    --suite experiments/suites/plan-shared-pool.yaml \
    --trials 5

# E3 warm formal
run_step e3_warm \
  bash "$ROOT/scripts/bench-e2e.sh" \
    --run-id "${RUN_ID}-e3-warm" \
    --suite experiments/suites/e2e.yaml \
    --cache warm \
    --trials 5

# E3 cold first-run (may be cache_enforced=false without sudo drop_caches)
run_step e3_cold \
  bash "$ROOT/scripts/bench-e2e.sh" \
    --run-id "${RUN_ID}-e3-cold" \
    --suite experiments/suites/e2e.yaml \
    --cache cold \
    --trials 1

# Summarize stage outcomes
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
