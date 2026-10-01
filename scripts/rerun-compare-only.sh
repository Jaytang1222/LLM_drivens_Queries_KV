#!/usr/bin/env bash
set -euo pipefail
KEY="${HOME}/.ssh/id_ed25519_kart_server"
HOST=tyq@10.242.104.108
# re-push fixed compare script body only as remote python file
scp -i "$KEY" -o IdentitiesOnly=yes -o BatchMode=yes \
  /mnt/f/Projects/LLM_KV/scripts/_compare_overhead_cache.py \
  "$HOST:/tmp/_compare_overhead_cache.py"
ssh -i "$KEY" -o IdentitiesOnly=yes -o BatchMode=yes "$HOST" 'bash -s' <<'REMOTE'
set -euo pipefail
source ~/.kart_server_env
cd "$KART_DST"
cp /tmp/_compare_overhead_cache.py scripts/_compare_overhead_cache.py
sed -i 's/\r$//' scripts/_compare_overhead_cache.py
export OUT=experiments/results/cbo-llm-overhead-cache-v1-20260929-153931
python3 scripts/_compare_overhead_cache.py | tee /tmp/overhead_cache_compare.txt
REMOTE
