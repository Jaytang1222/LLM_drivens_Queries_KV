#!/usr/bin/env bash
set -euo pipefail
ENVF=/mnt/f/Projects/LLM_KV/.env
[[ -f "$ENVF" ]] || ENVF=/home/jaytang/projects/llm-kv/.env
set -a
# shellcheck disable=SC1090
# Strip CR from Windows .env lines
source <(sed 's/\r$//' "$ENVF")
set +a
export LLM_MODEL="${LLM_MODEL:-deepseek-flash}"
export LLM_BASE_URL="${LLM_BASE_URL:-https://api.deepseek.com/v1}"
# sanitize again
LLM_MODEL="${LLM_MODEL//$'\r'/}"
LLM_BASE_URL="${LLM_BASE_URL//$'\r'/}"
LLM_API_KEY="${LLM_API_KEY//$'\r'/}"
export LLM_MODEL LLM_BASE_URL LLM_API_KEY
echo "MODEL=$LLM_MODEL"
echo "BASE=$LLM_BASE_URL"
echo "KEY_LEN=${#LLM_API_KEY}"

BODY=$(LLM_MODEL="$LLM_MODEL" python3 - <<'PY'
import json, os
print(json.dumps({
  "model": os.environ["LLM_MODEL"],
  "temperature": 0,
  "messages": [
    {"role": "system", "content": "Pick one whitelist plan_id. Reply with only JSON {\"plan_id\":\"P_...\"}."},
    {"role": "user", "content": "prompt_version=cbo_llm_compact_v3\nReply ONLY JSON: {\"plan_id\":\"P_...\"}\ncbo_plan_id=P_TZ\nwhitelist=P_T,P_Z\n"},
  ],
}))
PY
)

URL="${LLM_BASE_URL%/}/chat/completions"
echo "URL=$URL"

echo "=== try 1.5s wall ==="
set +e
curl -sS -o /tmp/ds1500.json -w "http=%{http_code} time_total=%{time_total} ttfb=%{time_starttransfer}\n" \
  --connect-timeout 1.5 --max-time 1.5 \
  -X POST "$URL" \
  -H "Authorization: Bearer $LLM_API_KEY" \
  -H "Content-Type: application/json" \
  -d "$BODY"
echo "curl15_exit=$?"
set -e
python3 - <<'PY'
from pathlib import Path
p=Path("/tmp/ds1500.json")
raw=p.read_bytes() if p.exists() else b""
print("bytes", len(raw))
if raw:
  try:
    import json
    j=json.loads(raw)
    c=(j.get("choices") or [{}])[0].get("message",{}).get("content")
    print("ok_content", (c or "")[:200])
    print("resp_model", j.get("model"))
  except Exception as e:
    print("raw_prefix", raw[:300], "err", e)
PY

echo "=== try 5s wall ==="
set +e
curl -sS -o /tmp/ds5.json -w "http=%{http_code} time_total=%{time_total} ttfb=%{time_starttransfer}\n" \
  --connect-timeout 5 --max-time 5 \
  -X POST "$URL" \
  -H "Authorization: Bearer $LLM_API_KEY" \
  -H "Content-Type: application/json" \
  -d "$BODY"
echo "curl5_exit=$?"
set -e
if [[ -f /tmp/ds5.json ]]; then
  python3 - <<'PY'
import json
from pathlib import Path
j=json.loads(Path("/tmp/ds5.json").read_text(encoding="utf-8"))
c=(j.get("choices") or [{}])[0].get("message",{}).get("content")
print("ok_content", (c or "")[:200])
print("resp_model", j.get("model"))
print("usage", j.get("usage"))
PY
else
  echo "no_ds5_body"
fi
echo LOCAL_PROBE_DONE
