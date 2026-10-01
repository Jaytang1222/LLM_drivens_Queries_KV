#!/usr/bin/env bash
# AIS opportunity E2E: CBO vs local Ollama hybrid — /home/tyq only, no DeepSeek .env rewrite.
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
export KART_CBO_LLM_SPECULATE_PT="${KART_CBO_LLM_SPECULATE_PT:-1}"
export KART_EXPERIMENT_MANIFEST=ais_v1_ready

export no_proxy="127.0.0.1,localhost,${no_proxy:-}"
export NO_PROXY="127.0.0.1,localhost,${NO_PROXY:-}"
_jto="${JAVA_TOOL_OPTIONS:-}"
_jto="$(printf '%s' "$_jto" | sed -E 's/-Dhttps\.proxyHost=[^ ]*//g; s/-Dhttps\.proxyPort=[^ ]*//g; s/-Dhttp\.proxyHost=[^ ]*//g; s/-Dhttp\.proxyPort=[^ ]*//g; s/  +/ /g; s/^ //; s/ $//')"
export JAVA_TOOL_OPTIONS="${_jto}"

echo "AIS OLLAMA E2E model=$LLM_MODEL manifest=$KART_EXPERIMENT_MANIFEST max_tokens=$KART_CBO_LLM_MAX_TOKENS speculate_pt=$KART_CBO_LLM_SPECULATE_PT"
python3 - <<'PY'
import json
from pathlib import Path
w = json.loads(Path("experiments/workloads/bound_ir_ais_opportunity_v1.json").read_text())
print("queries", [q["query_id"] for q in w["queries"]])
PY

RUN_ID="ais-llm-overhead-e2e-local-ollama-$(date +%Y%m%d-%H%M%S)"
echo "run_id=$RUN_ID"
./scripts/bench-e2e.sh \
  --suite experiments/suites/e2e-ais-llm-overhead-v1.yaml \
  --run-id "$RUN_ID" \
  --trials 1 \
  --cache warm \
  --arm cbo,cbo-llm-proposal

OUT="experiments/results/$RUN_ID"
JSONL="$OUT/e2e.jsonl"
export OUT JSONL
python3 - <<'PY'
import json, os
from pathlib import Path
path = Path(os.environ["JSONL"])
rows = [json.loads(l) for l in path.read_text(encoding="utf-8").splitlines() if l.strip()]
cbo = {r["query_id"]: r for r in rows if r.get("arm") == "cbo"}
hyb = {r["query_id"]: r for r in rows if (r.get("arm") or "").startswith("cbo-llm-proposal")}
print("=== AIS plan / exec (ms) ===")
print(
  f"{'query':28} {'arm':16} {'t_plan':>8} {'t_exec':>8} {'t_e2e':>8} "
  f"{'llm':>8} {'search':>8} {'await':>8} {'spec':>4} {'pid':6}"
)
for qid in sorted(cbo):
  for label, r in (("cbo", cbo[qid]), ("ollama", hyb[qid])):
    print(
      f"{qid:28} {label:16} {r.get('t_plan_ms')!s:>8} {r.get('t_exec_ms')!s:>8} "
      f"{r.get('t_e2e_ms')!s:>8} {r.get('llm_latency_ms')!s:>8} "
      f"{r.get('t_cbo_search_ms')!s:>8} {r.get('t_spec_await_ms')!s:>8} "
      f"{str(r.get('speculative_pt_used')):>4} "
      f"{(r.get('selected_plan_id') or r.get('plan_id') or '?'):6}"
    )
print("\n=== E2E beat CBO? ===")
print(f"{'query':28} {'cbo_e2e':>8} {'hyb_e2e':>8} {'delta':>8} {'win':>4} {'S':>8} {'L':>8}")
for qid in sorted(cbo):
  c, h = cbo[qid], hyb[qid]
  ce = int(c.get("t_e2e_ms") or ((c.get("t_plan_ms") or 0) + (c.get("t_exec_ms") or 0)))
  he = int(h.get("t_e2e_ms") or 0)
  S = int(c.get("t_exec_ms") or 0) - int(h.get("t_exec_ms") or 0)
  L = int(h.get("llm_latency_ms") or 0)
  print(
    f"{qid:28} {ce:8d} {he:8d} {he - ce:8d} "
    f"{'YES' if he < ce else 'NO':>4} {S:8d} {L:8d}"
  )
print("OUT=", os.environ["OUT"])
print("AIS_OLLAMA_E2E_OK")
PY
