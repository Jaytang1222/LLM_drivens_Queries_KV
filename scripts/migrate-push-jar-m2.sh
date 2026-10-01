#!/usr/bin/env bash
set -euo pipefail
KEY="${HOME}/.ssh/id_ed25519_kart_server"
mkdir -p "${HOME}/.ssh"
cp -f /mnt/c/Users/jayta/.ssh/id_ed25519_kart_server "$KEY"
chmod 600 "$KEY"
SSH=(ssh -i "$KEY" -o IdentitiesOnly=yes -o BatchMode=yes -o StrictHostKeyChecking=accept-new)
RSYNC_SSH="ssh -i $KEY -o IdentitiesOnly=yes -o BatchMode=yes -o StrictHostKeyChecking=accept-new"
REMOTE=tyq@10.242.104.108

JAR_SRC=/home/jaytang/projects/llm-kv/target/kart.jar
M2_SRC=/home/jaytang/.m2/repository/

if [[ ! -f "$JAR_SRC" ]]; then
  echo "ERROR: missing $JAR_SRC" >&2
  exit 1
fi

echo "=== remote mkdir ==="
"${SSH[@]}" "$REMOTE" 'mkdir -p /home/tyq/projects/llm-kv/target /home/tyq/.m2/repository'

echo "=== rsync jar ==="
rsync -a --info=progress2 -e "$RSYNC_SSH" \
  "$JAR_SRC" "$REMOTE:/home/tyq/projects/llm-kv/target/kart.jar"

echo "=== rsync m2 ==="
rsync -a --info=progress2 -e "$RSYNC_SSH" \
  "$M2_SRC" "$REMOTE:/home/tyq/.m2/repository/"

echo "=== verify ==="
"${SSH[@]}" "$REMOTE" 'ls -lh /home/tyq/projects/llm-kv/target/kart.jar; du -sh /home/tyq/.m2/repository'
echo "JAR_M2_DONE"
