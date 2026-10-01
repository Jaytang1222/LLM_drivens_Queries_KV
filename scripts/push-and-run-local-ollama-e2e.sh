#!/usr/bin/env bash
set -euo pipefail
KEY="${HOME}/.ssh/id_ed25519_kart_server"
HOST=tyq@10.242.104.108
ROOT=/mnt/f/Projects/LLM_KV
python3 - <<'PY'
from pathlib import Path
p = Path("/mnt/f/Projects/LLM_KV/scripts/run-server-llm-overhead-e2e-local-ollama.sh")
p.write_bytes(p.read_bytes().replace(b"\r\n", b"\n").replace(b"\r", b"\n"))
PY
scp -i "$KEY" -o IdentitiesOnly=yes -o BatchMode=yes \
  "$ROOT/scripts/run-server-llm-overhead-e2e-local-ollama.sh" \
  "$HOST:/home/tyq/run-server-llm-overhead-e2e-local-ollama.sh"
ssh -i "$KEY" -o IdentitiesOnly=yes -o BatchMode=yes "$HOST" 'bash -s' <<'REMOTE'
set -euo pipefail
source ~/.kart_server_env
cd "$KART_DST"
case "$KART_DST" in
  /home/tyq/*) ;;
  *) echo "FAIL: KART_DST=$KART_DST"; exit 1 ;;
esac
python3 - <<'PY'
from pathlib import Path
src = Path("/home/tyq/run-server-llm-overhead-e2e-local-ollama.sh")
dst = Path("scripts/run-server-llm-overhead-e2e-local-ollama.sh")
dst.write_bytes(src.read_bytes().replace(b"\r\n", b"\n").replace(b"\r", b"\n"))
print("wrote", dst)
PY
chmod +x scripts/run-server-llm-overhead-e2e-local-ollama.sh
bash scripts/run-server-llm-overhead-e2e-local-ollama.sh
REMOTE
