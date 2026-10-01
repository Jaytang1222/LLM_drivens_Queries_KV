#!/usr/bin/env bash
# Push Google wiring + run: (1) curl probe DeepSeek vs Gemini, (2) 1-query plan overhead each.
set -euo pipefail
KEY="${HOME}/.ssh/id_ed25519_kart_server"
HOST=tyq@10.242.104.108
ROOT=/mnt/f/Projects/LLM_KV

scp -i "$KEY" -o IdentitiesOnly=yes -o BatchMode=yes \
  "$ROOT/.env" \
  "$ROOT/scripts/compare-llm-providers-probe.sh" \
  "$ROOT/scripts/kart-env.sh" \
  "$ROOT/scripts/run-server-llm-overhead.sh" \
  "$HOST:/tmp/"

ssh -i "$KEY" -o IdentitiesOnly=yes -o BatchMode=yes "$HOST" 'bash -s' <<'REMOTE'
set -euo pipefail
source ~/.kart_server_env
cd "$KART_DST"
cp /tmp/.env .env
cp /tmp/compare-llm-providers-probe.sh scripts/
cp /tmp/kart-env.sh scripts/
cp /tmp/run-server-llm-overhead.sh scripts/
sed -i 's/\r$//' .env scripts/compare-llm-providers-probe.sh scripts/kart-env.sh scripts/run-server-llm-overhead.sh
chmod +x scripts/compare-llm-providers-probe.sh scripts/run-server-llm-overhead.sh

ss -ltn | grep -E ':17890\s' || { echo "FAIL: Clash HTTP reverse :17890 missing"; exit 1; }

echo "======== 1) compact-prompt curl probe ========"
PROXY=http://127.0.0.1:17890 REPEATS=3 MAX_TIME=45 \
  bash scripts/compare-llm-providers-probe.sh

run_overhead() {
  local provider="$1"
  echo ""
  echo "======== 2) plan overhead provider=$provider ========"
  # Fresh env each run
  unset LLM_PROVIDER LLM_BASE_URL LLM_MODEL LLM_API_KEY LLM_JSON_MODE
  set -a
  # shellcheck disable=SC1091
  source .env
  set +a
  if [[ "$provider" == "google" ]]; then
    export LLM_PROVIDER=google
    export LLM_API_KEY="$GOOGLE_API_KEY"
    export LLM_BASE_URL="${GOOGLE_LLM_BASE_URL%/}"
    export LLM_MODEL="$GOOGLE_LLM_MODEL"
    export LLM_JSON_MODE=false
  else
    export LLM_PROVIDER=deepseek
    # keep DeepSeek LLM_* from .env
  fi
  export KART_CBO_LLM_BUDGET_MS=120000
  echo "active model=$LLM_MODEL base=$LLM_BASE_URL key_len=${#LLM_API_KEY}"

  RUN_ID="cbo-llm-overhead-${provider}-$(date +%Y%m%d-%H%M%S)"
  echo "run_id=$RUN_ID"
  # Minimal: first query only via temporary 1-query workload extract
  python3 - <<PY
import json
from pathlib import Path
src = Path("experiments/workloads/bound_ir_cbo_llm_overhead_v1.json")
ors = Path("experiments/workloads/bound_ir_cbo_llm_overhead_v1.oracle.json")
wl = json.loads(src.read_text(encoding="utf-8"))
oq = json.loads(ors.read_text(encoding="utf-8"))
# keep first query only
q0 = wl["queries"][0]
qid = q0["query_id"]
wl["queries"] = [q0]
wl["id"] = "bound_ir_cbo_llm_overhead_min1"
ors_out = {"queries": {qid: oq["queries"][qid]}} if "queries" in oq else oq
# handle oracle shapes
if isinstance(oq, dict) and "queries" in oq:
  ors_out = dict(oq)
  ors_out["queries"] = {qid: oq["queries"][qid]}
elif isinstance(oq, dict) and qid in oq:
  ors_out = {qid: oq[qid]}
else:
  ors_out = oq
Path("experiments/workloads/bound_ir_cbo_llm_overhead_min1.json").write_text(
  json.dumps(wl, ensure_ascii=False, indent=2), encoding="utf-8")
Path("experiments/workloads/bound_ir_cbo_llm_overhead_min1.oracle.json").write_text(
  json.dumps(ors_out, ensure_ascii=False, indent=2), encoding="utf-8")
print("min1_query", qid)
PY

  cat > experiments/suites/plan-cbo-llm-overhead-min1.yaml <<'YAML'
kind: compare
id: plan-cbo-llm-overhead-min1
workload: experiments/workloads/bound_ir_cbo_llm_overhead_min1.json
oracle: experiments/workloads/bound_ir_cbo_llm_overhead_min1.oracle.json
manifest: tdrive_v1_ready
require_oracle: true
trials: 1
stages:
  - plan
arms:
  - cbo
  - cbo-llm-proposal
YAML

  ./scripts/bench-plan.sh \
    --suite experiments/suites/plan-cbo-llm-overhead-min1.yaml \
    --run-id "$RUN_ID" \
    --trials 1 \
    --arm cbo,cbo-llm-proposal

  OUT="experiments/results/$RUN_ID"
  python3 - <<PY
import json
from pathlib import Path
out = Path("$OUT")
rows = [json.loads(l) for l in (out/"plan.jsonl").read_text(encoding="utf-8").splitlines() if l.strip()]
cbo = {r["query_id"]: r for r in rows if r.get("arm") == "cbo"}
hyb = {r["query_id"]: r for r in rows if (r.get("arm") or "").startswith("cbo-llm-proposal")}
print("provider=$provider model=$LLM_MODEL")
for qid in sorted(cbo):
  c, h = cbo[qid], hyb[qid]
  ct, ht = c.get("t_plan_ms"), h.get("t_plan_ms")
  delta = None if ct is None or ht is None else int(ht) - int(ct)
  print(
    f"{qid}: cbo_t_plan={ct} hybrid_t_plan={ht} delta={delta} "
    f"llm_latency_ms={h.get('llm_latency_ms')} llm_triggered={h.get('llm_triggered')} "
    f"fallback={h.get('fallback_reason')} proposal={h.get('proposal_plan_id')} "
    f"selected={h.get('selected_plan_id') or h.get('plan_id')}"
  )
print("OUT=$OUT")
PY
}

run_overhead deepseek
run_overhead google

echo ""
echo "======== DONE ========"
REMOTE
