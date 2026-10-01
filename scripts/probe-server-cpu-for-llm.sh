#!/usr/bin/env bash
set -euo pipefail
KEY="${HOME}/.ssh/id_ed25519_kart_server"
ssh -i "$KEY" -o IdentitiesOnly=yes -o BatchMode=yes tyq@10.242.104.108 'bash -s' <<'REMOTE'
set -euo pipefail
echo "=== host ==="
hostname; uname -a
echo "=== cpu ==="
nproc
lscpu | egrep 'Model name|Socket|Core|Thread|CPU\(s\)|Flags' | head -20
echo "=== mem ==="
free -h
echo "=== disk home ==="
df -h ~ | tail -1
echo "=== gpu? ==="
(command -v nvidia-smi >/dev/null && nvidia-smi -L) || echo "no nvidia-smi"
echo "=== existing ollama/llama? ==="
command -v ollama || true
command -v llama-server || true
ss -ltn | egrep ':(11434|8080|8000|1234)\s' || true
REMOTE
