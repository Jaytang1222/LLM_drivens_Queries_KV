#!/usr/bin/env bash
set -euo pipefail
KEY="${HOME}/.ssh/id_ed25519_kart_server"
mkdir -p "${HOME}/.ssh"
cp -f /mnt/c/Users/jayta/.ssh/id_ed25519_kart_server "$KEY"
chmod 600 "$KEY"
SSH=(ssh -i "$KEY" -o IdentitiesOnly=yes -o BatchMode=yes -o StrictHostKeyChecking=accept-new)
RSYNC_SSH="ssh -i $KEY -o IdentitiesOnly=yes -o BatchMode=yes -o StrictHostKeyChecking=accept-new"
REMOTE=tyq@10.242.104.108
ROOT_WIN=/mnt/f/Projects/LLM_KV

sed -i 's/\r$//' "$ROOT_WIN/scripts/sync-wsl-workspace.sh"

echo "=== push sync fix ==="
rsync -a -e "$RSYNC_SSH" \
  "$ROOT_WIN/scripts/sync-wsl-workspace.sh" \
  "$REMOTE:/home/tyq/projects/llm-kv/scripts/sync-wsl-workspace.sh"

# Ensure smoke oracle/workload exist on server (may be missing if only Windows tree synced)
for f in tdrive_smoke.json tdrive_smoke.oracle.json; do
  if [[ -f "/home/jaytang/projects/llm-kv/experiments/workloads/$f" ]]; then
    rsync -a -e "$RSYNC_SSH" \
      "/home/jaytang/projects/llm-kv/experiments/workloads/$f" \
      "$REMOTE:/home/tyq/projects/llm-kv/experiments/workloads/$f"
  fi
done

# Catalog/datasets if missing on server
"${SSH[@]}" "$REMOTE" 'bash -s' <<'REMOTE'
set -euo pipefail
source ~/.kart_server_env
cd "$KART_DST"
echo "=== prereqs ==="
ls -lh target/kart.jar
ls -lh experiments/workloads/tdrive_smoke.json experiments/workloads/tdrive_smoke.oracle.json
ls -lh catalog/tdrive_v1_ready.manifest.json
ls -ld datasets/tdrive || echo "WARN: datasets/tdrive missing (HBase tables already loaded — smoke may still work)"
grep -n 'same-tree' scripts/sync-wsl-workspace.sh

mkdir -p experiments/results runs/smoke-tdrive
echo "=== SMOKE JAVA ==="
java -Xmx4g -Dkart.root="$KART_DST" -jar target/kart.jar smoke-tdrive \
  --workload experiments/workloads/tdrive_smoke.json \
  --oracle experiments/workloads/tdrive_smoke.oracle.json \
  --catalog catalog \
  --manifest "$KART_EXPERIMENT_MANIFEST" \
  --report experiments/results/tdrive_smoke_report.json \
  --runs runs/smoke-tdrive
echo "SMOKE_DONE"
REMOTE
