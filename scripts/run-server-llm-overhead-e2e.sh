#!/usr/bin/env bash
# Server E2E: report t_plan and t_exec separately for CBO vs hybrid LLM.
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

RUN_ID="cbo-llm-overhead-e2e-v1-$(date +%Y%m%d-%H%M%S)"
echo "run_id=$RUN_ID"
./scripts/bench-e2e.sh \
  --suite experiments/suites/e2e-cbo-llm-overhead-v1.yaml \
  --run-id "$RUN_ID" \
  --trials 1 \
  --cache warm \
  --arm cbo,cbo-llm-proposal

OUT="experiments/results/$RUN_ID"
# Prefer e2e.jsonl; fall back to plan.jsonl naming if suite wrote differently.
JSONL="$OUT/e2e.jsonl"
[[ -f "$JSONL" ]] || JSONL="$OUT/plan.jsonl"
python3 - <<PY
import json
from pathlib import Path
from statistics import median
path = Path("$JSONL")
rows = [json.loads(l) for l in path.read_text(encoding="utf-8").splitlines() if l.strip()]
cbo = {r["query_id"]: r for r in rows if r.get("arm") == "cbo"}
hyb = {r["query_id"]: r for r in rows if (r.get("arm") or "").startswith("cbo-llm-proposal")}
print("jsonl=", path)
print("=== per-query t_plan / t_exec / t_e2e (ms) ===")
print(f"{'query':22} {'arm':18} {'t_plan':>8} {'t_exec':>8} {'t_e2e':>8} {'llm_lat':>8} {'fallback':12} plan")
d_plan, d_exec, d_e2e = [], [], []
timeouts = 0
for qid in sorted(cbo):
  for label, r in (("cbo", cbo[qid]), ("cbo-llm-proposal", hyb[qid])):
    tp, te, te2 = r.get("t_plan_ms"), r.get("t_exec_ms"), r.get("t_e2e_ms")
    fb = r.get("fallback_reason")
    llm = r.get("llm_latency_ms")
    pid = r.get("selected_plan_id") or r.get("plan_id")
    if label.startswith("cbo-llm") and fb == "llm_timeout":
      timeouts += 1
    print(f"{qid:22} {label:18} {tp!s:>8} {te!s:>8} {te2!s:>8} {llm!s:>8} {str(fb):12} {pid}")
  c, h = cbo[qid], hyb[qid]
  if c.get("t_plan_ms") is not None and h.get("t_plan_ms") is not None:
    d_plan.append(int(h["t_plan_ms"]) - int(c["t_plan_ms"]))
  if c.get("t_exec_ms") is not None and h.get("t_exec_ms") is not None:
    d_exec.append(int(h["t_exec_ms"]) - int(c["t_exec_ms"]))
  if c.get("t_e2e_ms") is not None and h.get("t_e2e_ms") is not None:
    d_e2e.append(int(h["t_e2e_ms"]) - int(c["t_e2e_ms"]))
  print(f"{'':22} {'delta(hyb-cbo)':18} {d_plan[-1] if d_plan else None!s:>8} {d_exec[-1] if d_exec else None!s:>8} {d_e2e[-1] if d_e2e else None!s:>8}")

def fmt(xs):
  if not xs:
    return "n/a"
  return f"median={median(xs)} mean={round(sum(xs)/len(xs),1)}"

print("=== hybrid - cbo deltas ===")
print("delta_t_plan_ms ", fmt(d_plan), d_plan)
print("delta_t_exec_ms ", fmt(d_exec), d_exec)
print("delta_t_e2e_ms  ", fmt(d_e2e), d_e2e)
print("llm_timeout_count=", timeouts)
print("OUT=$OUT")
if timeouts:
  raise SystemExit("UNEXPECTED llm_timeout")
print("E2E_OVERHEAD_OK")
PY
