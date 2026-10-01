#!/usr/bin/env bash
# Hard 1.5s LLM invoke check on server (curl + Java probe with budget).
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

echo "=== curl chat hard 1.5s ==="
set +e
curl -sS -o /tmp/ds_chat15.json -w "http=%{http_code} time=%{time_total} ttfb=%{time_starttransfer} size=%{size_download}\n" \
  --connect-timeout 1.5 --max-time 1.5 \
  -X POST "$URL" \
  -H "Authorization: Bearer $LLM_API_KEY" \
  -H "Content-Type: application/json" \
  -d "$BODY"
curl_exit=$?
set -e
echo "curl_exit=$curl_exit"
python3 - <<'PY'
import json, sys
from pathlib import Path
p = Path('/tmp/ds_chat15.json')
ok = False
if p.exists() and p.stat().st_size:
  j = json.loads(p.read_text())
  c = (j.get('choices') or [{}])[0].get('message', {}).get('content')
  print('content', (c or '')[:200])
  print('model', j.get('model'))
  ok = bool(c)
print('CURL_1P5S', 'OK' if ok else 'FAIL')
sys.exit(0 if ok else 1)
PY
curl_ok=$?

echo "=== kart probe (Java HttpURLConnection via proxy) ==="
set +e
./scripts/kart.sh probe 2>&1 | tee /tmp/kart-probe-1p5.out | tail -20
grep -E 'PROBE_OK|PROBE_FAILED' /tmp/kart-probe-1p5.out
probe_ok=${PIPESTATUS[0]}
set -e

if [[ $curl_ok -eq 0 ]]; then
  echo "RESULT: LLM callable within 1.5s (curl)"
  exit 0
fi
echo "RESULT: curl missed 1.5s hard cutoff (exit=$curl_exit); see probe above"
exit 1
