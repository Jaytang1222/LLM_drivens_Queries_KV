#!/usr/bin/env bash
set -euo pipefail
KEY="${HOME}/.ssh/id_ed25519_kart_server"
HOST=tyq@10.242.104.108
ROOT=/mnt/f/Projects/LLM_KV
python3 - <<'PY'
from pathlib import Path
for rel in (
  "scripts/run-server-ais-llm-overhead-e2e-local-ollama.sh",
  "experiments/suites/e2e-ais-llm-overhead-v1.yaml",
  "experiments/workloads/bound_ir_ais_opportunity_v1.json",
  "experiments/workloads/bound_ir_ais_opportunity_v1.oracle.json",
):
  p = Path("/mnt/f/Projects/LLM_KV") / rel
  if p.exists() and p.suffix in (".sh", ".yaml", ".yml", ".json"):
    p.write_bytes(p.read_bytes().replace(b"\r\n", b"\n").replace(b"\r", b"\n"))
PY
scp -i "$KEY" -o IdentitiesOnly=yes -o BatchMode=yes \
  "$ROOT/scripts/run-server-ais-llm-overhead-e2e-local-ollama.sh" \
  "$ROOT/experiments/suites/e2e-ais-llm-overhead-v1.yaml" \
  "$ROOT/experiments/workloads/bound_ir_ais_opportunity_v1.json" \
  "$ROOT/experiments/workloads/bound_ir_ais_opportunity_v1.oracle.json" \
  "$HOST:/home/tyq/"
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
pairs = [
  ("/home/tyq/run-server-ais-llm-overhead-e2e-local-ollama.sh",
   "scripts/run-server-ais-llm-overhead-e2e-local-ollama.sh"),
  ("/home/tyq/e2e-ais-llm-overhead-v1.yaml",
   "experiments/suites/e2e-ais-llm-overhead-v1.yaml"),
  ("/home/tyq/bound_ir_ais_opportunity_v1.json",
   "experiments/workloads/bound_ir_ais_opportunity_v1.json"),
  ("/home/tyq/bound_ir_ais_opportunity_v1.oracle.json",
   "experiments/workloads/bound_ir_ais_opportunity_v1.oracle.json"),
]
for src, dst in pairs:
  raw = Path(src).read_bytes().replace(b"\r\n", b"\n").replace(b"\r", b"\n")
  Path(dst).parent.mkdir(parents=True, exist_ok=True)
  Path(dst).write_bytes(raw)
  print("wrote", dst)
PY
chmod +x scripts/run-server-ais-llm-overhead-e2e-local-ollama.sh
# Ensure ollama up
ss -ltn | grep -E ':11434\s' || {
  echo "starting ollama serve..."
  nohup env PATH="$HOME/.local/bin:$PATH" OLLAMA_HOST=127.0.0.1:11434 \
    OLLAMA_MODELS="$HOME/.ollama" "$HOME/.local/bin/ollama" serve \
    >"$HOME/.ollama/serve.log" 2>&1 &
  for _ in $(seq 1 30); do ss -ltn | grep -qE ':11434\s' && break; sleep 1; done
}
ss -ltn | grep -E ':11434\s' || { echo "FAIL: no 11434"; exit 1; }
# Confirm compact_v5 in ja
strings target/kart.jar | grep -F 'cbo_llm_compact_v5' | head -1 || {
  echo "WARN: compact_v5 string not found via strings on jar; proceeding"
}
bash scripts/run-server-ais-llm-overhead-e2e-local-ollama.sh
REMOTE
