#!/usr/bin/env bash
set -euo pipefail
KEY="${HOME}/.ssh/id_ed25519_kart_server"
mkdir -p "${HOME}/.ssh"
cp -f /mnt/c/Users/jayta/.ssh/id_ed25519_kart_server "$KEY"
chmod 600 "$KEY"
SRC=/mnt/f/Projects/LLM_KV/scripts/kart_server_env.template
sed -i 's/\r$//' "$SRC"
echo "local lines=$(wc -l < "$SRC")"
scp -i "$KEY" -o IdentitiesOnly=yes -o BatchMode=yes \
  "$SRC" tyq@10.242.104.108:/home/tyq/.kart_server_env
ssh -i "$KEY" -o IdentitiesOnly=yes -o BatchMode=yes tyq@10.242.104.108 \
  'set -e; echo ---; cat ~/.kart_server_env; echo ---; source ~/.kart_server_env; echo LLM_MODEL=${LLM_MODEL:-unset}; cd "$KART_DST"; java -Dkart.root="$KART_DST" -jar target/kart.jar doctor 2>/dev/null | egrep "doctor:|tables=|LLM_"'
