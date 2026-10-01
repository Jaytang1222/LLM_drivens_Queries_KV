#!/usr/bin/env bash
set -euo pipefail
source ~/.kart_server_env
cd "$KART_DST"
set -a
# shellcheck disable=SC1091
source .env
set +a
URL="${LLM_BASE_URL%/}/chat/completions"
echo "PROXY=$HTTPS_PROXY"
ss -ltn | grep -E ':17890\s' || { echo "missing 17890"; exit 1; }
BODY='{"model":"deepseek-flash","temperature":0,"messages":[{"role":"user","content":"Reply ONLY JSON: {\"ok\":true}"}]}'
curl -sS -o /tmp/ds_chat8.json -w "chat8 http=%{http_code} time=%{time_total} ttfb=%{time_starttransfer} size=%{size_download}\n" \
  --connect-timeout 5 --max-time 8 \
  -X POST "$URL" \
  -H "Authorization: Bearer $LLM_API_KEY" \
  -H "Content-Type: application/json" \
  -d "$BODY"
echo "exit=$?"
python3 - <<'PY'
import json
from pathlib import Path
p=Path('/tmp/ds_chat8.json')
print('bytes', p.stat().st_size if p.exists() else 0)
if p.exists() and p.stat().st_size:
  j=json.loads(p.read_text())
  c=(j.get('choices') or [{}])[0].get('message',{}).get('content')
  print('content', (c or '')[:160])
  print('model', j.get('model'))
PY
