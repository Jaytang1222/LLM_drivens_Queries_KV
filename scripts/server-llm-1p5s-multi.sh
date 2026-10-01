#!/usr/bin/env bash
# Multi-trial 1.5s LLM wall-clock check (curl + Java oneShot 1500ms).
set -euo pipefail
source ~/.kart_server_env
cd "$KART_DST"
set -a
# shellcheck disable=SC1091
source .env
set +a

LLM_MODEL="${LLM_MODEL:-deepseek-flash}"
URL="${LLM_BASE_URL%/}/chat/completions"
echo "model=$LLM_MODEL proxy=$HTTPS_PROXY"
ss -ltn | grep -E ':17890\s' || { echo "FAIL: 17890 not listening"; exit 1; }

BODY=$(python3 - <<PY
import json, os
print(json.dumps({
  "model": os.environ.get("LLM_MODEL", "deepseek-flash"),
  "temperature": 0,
  "messages": [
    {"role": "system", "content": "Pick one whitelist plan_id. Reply with only JSON {\"plan_id\":\"P_...\"}."},
    {"role": "user", "content": "prompt_version=cbo_llm_compact_v3\\nReply ONLY JSON: {\"plan_id\":\"P_...\"}\\ncbo_plan_id=P_TZ\\nwhitelist=P_T,P_Z\\n"},
  ],
}))
PY
)

run_curl_trials() {
  local max_t="$1" n="$2" label="$3"
  local ok=0
  echo "=== ${label}: ${n}x curl max-time ${max_t}s ==="
  for i in $(seq 1 "$n"); do
    set +e
    metrics=$(curl -sS -o "/tmp/ds_${label}_$i.json" \
      -w "%{http_code} %{time_total} %{time_starttransfer} %{size_download}" \
      --connect-timeout "$max_t" --max-time "$max_t" \
      -X POST "$URL" \
      -H "Authorization: Bearer $LLM_API_KEY" \
      -H "Content-Type: application/json" \
      -d "$BODY" 2>"/tmp/ds_${label}_$i.err")
    ec=$?
    set -e
    sz=$(wc -c <"/tmp/ds_${label}_$i.json" 2>/dev/null || echo 0)
    content=""
    if [[ "$sz" -gt 0 ]]; then
      content=$(python3 -c "import json; j=json.load(open('/tmp/ds_${label}_$i.json')); print(((j.get('choices') or [{}])[0].get('message',{}) or {}).get('content') or '')[:80]" 2>/dev/null || true)
    fi
    if [[ $ec -eq 0 && "$sz" -gt 0 && -n "$content" ]]; then
      ok=$((ok+1)); mark=OK
    else
      mark=FAIL
    fi
    echo "trial$i $mark curl_exit=$ec metrics=$metrics content=${content:-<empty>}"
  done
  echo "${label}_success=${ok}/${n}"
}

run_curl_trials 1.5 5 "t15"
run_curl_trials 2.0 5 "t20"

echo "=== Java TimedLlm1500 (connect/read timeout=1500ms, uses JVM proxy props) ==="
SRC="$KART_DST/scripts/TimedLlm1500.java"
javac "$SRC" -d /tmp
echo "JAVA_TOOL_OPTIONS=$JAVA_TOOL_OPTIONS"
java -cp /tmp TimedLlm1500

echo DONE_1P5S_MULTI
