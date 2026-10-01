#!/usr/bin/env bash
# E2E 4q: CBO vs local Ollama hybrid — does NOT rewrite project .env DeepSeek defaults.
set -euo pipefail
source ~/.kart_server_env
cd "$KART_DST"
case "$KART_DST" in
  /home/tyq/*) ;;
  *) echo "FAIL: KART_DST=$KART_DST not under /home/tyq"; exit 1 ;;
esac

export PATH="$HOME/.local/bin:$PATH"
export OLLAMA_HOST=127.0.0.1:11434
export OLLAMA_MODELS="${OLLAMA_MODELS:-$HOME/.ollama}"

ss -ltn | grep -qE ':11434\s' || {
  echo "FAIL: ollama not on 127.0.0.1:11434"
  exit 1
}

MODEL="${OLLAMA_MODEL:-qwen2.5:1.5b-instruct}"
OLLAMA_ENV="${OLLAMA_MODELS}/kart_llm_overlay.env"
cat >"$OLLAMA_ENV" <<EOF
LLM_PROVIDER=ollama
LLM_BASE_URL=http://127.0.0.1:11434/v1
LLM_MODEL=${MODEL}
LLM_API_KEY=ollama
LLM_JSON_MODE=false
EOF
export LLM_ENV_FILE="$OLLAMA_ENV"
export LLM_PROVIDER=ollama
export LLM_BASE_URL="http://127.0.0.1:11434/v1"
export LLM_MODEL="$MODEL"
export LLM_API_KEY="ollama"
export LLM_JSON_MODE=false
export KART_CBO_LLM_BUDGET_MS="${KART_CBO_LLM_BUDGET_MS:-120000}"
export KART_CBO_LLM_MAX_TOKENS="${KART_CBO_LLM_MAX_TOKENS:-32}"
export KART_CBO_LLM_SPECULATE="${KART_CBO_LLM_SPECULATE:-1}"
export KART_CBO_LLM_SPECULATE_K="${KART_CBO_LLM_SPECULATE_K:-1}"
export KART_CBO_LLM_SPECULATE_PT="${KART_CBO_LLM_SPECULATE_PT:-$KART_CBO_LLM_SPECULATE}"

export no_proxy="127.0.0.1,localhost,${no_proxy:-}"
export NO_PROXY="127.0.0.1,localhost,${NO_PROXY:-}"
_jto="${JAVA_TOOL_OPTIONS:-}"
_jto="$(printf '%s' "$_jto" | sed -E 's/-Dhttps\.proxyHost=[^ ]*//g; s/-Dhttps\.proxyPort=[^ ]*//g; s/-Dhttp\.proxyHost=[^ ]*//g; s/-Dhttp\.proxyPort=[^ ]*//g; s/  +/ /g; s/^ //; s/ $//')"
export JAVA_TOOL_OPTIONS="${_jto}"

echo "LOCAL_OLLAMA E2E LLM_ENV_FILE=$LLM_ENV_FILE model=$LLM_MODEL budget=$KART_CBO_LLM_BUDGET_MS max_tokens=$KART_CBO_LLM_MAX_TOKENS speculate=$KART_CBO_LLM_SPECULATE k=$KART_CBO_LLM_SPECULATE_K"

RUN_ID="cbo-llm-overhead-e2e-local-ollama-$(date +%Y%m%d-%H%M%S)"
echo "run_id=$RUN_ID"
./scripts/bench-e2e.sh \
  --suite experiments/suites/e2e-cbo-llm-overhead-v1.yaml \
  --run-id "$RUN_ID" \
  --trials 1 \
  --cache warm \
  --arm cbo,cbo-llm-proposal

OUT="experiments/results/$RUN_ID"
JSONL="$OUT/e2e.jsonl"
[[ -f "$JSONL" ]] || JSONL="$OUT/plan.jsonl"
export OUT JSONL
python3 - <<'PY'
import json
import os
from pathlib import Path
from statistics import median

# DeepSeek E2E anchor (same suite)
DS = {
  "a2_topk_dtw_wide": {"cbo_plan": 34, "cbo_exec": 107, "hyb_plan": 1123, "hyb_exec": 120, "hyb_llm": 1079, "hyb_pid": "P_T"},
  "a2_tz_hard_a": {"cbo_plan": 1374, "cbo_exec": 1228, "hyb_plan": 10814, "hyb_exec": 1025, "hyb_llm": 8564, "hyb_pid": "P_T"},
  "a2_tz_hard_d": {"cbo_plan": 1808, "cbo_exec": 1673, "hyb_plan": 4220, "hyb_exec": 1260, "hyb_llm": 1190, "hyb_pid": "P_T"},
  "topk_st_3": {"cbo_plan": 32, "cbo_exec": 87, "hyb_plan": 1062, "hyb_exec": 58, "hyb_llm": 1019, "hyb_pid": "P_T"},
}

path = Path(os.environ["JSONL"])
rows = [json.loads(l) for l in path.read_text(encoding="utf-8").splitlines() if l.strip()]
cbo = {r["query_id"]: r for r in rows if r.get("arm") == "cbo"}
hyb = {r["query_id"]: r for r in rows if (r.get("arm") or "").startswith("cbo-llm-proposal")}

print("jsonl=", path)
print("=== THIS RUN (CBO / Ollama hybrid) ===")
print(
  f"{'query':22} {'arm':18} {'t_plan':>8} {'t_exec':>8} {'t_e2e':>8} "
  f"{'llm_lat':>8} {'search':>8} {'await':>8} {'spec':>4} {'prefer':6} plan"
)
timeouts = 0
for qid in sorted(cbo):
  for label, r in (("cbo", cbo[qid]), ("ollama-hybrid", hyb[qid])):
    fb = r.get("fallback_reason")
    if label.startswith("ollama") and fb == "llm_timeout":
      timeouts += 1
    spec = r.get("speculative_used", r.get("speculative_pt_used"))
    print(
      f"{qid:22} {label:18} {r.get('t_plan_ms')!s:>8} {r.get('t_exec_ms')!s:>8} "
      f"{r.get('t_e2e_ms')!s:>8} {r.get('llm_latency_ms')!s:>8} "
      f"{r.get('t_cbo_search_ms')!s:>8} {r.get('t_spec_await_ms')!s:>8} "
      f"{str(spec):>4} {str(r.get('prefer_plan_id') or '-'):6} "
      f"{r.get('selected_plan_id') or r.get('plan_id')}"
    )

print("\n=== E2E beat CBO? (same-run wall) ===")
print(f"{'query':22} {'cbo_e2e':>8} {'hyb_e2e':>8} {'delta':>8} {'win':>4} {'S':>8} {'L':>8}")
for qid in sorted(cbo):
  c, h = cbo[qid], hyb[qid]
  ce = int(c.get("t_e2e_ms") or ((c.get("t_plan_ms") or 0) + (c.get("t_exec_ms") or 0)))
  he = int(h.get("t_e2e_ms") or 0)
  S = int(c.get("t_exec_ms") or 0) - int(h.get("t_exec_ms") or 0)
  L = int(h.get("llm_latency_ms") or 0)
  print(
    f"{qid:22} {ce:8d} {he:8d} {he - ce:8d} "
    f"{'YES' if he < ce else 'NO':>4} {S:8d} {L:8d}"
  )

print("\n=== COMPARE plan/exec (ms) ===")
print(
  f"{'query':22} {'cbo_plan':>8} {'cbo_exec':>8} "
  f"{'ds_plan':>8} {'ds_exec':>8} {'ol_plan':>8} {'ol_exec':>8} "
  f"{'ds_pid':>6} {'ol_pid':>6}"
)
for qid in sorted(DS):
  d = DS[qid]
  c, h = cbo[qid], hyb[qid]
  print(
    f"{qid:22} {d['cbo_plan']:8d} {d['cbo_exec']:8d} "
    f"{d['hyb_plan']:8d} {d['hyb_exec']:8d} "
    f"{int(h['t_plan_ms']):8d} {int(h['t_exec_ms']):8d} "
    f"{d['hyb_pid']:>6} {(h.get('selected_plan_id') or h.get('plan_id')):>6}"
  )

print("OUT=", os.environ["OUT"])
print("llm_timeout_count=", timeouts)
if timeouts:
  raise SystemExit("UNEXPECTED llm_timeout")
print("E2E_LOCAL_OLLAMA_OK")
PY
