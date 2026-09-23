#!/usr/bin/env bash
# OI-1: probe OpenAI-compatible chat/completions + optional json_object.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck disable=SC1091
source "$ROOT/scripts/load-llm-env.sh"

BASE="${LLM_BASE_URL%/}"
MODEL="$LLM_MODEL"
KEY="$LLM_API_KEY"

probe_one() {
  local url="$1"
  local with_json="$2"
  local body
  if [[ "$with_json" == "1" ]]; then
    body=$(cat <<EOF
{"model":"$MODEL","temperature":0,"messages":[{"role":"user","content":"Return a JSON object with keys ok (boolean true) and model_echo (string repeating the model id you are)."}],"response_format":{"type":"json_object"}}
EOF
)
  else
    body=$(cat <<EOF
{"model":"$MODEL","temperature":0,"messages":[{"role":"user","content":"Reply with exactly: {\"ok\":true}"}]}
EOF
)
  fi
  local tmp code
  tmp=$(mktemp)
  code=$(curl -sS -o "$tmp" -w "%{http_code}" \
    -X POST "$url" \
    -H "Authorization: Bearer $KEY" \
    -H "Content-Type: application/json" \
    --connect-timeout 20 \
    --max-time 90 \
    -d "$body" || echo "000")
  echo "PROBE url=$url json_mode=$with_json http=$code"
  # Print response truncated; strip any accidental key echo
  python3 - "$tmp" <<'PY'
import json,sys
p=sys.argv[1]
raw=open(p,encoding='utf-8',errors='replace').read()
print('body_prefix=', raw[:800].replace('\n',' '))
try:
  j=json.loads(raw)
  c=j.get('choices') or []
  if c:
    msg=(c[0].get('message') or {}).get('content')
    print('assistant_content=', (msg or '')[:500])
  if 'error' in j:
    print('error=', j['error'])
except Exception as e:
  print('parse_err=', e)
PY
  rm -f "$tmp"
  [[ "$code" == "200" ]]
}

echo "=== OI-1 probe model=$MODEL ==="
CANDIDATES=(
  "$BASE/v1/chat/completions"
  "$BASE/chat/completions"
)
# If user already included /v1 in BASE, also try as-is
if [[ "$BASE" == */v1 ]]; then
  CANDIDATES=("$BASE/chat/completions" "${BASE%/v1}/chat/completions")
fi

ok_url=""
json_ok="unknown"
plain_ok="unknown"

for url in "${CANDIDATES[@]}"; do
  echo "--- try $url with json_object ---"
  if probe_one "$url" 1; then
    ok_url="$url"
    json_ok="yes"
    break
  fi
  echo "json_object failed; try plain ---"
  if probe_one "$url" 0; then
    ok_url="$url"
    json_ok="no"
    plain_ok="yes"
    break
  fi
done

if [[ -z "$ok_url" ]]; then
  echo "PROBE_FAILED"
  exit 1
fi

# Derive base for Java client (strip /chat/completions)
JAVA_BASE="${ok_url%/chat/completions}"
echo "PROBE_OK endpoint=$ok_url java_base=$JAVA_BASE json_object=$json_ok plain=$plain_ok"
# Write non-secret hint for follow-up scripts
mkdir -p "$ROOT/runs/llm-probe"
cat > "$ROOT/runs/llm-probe/last.json" <<EOF
{"endpoint":"$ok_url","java_base":"$JAVA_BASE","model":"$MODEL","json_object":"$json_ok","provider":"PinAI"}
EOF
echo "wrote runs/llm-probe/last.json"
