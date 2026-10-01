#!/usr/bin/env bash
set -euo pipefail
KEY="${HOME}/.ssh/id_ed25519_kart_server"
HOST=tyq@10.242.104.108
ROOT=/mnt/f/Projects/LLM_KV
scp -i "$KEY" -o IdentitiesOnly=yes -o BatchMode=yes "$ROOT/.env" "$HOST:/tmp/.env"
ssh -i "$KEY" -o IdentitiesOnly=yes -o BatchMode=yes "$HOST" 'bash -s' <<'REMOTE'
set -euo pipefail
source ~/.kart_server_env
cp /tmp/.env "$KART_DST/.env"
sed -i 's/\r$//' "$KART_DST/.env"
set -a; source "$KART_DST/.env"; set +a
BASE="${GOOGLE_LLM_BASE_URL%/}"
KEY="$GOOGLE_API_KEY"
for m in gemini-3.8-flash gemini-3.5-flash-lite gemini-3.5-flash gemini-flash-latest; do
  for attempt in 1 2 3; do
    body=$(MODEL="$m" python3 - <<'PY'
import json, os
print(json.dumps({
  "model": os.environ["MODEL"],
  "temperature": 0,
  "messages": [
    {"role": "system", "content": "Pick one whitelist plan_id. Reply with only JSON {\"plan_id\":\"P_...\"}."},
    {"role": "user", "content": "prompt_version=cbo_llm_compact_v3\nReply ONLY JSON: {\"plan_id\":\"P_...\"}\ncbo_plan_id=P_TZ\nwhitelist=P_T,P_Z\n"},
  ],
}))
PY
)
    resp=$(mktemp)
    set +e
    meta=$(curl -sS -o "$resp" -w "%{http_code} %{time_total}" --proxy http://127.0.0.1:17890 \
      --connect-timeout 10 --max-time 45 \
      -X POST "$BASE/chat/completions" \
      -H "Authorization: Bearer $KEY" -H "Content-Type: application/json" -d "$body")
    ec=$?
    set -e
    if [[ $ec -ne 0 ]]; then
      echo "model=$m attempt=$attempt curl_exit=$ec"
      rm -f "$resp"
      sleep 2
      continue
    fi
    http=$(echo "$meta" | awk '{print $1}')
    t=$(echo "$meta" | awk '{print $2}')
    content=$(python3 - <<PY
import json
from pathlib import Path
raw=Path("$resp").read_text(encoding="utf-8", errors="replace")
try:
  j=json.loads(raw)
except Exception:
  print(raw[:200].replace("\n"," ")); raise SystemExit
if isinstance(j, list):
  j=j[0] if j else {}
err=j.get("error")
if err:
  print("ERR:"+str(err)[:160])
else:
  c=(j.get("choices") or [{}])[0].get("message",{}).get("content") or ""
  print(c.replace("\n"," ")[:160])
PY
)
    echo "model=$m attempt=$attempt http=$http time=${t}s content=$content"
    rm -f "$resp"
    if [[ "$http" == "200" ]]; then
      break
    fi
    sleep 3
  done
done
REMOTE
