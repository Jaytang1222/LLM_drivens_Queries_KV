#!/usr/bin/env bash
set -euo pipefail
KEY="${HOME}/.ssh/id_ed25519_kart_server"
HOST=tyq@10.242.104.108
ROOT=/mnt/f/Projects/LLM_KV
sed -i 's/\r$//' "$ROOT/scripts/_remote_install_ollama.sh"
scp -i "$KEY" -o IdentitiesOnly=yes -o BatchMode=yes \
  "$ROOT/scripts/_remote_install_ollama.sh" \
  "$HOST:/home/tyq/_remote_install_ollama.sh"
ssh -i "$KEY" -o IdentitiesOnly=yes -o BatchMode=yes "$HOST" \
  'ls -lah /home/tyq/tmp_ollama_5m9VOn; wc -c /home/tyq/tmp_ollama_5m9VOn/ollama.tar.zst || true; ss -ltn | grep 17890 || echo no17890; sed -i "s/\r$//" /home/tyq/_remote_install_ollama.sh; bash /home/tyq/_remote_install_ollama.sh'
