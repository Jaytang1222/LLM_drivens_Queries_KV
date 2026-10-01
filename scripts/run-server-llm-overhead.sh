#!/usr/bin/env bash
# Server: plan-only LLM overhead vs CBO (4 opportunity queries, no 1.5s wall).
set -euo pipefail
source ~/.kart_server_env
cd "$KART_DST"
set -a
# shellcheck disable=SC1091
source .env
set +a
export KART_CBO_LLM_BUDGET_MS="${KART_CBO_LLM_BUDGET_MS:-120000}"
echo "PROXY=$HTTPS_PROXY budget_ms=$KART_CBO_LLM_BUDGET_MS model=$LLM_MODEL"
ss -ltn | grep -E ':17890\s' || { echo "FAIL: Clash HTTP reverse :17890 missing"; exit 1; }

RUN_ID="cbo-llm-overhead-v1-$(date +%Y%m%d-%H%M%S)"
echo "run_id=$RUN_ID"
./scripts/bench-plan.sh \
  --suite experiments/suites/plan-cbo-llm-overhead-v1.yaml \
  --run-id "$RUN_ID" \
  --trials 1 \
  --arm cbo,cbo-llm-proposal

OUT="experiments/results/$RUN_ID"
python3 - <<PY
import json
from pathlib import Path
from statistics import median
out = Path("$OUT")
rows = []
for line in (out/"plan.jsonl").read_text(encoding="utf-8").splitlines():
  if line.strip():
    rows.append(json.loads(line))

cbo = {r["query_id"]: r for r in rows if r.get("arm") == "cbo"}
hyb = {r["query_id"]: r for r in rows
       if (r.get("arm") or "").startswith("cbo-llm-proposal")}
print("=== per-query plan overhead (hybrid - cbo) ===")
deltas = []
timeouts = 0
triggered = 0
for qid in sorted(cbo):
  c, h = cbo[qid], hyb[qid]
  ct, ht = c.get("t_plan_ms"), h.get("t_plan_ms")
  fb = h.get("fallback_reason")
  llm_lat = h.get("llm_latency_ms")
  calls = h.get("llm_calls")
  trig = h.get("llm_triggered")
  budget = h.get("llm_budget_ms")
  delta = None if ct is None or ht is None else int(ht) - int(ct)
  if delta is not None:
    deltas.append(delta)
  if fb == "llm_timeout":
    timeouts += 1
  if trig is True or str(trig).lower() == "true":
    triggered += 1
  print(
    f"{qid}: cbo_t_plan={ct} hybrid_t_plan={ht} delta={delta} "
    f"llm_calls={calls} llm_triggered={trig} llm_latency_ms={llm_lat} "
    f"budget_ms={budget} fallback={fb} proposal={h.get('proposal_plan_id')} "
    f"selected={h.get('selected_plan_id') or h.get('plan_id')}"
  )

print(
  "n=", len(deltas),
  "delta_ms median=", (median(deltas) if deltas else None),
  "mean=", (round(sum(deltas) / len(deltas), 1) if deltas else None),
)
print("llm_triggered=", triggered, "/", len(hyb))
print("llm_timeout_count=", timeouts)
print("OUT=$OUT")
if timeouts:
  raise SystemExit("UNEXPECTED llm_timeout after wall removal")
if triggered < len(hyb):
  raise SystemExit("EXPECTED all queries to trigger LLM for overhead measurement")
print("OVERHEAD_OK")
PY
