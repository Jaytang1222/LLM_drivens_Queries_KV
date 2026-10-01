#!/usr/bin/env bash
set -euo pipefail
KEY="${HOME}/.ssh/id_ed25519_kart_server"
mkdir -p "${HOME}/.ssh"
cp -f /mnt/c/Users/jayta/.ssh/id_ed25519_kart_server "$KEY"
chmod 600 "$KEY"
SSH=(ssh -i "$KEY" -o IdentitiesOnly=yes -o BatchMode=yes -o StrictHostKeyChecking=accept-new)
RSYNC_SSH="ssh -i $KEY -o IdentitiesOnly=yes -o BatchMode=yes -o StrictHostKeyChecking=accept-new"
REMOTE=tyq@10.242.104.108
ROOT=/mnt/f/Projects/LLM_KV

"${SSH[@]}" "$REMOTE" 'mkdir -p /home/tyq/projects/llm-kv/experiments/workloads /home/tyq/projects/llm-kv/experiments/adapters /home/tyq/projects/llm-kv/scripts /home/tyq/projects/llm-kv/experiments/opportunity'

for f in \
  experiments/adapters/generate_cbo_opportunity_wide_joint_v3.py \
  experiments/workloads/bound_ir_cbo_opportunity_candidates_v3.json \
  experiments/workloads/bound_ir_cbo_opportunity_candidates_v3.candidates.json \
  experiments/workloads/bound_ir_cbo_opportunity_candidates_v3.provenance.json \
  scripts/fill-cbo-opportunity-oracle.sh \
  scripts/run-opportunity-census.sh \
  experiments/adapters/summarize_opportunity_census.py
do
  sed -i 's/\r$//' "$ROOT/$f"
  rsync -a -e "$RSYNC_SSH" "$ROOT/$f" "$REMOTE:/home/tyq/projects/llm-kv/$f"
  echo "synced $f"
done
echo SYNC_OK
