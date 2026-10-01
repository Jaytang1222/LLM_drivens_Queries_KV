#!/usr/bin/env bash
set -euo pipefail
KEY="${HOME}/.ssh/id_ed25519_kart_server"
HOST=tyq@10.242.104.108
ssh -i "$KEY" -o IdentitiesOnly=yes -o BatchMode=yes "$HOST" 'bash -s' <<'REMOTE'
set -euo pipefail
source ~/.kart_server_env
set -a
# shellcheck disable=SC1090
source "$KART_DST/.env"
set +a
BASE="${GOOGLE_LLM_BASE_URL%/}"
KEY="$GOOGLE_API_KEY"
for m in gemini-2.0-flash gemini-2.0-flash-001 gemini-1.5-flash gemini-1.5-flash-latest gemini-2.5-flash gemini-2.5-flash-lite gemini-flash-latest gemini-2.0-flash-lite gemini-3.8-flash; do
  body=$(MODEL="$m" python3 - <<'PY'
import json, os
print(json.dumps({
  "model": os.environ["MODEL"],
  "temperature": 0,
  "messages": [{"role": "user", "content": "Reply ONLY JSON {\"plan_id\":\"P_T\"}"}],
}))
PY
)
  resp=$(mktemp)
  set +e
  meta=$(curl -sS -o "$resp" -w "%{http_code} %{time_total}" --proxy http://127.0.0.1:17890 \
    --connect-timeout 10 --max-time 30 \
    -X POST "$BASE/chat/completions" \
    -H "Authorization: Bearer $KEY" -H "Content-Type: application/json" -d "$body")
  ec=$?
  set -e
  if [[ $ec -ne 0 ]]; then
    echo "model=$m curl_exit=$ec"
    rm -f "$resp"
    continue
  fi
  http=$(echo "$meta" | awk '{print $1}')
  t=$(echo "$meta" | awk '{print $2}')
  snippet=$(python3 -c "from pathlib import Path; print(Path('$resp').read_text(encoding='utf-8',errors='replace')[:240].replace(chr(10),' '))")
  echo "model=$m http=$http time=$t body=$snippet"
  rm -f "$resp"
done
REMOTE
