#!/usr/bin/env bash
# Verify server can reach DeepSeek via Clash HTTP reverse tunnel within 1.5s.
set -euo pipefail
source ~/.kart_server_env
cd "$KART_DST"
echo "PROXY=$HTTPS_PROXY"
echo "JAVA_TOOL_OPTIONS=$JAVA_TOOL_OPTIONS"
ss -ltn | grep -E ':(17890|7890|1080)\s' || true

set -a
# shellcheck disable=SC1091
source .env
set +a
LLM_MODEL="${LLM_MODEL:-deepseek-flash}"
URL="${LLM_BASE_URL%/}/chat/completions"

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

echo "=== models via HTTP proxy ==="
curl -sS -o /tmp/ds_models.json -w "models http=%{http_code} time=%{time_total}\n" \
  --connect-timeout 5 --max-time 8 \
  -H "Authorization: Bearer $LLM_API_KEY" \
  "$LLM_BASE_URL/models"

echo "=== chat 1.5s via HTTP proxy ==="
set +e
curl -sS -o /tmp/ds_chat15.json -w "chat15 http=%{http_code} time=%{time_total} ttfb=%{time_starttransfer}\n" \
  --connect-timeout 1.5 --max-time 1.5 \
  -X POST "$URL" \
  -H "Authorization: Bearer $LLM_API_KEY" \
  -H "Content-Type: application/json" \
  -d "$BODY"
echo "chat15_exit=$?"
set -e
python3 - <<'PY'
import json
from pathlib import Path
p=Path('/tmp/ds_chat15.json')
if not p.exists() or p.stat().st_size==0:
  print('no_body'); raise SystemExit
j=json.loads(p.read_text())
c=(j.get('choices') or [{}])[0].get('message',{}).get('content')
print('content', (c or '')[:200])
print('model', j.get('model'))
PY

echo "=== kart probe (uses JAVA proxy props) ==="
./scripts/kart.sh probe 2>&1 | tee /tmp/kart-probe-http-proxy.out | tail -25
grep -E 'PROBE_OK|PROBE_FAILED' /tmp/kart-probe-http-proxy.out || true
echo EGRESS_HTTP_DONE
