#!/usr/bin/env bash
# Push max_tokens + speculative P_T (LLM-on), patch jar under /home/tyq, run T-Drive E2E.
set -euo pipefail
KEY="${HOME}/.ssh/id_ed25519_kart_server"
HOST=tyq@10.242.104.108
# Direct :22 may be blocked; Prefer ProxyJump (OpenSSH Host kart-lab / dorm-jump).
SSH_OPTS=(-i "$KEY" -o IdentitiesOnly=yes -o BatchMode=yes -o ProxyJump=dorm-jump)
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# Prefer Windows mount when invoked from WSL against this checkout.
if [[ -d /mnt/f/Projects/LLM_KV ]]; then
  ROOT=/mnt/f/Projects/LLM_KV
fi

python3 - <<PY
from pathlib import Path
root = Path(r"$ROOT")
for rel in (
  "src/main/java/kart/bench/CboLlmProposalArm.java",
  "src/main/java/kart/llm/LlmOptions.java",
  "src/main/java/kart/llm/OpenAiCompatibleClient.java",
  "scripts/server-patch-cbo-llm-arm.sh",
  "scripts/run-server-llm-overhead-e2e-local-ollama.sh",
  "scripts/run-server-ais-llm-overhead-e2e.sh",
):
  p = root / rel
  if p.exists():
    p.write_bytes(p.read_bytes().replace(b"\r\n", b"\n").replace(b"\r", b"\n"))
PY

scp "${SSH_OPTS[@]}" \
  "$ROOT/src/main/java/kart/bench/CboLlmProposalArm.java" \
  "$ROOT/src/main/java/kart/llm/LlmOptions.java" \
  "$ROOT/src/main/java/kart/llm/OpenAiCompatibleClient.java" \
  "$ROOT/scripts/server-patch-cbo-llm-arm.sh" \
  "$ROOT/scripts/run-server-llm-overhead-e2e-local-ollama.sh" \
  "$HOST:/home/tyq/"

ssh "${SSH_OPTS[@]}" "$HOST" 'bash -s' <<'REMOTE'
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
  ("/home/tyq/CboLlmProposalArm.java", "src/main/java/kart/bench/CboLlmProposalArm.java"),
  ("/home/tyq/LlmOptions.java", "src/main/java/kart/llm/LlmOptions.java"),
  ("/home/tyq/OpenAiCompatibleClient.java", "src/main/java/kart/llm/OpenAiCompatibleClient.java"),
  ("/home/tyq/server-patch-cbo-llm-arm.sh", "scripts/server-patch-cbo-llm-arm.sh"),
  ("/home/tyq/run-server-llm-overhead-e2e-local-ollama.sh", "scripts/run-server-llm-overhead-e2e-local-ollama.sh"),
]
for src, dst in pairs:
  raw = Path(src).read_bytes().replace(b"\r\n", b"\n").replace(b"\r", b"\n")
  Path(dst).write_bytes(raw)
  print("wrote", dst, "bytes", len(raw))
PY
chmod +x scripts/server-patch-cbo-llm-arm.sh \
  scripts/run-server-llm-overhead-e2e-local-ollama.sh

bash scripts/server-patch-cbo-llm-arm.sh

# LLM-on knobs (defaults already on; export explicitly for the run log)
export KART_CBO_LLM_MAX_TOKENS="${KART_CBO_LLM_MAX_TOKENS:-32}"
export KART_CBO_LLM_SPECULATE="${KART_CBO_LLM_SPECULATE:-1}"
export KART_CBO_LLM_SPECULATE_K="${KART_CBO_LLM_SPECULATE_K:-1}"
echo "LLM-on: MAX_TOKENS=$KART_CBO_LLM_MAX_TOKENS SPECULATE=$KART_CBO_LLM_SPECULATE K=$KART_CBO_LLM_SPECULATE_K"

echo "======== T-Drive E2E (Ollama + speculative P_T) ========"
bash scripts/run-server-llm-overhead-e2e-local-ollama.sh
REMOTE
