#!/usr/bin/env bash
# Plan-only 4q overhead with local Ollama overlay — does NOT rewrite project .env DeepSeek defaults.
set -euo pipefail
source ~/.kart_server_env
cd "$KART_DST"
case "$KART_DST" in
  /home/tyq/*) ;;
  *) echo "FAIL: KART_DST=$KART_DST not under /home/tyq"; exit 1 ;;
esac

# Ensure user-local ollama on PATH (never /usr/local)
export PATH="$HOME/.local/bin:$PATH"
export OLLAMA_HOST=127.0.0.1:11434
export OLLAMA_MODELS="${OLLAMA_MODELS:-$HOME/.ollama}"

ss -ltn | grep -qE ':11434\s' || {
  echo "FAIL: ollama not on 127.0.0.1:11434 — run scripts/install-ollama-on-server.sh first"
  exit 1
}

# Temporary env file so kart_load_llm_env (via bench-plan.sh) does not reload DeepSeek .env
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

# Java must NOT send 127.0.0.1 through Clash
export no_proxy="127.0.0.1,localhost,${no_proxy:-}"
export NO_PROXY="127.0.0.1,localhost,${NO_PROXY:-}"
# Strip proxy JVM flags for this local-LLM run
_jto="${JAVA_TOOL_OPTIONS:-}"
_jto="$(printf '%s' "$_jto" | sed -E 's/-Dhttps\.proxyHost=[^ ]*//g; s/-Dhttps\.proxyPort=[^ ]*//g; s/-Dhttp\.proxyHost=[^ ]*//g; s/-Dhttp\.proxyPort=[^ ]*//g; s/  +/ /g; s/^ //; s/ $//')"
export JAVA_TOOL_OPTIONS="${_jto}"

echo "LOCAL_OLLAMA overlay LLM_ENV_FILE=$LLM_ENV_FILE model=$LLM_MODEL base=$LLM_BASE_URL budget=$KART_CBO_LLM_BUDGET_MS"
echo "JAVA_TOOL_OPTIONS=$JAVA_TOOL_OPTIONS"

RUN_ID="cbo-llm-overhead-local-ollama-$(date +%Y%m%d-%H%M%S)"
echo "run_id=$RUN_ID"
./scripts/bench-plan.sh \
  --suite experiments/suites/plan-cbo-llm-overhead-v1.yaml \
  --run-id "$RUN_ID" \
  --trials 1 \
  --arm cbo,cbo-llm-proposal

OUT="experiments/results/$RUN_ID"
export OUT
python3 - <<'PY'
import json
import os
from pathlib import Path
from statistics import median

BASELINE = {
  "a2_topk_dtw_wide": {"cbo": 37, "hybrid": 1143, "delta": 1106, "llm_lat": 1097},
  "a2_tz_hard_a": {"cbo": 1409, "hybrid": 6980, "delta": 5571, "llm_lat": 4701},
  "a2_tz_hard_d": {"cbo": 2182, "hybrid": 19296, "delta": 17114, "llm_lat": 16059},
  "topk_st_3": {"cbo": 39, "hybrid": 6022, "delta": 5983, "llm_lat": 5981},
}

out = Path(os.environ["OUT"])
rows = [json.loads(line) for line in (out / "plan.jsonl").read_text(encoding="utf-8").splitlines() if line.strip()]
cbo = {r["query_id"]: r for r in rows if r.get("arm") == "cbo"}
hyb = {r["query_id"]: r for r in rows if (r.get("arm") or "") == "cbo-llm-proposal"}

print("=== DeepSeek BASELINE (cbo-llm-overhead-v1-20260929-120807) ===")
for qid in sorted(BASELINE):
  b = BASELINE[qid]
  print(f"{qid}: cbo={b['cbo']} hybrid={b['hybrid']} delta={b['delta']} llm_lat={b['llm_lat']}")

print("\n=== LOCAL OLLAMA this run ===")
deltas, lats = [], []
timeouts = 0
triggered = 0
for qid in sorted(cbo):
  c, h = cbo[qid], hyb[qid]
  ct, ht = c.get("t_plan_ms"), h.get("t_plan_ms")
  d = None if ct is None or ht is None else int(ht) - int(ct)
  lat = h.get("llm_latency_ms")
  if d is not None:
    deltas.append(d)
  if lat is not None:
    lats.append(int(lat))
  if h.get("fallback_reason") == "llm_timeout":
    timeouts += 1
  if h.get("llm_triggered") is True or str(h.get("llm_triggered")).lower() == "true":
    triggered += 1
  print(
    f"{qid}: cbo={ct} hybrid={ht} delta={d} llm_lat={lat} "
    f"calls={h.get('llm_calls')} trig={h.get('llm_triggered')} "
    f"fb={h.get('fallback_reason')} proposal={h.get('proposal_plan_id')} "
    f"selected={h.get('selected_plan_id') or h.get('plan_id')}"
  )

print(
  "local delta median=", median(deltas) if deltas else None,
  "mean=", round(sum(deltas) / len(deltas), 1) if deltas else None,
)
print(
  "local llm_lat median=", median(lats) if lats else None,
  "mean=", round(sum(lats) / len(lats), 1) if lats else None,
)
print("\n=== vs BASELINE ===")
print("query\tbase_delta\tlocal_delta\tbase_lat\tlocal_lat")
for qid in sorted(BASELINE):
  b = BASELINE[qid]
  c, h = cbo.get(qid), hyb.get(qid)
  d = None
  if c and h and c.get("t_plan_ms") is not None and h.get("t_plan_ms") is not None:
    d = int(h["t_plan_ms"]) - int(c["t_plan_ms"])
  print(f"{qid}\t{b['delta']}\t{d}\t{b['llm_lat']}\t{h.get('llm_latency_ms') if h else None}")

print("OUT=", out)
print("llm_triggered=", triggered, "/", len(hyb), "timeouts=", timeouts)
if timeouts:
  raise SystemExit("UNEXPECTED llm_timeout")
if triggered < len(hyb):
  raise SystemExit("EXPECTED all queries to trigger LLM")
print("LOCAL_OLLAMA_OVERHEAD_OK")
PY
