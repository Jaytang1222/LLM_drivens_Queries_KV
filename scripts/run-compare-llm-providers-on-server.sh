#!/usr/bin/env bash
set -euo pipefail
KEY="${HOME}/.ssh/id_ed25519_kart_server"
mkdir -p "${HOME}/.ssh"
cp -f /mnt/c/Users/jayta/.ssh/id_ed25519_kart_server "$KEY"
chmod 600 "$KEY"
HOST=tyq@10.242.104.108
ROOT=/mnt/f/Projects/LLM_KV

scp -i "$KEY" -o IdentitiesOnly=yes -o BatchMode=yes \
  "$ROOT/.env" \
  "$ROOT/scripts/compare-llm-providers-probe.sh" \
  "$ROOT/scripts/kart-env.sh" \
  "$HOST:/tmp/"

ssh -i "$KEY" -o IdentitiesOnly=yes -o BatchMode=yes "$HOST" 'bash -s' <<'REMOTE'
set -euo pipefail
source ~/.kart_server_env
cp /tmp/.env "$KART_DST/.env"
cp /tmp/compare-llm-providers-probe.sh "$KART_DST/scripts/"
cp /tmp/kart-env.sh "$KART_DST/scripts/"
sed -i 's/\r$//' "$KART_DST/.env" "$KART_DST/scripts/compare-llm-providers-probe.sh" "$KART_DST/scripts/kart-env.sh"
chmod +x "$KART_DST/scripts/compare-llm-providers-probe.sh"
echo "proxy_check:"
curl -sS -o /dev/null -w "http=%{http_code} time=%{time_total}\n" \
  --connect-timeout 5 --max-time 15 --proxy http://127.0.0.1:17890 \
  https://api.deepseek.com/ || true
PROXY=http://127.0.0.1:17890 REPEATS=3 MAX_TIME=45 \
  bash "$KART_DST/scripts/compare-llm-providers-probe.sh"
REMOTE
