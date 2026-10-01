#!/usr/bin/env bash
# Launch cbo-llm pilot: sync, rebuild, E2 plan then E3 warm (background-friendly).
set -euo pipefail
ROOT="${KART_ROOT:-/home/jaytang/projects/llm-kv}"
SRC="${KART_SRC:-/mnt/f/Projects/LLM_KV}"
cd "$ROOT"
# shellcheck disable=SC1091
source scripts/kart-env.sh

log() { echo "[$(date '+%F %T')] $*"; }

# Sync critical sources from Windows mount
rsync -a "$SRC/src/main/java/kart/bench/CboLlmProposal"*.java "$ROOT/src/main/java/kart/bench/" 2>/dev/null || true
rsync -a "$SRC/src/main/java/kart/bench/ArmRegistry.java" "$ROOT/src/main/java/kart/bench/"
rsync -a "$SRC/src/main/java/kart/llm/LlmOptions.java" "$SRC/src/main/java/kart/llm/OpenAiCompatibleClient.java" "$ROOT/src/main/java/kart/llm/"
rsync -a "$SRC/experiments/suites/plan-cbo-llm-pilot.yaml" "$SRC/experiments/suites/e2e-cbo-llm-pilot.yaml" "$SRC/experiments/suites/e2e-cbo-llm-pilot-cache.yaml" "$ROOT/experiments/suites/"
rsync -a "$SRC/experiments/workloads/bound_ir_cbo_llm_pilot_v1.json" \
  "$SRC/experiments/workloads/bound_ir_cbo_llm_pilot_v1.oracle.json" \
  "$SRC/experiments/workloads/bound_ir_cbo_llm_pilot_v1.provenance.json" \
  "$ROOT/experiments/workloads/" 2>/dev/null || true

python3 - <<'PY'
import json
from pathlib import Path
w=json.loads(Path("experiments/workloads/bound_ir_cbo_llm_pilot_v1.json").read_text())
o=json.loads(Path("experiments/workloads/bound_ir_cbo_llm_pilot_v1.oracle.json").read_text())
raw=o.get("answers") or o
if isinstance(raw, list):
    ans={a.get("query_id"): a for a in raw if isinstance(a, dict)}
elif isinstance(raw, dict):
    ans=raw
else:
    raise SystemExit("unexpected oracle shape")
ids=[q["query_id"] for q in w["queries"]]
print("pilot queries:", ids)
missing=[i for i in ids if i not in ans]
print("oracle n=", len(ans), "missing:", missing)
if missing:
    raise SystemExit("oracle incomplete")
PY

log "doctor"
./scripts/kart.sh doctor >/tmp/pilot-doctor.out 2>&1 || { tail -20 /tmp/pilot-doctor.out; exit 1; }
grep -E 'doctor: OK|region_server' /tmp/pilot-doctor.out || true

log "rebuild jar"
kart_ensure_jar
kart_load_llm_env || true

PLAN_ID="cbo-llm-pilot-v1-plan-$(date +%Y%m%d-%H%M%S)"
E2E_ID="cbo-llm-pilot-v1-warm-$(date +%Y%m%d-%H%M%S)"
STATUS="$ROOT/experiments/results/cbo-llm-pilot-chain/status.json"
mkdir -p "$(dirname "$STATUS")"
LOG="$ROOT/experiments/results/cbo-llm-pilot-chain/chain.log"
mkdir -p "$(dirname "$LOG")"

{
  echo "plan_run_id=$PLAN_ID"
  echo "e2e_run_id=$E2E_ID"
} | tee -a "$LOG"

python3 - "$STATUS" "$PLAN_ID" "$E2E_ID" <<'PY'
import json,sys,time
from pathlib import Path
p=Path(sys.argv[1]); p.parent.mkdir(parents=True, exist_ok=True)
st={"overall":"running","started_at":time.strftime("%Y-%m-%dT%H:%M:%S"),
    "plan_run_id":sys.argv[2],"e2e_run_id":sys.argv[3],
    "stages":{"plan":{"state":"pending"},"e2e_warm":{"state":"pending"}}}
p.write_text(json.dumps(st,indent=2)+"\n")
PY

log "START E2 plan $PLAN_ID"
python3 - "$STATUS" <<'PY'
import json,sys,time
from pathlib import Path
p=Path(sys.argv[1]); st=json.loads(p.read_text())
st["stages"]["plan"]={"state":"running","ts":time.strftime("%Y-%m-%dT%H:%M:%S")}
st["current"]="plan"; st["updated_at"]=st["stages"]["plan"]["ts"]
p.write_text(json.dumps(st,indent=2)+"\n")
PY

if bash scripts/bench-plan.sh \
  --suite experiments/suites/plan-cbo-llm-pilot.yaml \
  --run-id "$PLAN_ID" \
  --trials 3 >>"$LOG" 2>&1; then
  log "OK plan"
  python3 - "$STATUS" <<'PY'
import json,sys,time
from pathlib import Path
p=Path(sys.argv[1]); st=json.loads(p.read_text())
st["stages"]["plan"]={"state":"ok","ts":time.strftime("%Y-%m-%dT%H:%M:%S")}
st["updated_at"]=st["stages"]["plan"]["ts"]
p.write_text(json.dumps(st,indent=2)+"\n")
PY
else
  log "FAIL plan"
  python3 - "$STATUS" <<'PY'
import json,sys,time
from pathlib import Path
p=Path(sys.argv[1]); st=json.loads(p.read_text())
st["stages"]["plan"]={"state":"failed","ts":time.strftime("%Y-%m-%dT%H:%M:%S")}
st["overall"]="failed"; st["updated_at"]=st["stages"]["plan"]["ts"]
p.write_text(json.dumps(st,indent=2)+"\n")
PY
  exit 1
fi

log "START E3 warm $E2E_ID"
python3 - "$STATUS" <<'PY'
import json,sys,time
from pathlib import Path
p=Path(sys.argv[1]); st=json.loads(p.read_text())
st["stages"]["e2e_warm"]={"state":"running","ts":time.strftime("%Y-%m-%dT%H:%M:%S")}
st["current"]="e2e_warm"; st["updated_at"]=st["stages"]["e2e_warm"]["ts"]
p.write_text(json.dumps(st,indent=2)+"\n")
PY

if bash scripts/bench-e2e.sh \
  --suite experiments/suites/e2e-cbo-llm-pilot.yaml \
  --run-id "$E2E_ID" \
  --cache warm \
  --trials 3 >>"$LOG" 2>&1; then
  log "OK e2e_warm"
  python3 - "$STATUS" <<'PY'
import json,sys,time
from pathlib import Path
p=Path(sys.argv[1]); st=json.loads(p.read_text())
st["stages"]["e2e_warm"]={"state":"ok","ts":time.strftime("%Y-%m-%dT%H:%M:%S")}
st["overall"]="ok"; st["finished_at"]=st["stages"]["e2e_warm"]["ts"]
st["updated_at"]=st["finished_at"]
p.write_text(json.dumps(st,indent=2)+"\n")
PY
else
  log "FAIL e2e_warm"
  python3 - "$STATUS" <<'PY'
import json,sys,time
from pathlib import Path
p=Path(sys.argv[1]); st=json.loads(p.read_text())
st["stages"]["e2e_warm"]={"state":"failed","ts":time.strftime("%Y-%m-%dT%H:%M:%S")}
st["overall"]="failed"; st["finished_at"]=st["stages"]["e2e_warm"]["ts"]
st["updated_at"]=st["finished_at"]
p.write_text(json.dumps(st,indent=2)+"\n")
PY
  exit 1
fi

log "pilot chain DONE plan=$PLAN_ID e2e=$E2E_ID"
# mirror status to Windows mount if present
cp -f "$STATUS" "$SRC/experiments/results/cbo-llm-pilot-chain/" 2>/dev/null || mkdir -p "$SRC/experiments/results/cbo-llm-pilot-chain" && cp -f "$STATUS" "$LOG" "$SRC/experiments/results/cbo-llm-pilot-chain/" 2>/dev/null || true
echo "STATUS=$STATUS"
echo "LOG=$LOG"
