#!/usr/bin/env bash
# Watch comparative batch; when fully finished + results stable, start ablation.
set -uo pipefail

ROOT="${KART_DST:-$HOME/projects/llm-kv}"
WIN=/mnt/f/Projects/LLM_KV
# shellcheck disable=SC1091
source "$ROOT/scripts/kart-env.sh"
cd "$ROOT"

CMP_ID="${CMP_RUN_ID:-cmp-full-adv2-20260927-200011}"
CMP_OUT="$ROOT/experiments/results/$CMP_ID"
WATCH_LOG="$CMP_OUT/watch-then-ablation.log"
WATCH_STATUS="$CMP_OUT/watch-then-ablation.status.json"
ABL_STAMP="$(date +%Y%m%d-%H%M%S)"
ABL_COLD="abl-main-cold-${ABL_STAMP}"
ABL_WARM="abl-main-warm-${ABL_STAMP}"
ABL_RISK="abl-risk-no-coverage-${ABL_STAMP}"

mkdir -p "$CMP_OUT" "$WIN/experiments/results/$CMP_ID"
exec >>"$WATCH_LOG" 2>&1

log() { echo "[$(date '+%F %T')] $*"; }

write_watch() {
  python3 - "$WATCH_STATUS" "$@" <<'PY'
import json, sys, time
from pathlib import Path
path = Path(sys.argv[1])
state, note = sys.argv[2], sys.argv[3] if len(sys.argv) > 3 else ""
extra = {}
if len(sys.argv) > 4 and sys.argv[4]:
    try:
        extra = json.loads(sys.argv[4])
    except Exception:
        extra = {"raw": sys.argv[4]}
prev = {}
if path.is_file():
    try:
        prev = json.loads(path.read_text(encoding="utf-8"))
    except Exception:
        prev = {}
out = {
    **prev,
    "updated_at": time.strftime("%Y-%m-%dT%H:%M:%S"),
    "state": state,
    "note": note,
    **extra,
}
if "started_at" not in out:
    out["started_at"] = out["updated_at"]
path.write_text(json.dumps(out, indent=2) + "\n", encoding="utf-8")
PY
  cp -f "$WATCH_STATUS" "$WIN/experiments/results/$CMP_ID/" 2>/dev/null || true
}

rows() {
  local f="$1"
  if [[ -f "$f" ]]; then wc -l < "$f" | tr -d ' '; else echo 0; fi
}

cmp_batch_alive() {
  local pid_file="$CMP_OUT/nohup.pid"
  [[ -f "$pid_file" ]] || return 1
  local pid
  pid=$(tr -d ' \r\n' < "$pid_file")
  [[ -n "$pid" ]] && ps -p "$pid" >/dev/null 2>&1
}

cmp_complete() {
  python3 - "$CMP_OUT/batch.status.json" <<'PY'
import json, sys
from pathlib import Path
p = Path(sys.argv[1])
if not p.is_file():
    sys.exit(1)
st = json.loads(p.read_text(encoding="utf-8"))
stages = st.get("stages") or {}
# Pipeline finished when e3_cold settled and overall terminal
need = ["e1_parse", "e2_plan", "e2_shared_pool", "e3_warm", "e3_cold"]
for k in need:
    s = (stages.get(k) or {}).get("state")
    if s not in ("ok", "failed"):
        sys.exit(2)
if st.get("overall") not in ("ok", "failed"):
    # overall may still be "running" until final write; require e3_cold present
    if "all" not in stages and st.get("overall") == "running":
        # accept if e3_cold done — batch script sets overall at end
        pass
sys.exit(0)
PY
}

results_ready() {
  # Expected formal sizes for advantage_v2 n=58, 5 arms
  local n_parse n_plan n_pool n_warm n_cold
  n_parse=$(rows "$CMP_OUT/parse.jsonl")
  n_plan=$(rows "$CMP_OUT/plan.jsonl")
  n_pool=$(rows "$CMP_OUT-shared-pool/plan.jsonl")
  n_warm=$(rows "$CMP_OUT-e3-warm/e2e.jsonl")
  n_cold=$(rows "$CMP_OUT-e3-cold/e2e.jsonl")
  log "counts parse=$n_parse plan=$n_plan pool=$n_pool warm=$n_warm cold=$n_cold"
  # Soft gates: allow E1 failed-but-full; require E2/E3 presence
  [[ "$n_plan" -ge 1400 ]] || return 1
  [[ "$n_pool" -ge 1100 ]] || return 1
  [[ "$n_warm" -ge 1400 ]] || return 1
  [[ "$n_cold" -ge 280 ]] || return 1
  # Stability: sizes unchanged across 20s
  local a b
  a=$(stat -c%s "$CMP_OUT-e3-cold/e2e.jsonl" 2>/dev/null || echo 0)
  sleep 20
  b=$(stat -c%s "$CMP_OUT-e3-cold/e2e.jsonl" 2>/dev/null || echo 0)
  [[ "$a" == "$b" && "$a" != "0" ]] || return 1
  # meta present
  [[ -f "$CMP_OUT-e3-warm/meta.json" && -f "$CMP_OUT-e3-cold/meta.json" ]] || return 1
  return 0
}

start_ablation() {
  local abl_root="$ROOT/experiments/results/abl-chain-$ABL_STAMP"
  mkdir -p "$abl_root" "$WIN/experiments/results/abl-chain-$ABL_STAMP"
  local abl_log="$abl_root/ablation.batch.log"
  local abl_status="$abl_root/ablation.batch.status.json"
  echo "$ABL_COLD|$ABL_WARM|$ABL_RISK" >"$abl_root/RUN_IDS.txt"
  cp -f "$abl_root/RUN_IDS.txt" "$WIN/experiments/results/abl-chain-$ABL_STAMP/" 2>/dev/null || true
  echo "abl-chain-$ABL_STAMP" >"$WIN/experiments/results/.latest_ablation_chain_id"

  nohup bash -c "
    set -uo pipefail
    cd '$ROOT'
    source scripts/kart-env.sh
    kart_ensure_jar
    kart_load_llm_env || true
    STATUS='$abl_status'
    LOG='$abl_log'
    log() { echo \"[\$(date '+%F %T')] \$*\" | tee -a \"\$LOG\"; }
    wstat() {
      python3 - \"\$STATUS\" \"\$1\" \"\$2\" <<'PY'
import json,sys,time
from pathlib import Path
p=Path(sys.argv[1]); stage,state=sys.argv[2],sys.argv[3]
prev={}
if p.is_file():
  try: prev=json.loads(p.read_text())
  except: prev={}
stages=prev.get('stages') or {}
stages[stage]={'state':state,'ts':time.strftime('%Y-%m-%dT%H:%M:%S')}
prev.update({'updated_at':time.strftime('%Y-%m-%dT%H:%M:%S'),'current':stage,'stages':stages,'overall':'running'})
if 'started_at' not in prev: prev['started_at']=prev['updated_at']
p.write_text(json.dumps(prev,indent=2)+'\n')
PY
      cp -f \"\$STATUS\" '$WIN/experiments/results/abl-chain-$ABL_STAMP/' 2>/dev/null || true
    }
    wstat init running
    log '=== START ablation cold $ABL_COLD ==='
    wstat cold running
    if bash scripts/bench-ablation.sh --run-id '$ABL_COLD' --cache cold --trials 1 >>\"\$LOG\" 2>&1; then
      wstat cold ok; log '=== OK cold ==='
    else
      wstat cold failed; log '=== FAIL cold ==='
    fi
    log '=== START ablation warm $ABL_WARM ==='
    wstat warm running
    if bash scripts/bench-ablation.sh --run-id '$ABL_WARM' --cache warm --trials 5 >>\"\$LOG\" 2>&1; then
      wstat warm ok; log '=== OK warm ==='
    else
      wstat warm failed; log '=== FAIL warm ==='
    fi
    log '=== START ablation risk $ABL_RISK ==='
    wstat risk running
    if bash scripts/bench-ablation.sh --run-id '$ABL_RISK' --factor no_coverage --allow-unsafe --cache cold --trials 1 --keep-artifacts >>\"\$LOG\" 2>&1; then
      wstat risk ok; log '=== OK risk ==='
    else
      wstat risk failed; log '=== FAIL risk ==='
    fi
    python3 - \"\$STATUS\" <<'PY'
import json,sys,time
from pathlib import Path
p=Path(sys.argv[1])
st=json.loads(p.read_text())
failed=[k for k,v in (st.get('stages') or {}).items() if v.get('state')=='failed']
st['overall']='failed' if failed else 'ok'
st['finished_at']=time.strftime('%Y-%m-%dT%H:%M:%S')
st['failed_stages']=failed
p.write_text(json.dumps(st,indent=2)+'\n')
print('ablation overall', st['overall'], 'failed', failed)
PY
    cp -f \"\$STATUS\" '$WIN/experiments/results/abl-chain-$ABL_STAMP/' 2>/dev/null || true
    cp -f \"\$LOG\" '$WIN/experiments/results/abl-chain-$ABL_STAMP/' 2>/dev/null || true
    log '=== ablation chain DONE ==='
  " >"$abl_root/nohup.out" 2>&1 &
  local apid=$!
  echo "$apid" >"$abl_root/nohup.pid"
  echo "$apid" >"$WIN/experiments/results/abl-chain-$ABL_STAMP/nohup.pid"
  log "STARTED ablation chain pid=$apid cold=$ABL_COLD warm=$ABL_WARM risk=$ABL_RISK"
  write_watch "ablation_started" "pid=$apid" "{\"ablation_chain\":\"abl-chain-$ABL_STAMP\",\"cold\":\"$ABL_COLD\",\"warm\":\"$ABL_WARM\",\"risk\":\"$ABL_RISK\",\"pid\":$apid}"
}

log "=== watch start cmp=$CMP_ID ==="
write_watch "watching" "waiting for comparative completion"

POLL=60
while true; do
  if cmp_batch_alive; then
    n_warm=$(rows "$CMP_OUT-e3-warm/e2e.jsonl")
    write_watch "watching" "cmp_alive warm_rows=$n_warm"
    log "still running; e3_warm_rows=$n_warm"
    sleep "$POLL"
    continue
  fi

  # Batch process exited — wait for status file to settle
  if ! cmp_complete; then
    write_watch "watching" "batch_exited_waiting_status"
    log "batch pid gone; waiting for e3_cold status..."
    sleep 30
    # If status never gets e3_cold, check if cold dir appeared
    if [[ -f "$CMP_OUT-e3-cold/e2e.jsonl" ]] && [[ $(rows "$CMP_OUT-e3-cold/e2e.jsonl") -ge 280 ]]; then
      log "e3-cold jsonl present without status — proceed to stability check"
    else
      # timeout guard: if gone > 2h after pid death without cold, abort
      sleep "$POLL"
      continue
    fi
  fi

  if results_ready; then
    log "comparative results ready — launching ablation"
    write_watch "cmp_complete" "launching ablation"
    # Mirror key status to Windows
    cp -f "$CMP_OUT/batch.status.json" "$WIN/experiments/results/$CMP_ID/" 2>/dev/null || true
    cp -f "$CMP_OUT/batch.log" "$WIN/experiments/results/$CMP_ID/" 2>/dev/null || true
    start_ablation
    write_watch "done" "ablation launched"
    log "=== watch DONE ==="
    exit 0
  fi

  write_watch "watching" "batch_done_waiting_stable_files"
  log "batch finished but results not stable/complete yet"
  sleep "$POLL"
done
