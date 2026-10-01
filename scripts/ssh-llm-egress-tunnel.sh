#!/usr/bin/env bash
# Keep an SSH reverse SOCKS tunnel up so startserver02 can egress via this machine.
# Usage (WSL or Linux): bash scripts/ssh-llm-egress-tunnel.sh
set -euo pipefail

KEY="${KART_SSH_KEY:-$HOME/.ssh/id_ed25519_kart_server}"
if [[ ! -f "$KEY" && -f /mnt/c/Users/jayta/.ssh/id_ed25519_kart_server ]]; then
  mkdir -p "$HOME/.ssh"
  cp -f /mnt/c/Users/jayta/.ssh/id_ed25519_kart_server "$KEY"
  chmod 600 "$KEY"
fi
REMOTE="${KART_SSH_REMOTE:-tyq@10.242.104.108}"
PORT="${KART_SOCKS_PORT:-1080}"

echo "Starting reverse SOCKS on $REMOTE:127.0.0.1:$PORT via $KEY"
exec ssh -i "$KEY" \
  -o IdentitiesOnly=yes \
  -o BatchMode=yes \
  -o StrictHostKeyChecking=accept-new \
  -o ServerAliveInterval=30 \
  -o ServerAliveCountMax=3 \
  -o ExitOnForwardFailure=yes \
  -N -T \
  -R "127.0.0.1:${PORT}" \
  "$REMOTE"
