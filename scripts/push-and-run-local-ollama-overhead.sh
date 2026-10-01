#!/usr/bin/env bash
# Push local-ollama overhead runner to server and execute under /home/tyq only.
set -euo pipefail
KEY="${HOME}/.ssh/id_ed25519_kart_server"
HOST=tyq@10.242.104.108
ROOT=/mnt/f/Projects/LLM_KV

python3 - <<'PY'
from pathlib import Path
for rel in (
  "scripts/run-server-llm-overhead-local-ollama.sh",
):
  p = Path("/mnt/f/Projects/LLM_KV") / rel
  raw = p.read_bytes().replace(b"\r\n", b"\n").replace(b"\r", b"\n")
  p.write_bytes(raw)
PY

scp -i "$KEY" -o IdentitiesOnly=yes -o BatchMode=yes \
  "$ROOT/scripts/run-server-llm-overhead-local-ollama.sh" \
  "$HOST:/home/tyq/run-server-llm-overhead-local-ollama.sh"

ssh -i "$KEY" -o IdentitiesOnly=yes -o BatchMode=yes "$HOST" 'bash -s' <<'REMOTE'
set -euo pipefail
source ~/.kart_server_env
cd "$KART_DST"
case "$KART_DST" in
  /home/tyq/*) ;;
  *) echo "FAIL: KART_DST=$KART_DST not under /home/tyq"; exit 1 ;;
esac
python3 - <<'PY'
from pathlib import Path
src = Path("/home/tyq/run-server-llm-overhead-local-ollama.sh")
dst = Path("scripts/run-server-llm-overhead-local-ollama.sh")
raw = src.read_bytes().replace(b"\r\n", b"\n").replace(b"\r", b"\n")
dst.write_bytes(raw)
print("wrote", dst, "bytes", len(raw))
PY
chmod +x scripts/run-server-llm-overhead-local-ollama.sh
# Confirm ollama still only user-local
ss -ltn | grep -E ':11434\s' || { echo "FAIL: 11434"; exit 1; }
test -x "$HOME/.local/bin/ollama"
test ! -e /usr/local/bin/ollama
echo "KART_DST=$KART_DST HOME=$HOME"
bash scripts/run-server-llm-overhead-local-ollama.sh
REMOTE
