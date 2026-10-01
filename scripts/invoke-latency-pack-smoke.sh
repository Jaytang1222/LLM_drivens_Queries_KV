#!/usr/bin/env bash
set -euo pipefail
KEY="${HOME}/.ssh/id_ed25519_kart_server"
cp -f /mnt/c/Users/jayta/.ssh/id_ed25519_kart_server "$KEY"
chmod 600 "$KEY"
sed -i 's/\r$//' /mnt/f/Projects/LLM_KV/scripts/server-latency-pack-smoke-remote.sh
scp -i "$KEY" -o IdentitiesOnly=yes \
  /mnt/f/Projects/LLM_KV/scripts/server-latency-pack-smoke-remote.sh \
  tyq@10.242.104.108:/home/tyq/latency-pack-smoke.sh
ssh -i "$KEY" -o IdentitiesOnly=yes -o ServerAliveInterval=30 \
  tyq@10.242.104.108 'sed -i "s/\r$//" /home/tyq/latency-pack-smoke.sh && bash /home/tyq/latency-pack-smoke.sh'
