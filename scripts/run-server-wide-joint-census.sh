#!/usr/bin/env bash
# Push wide/joint v3 pool to server, fill FullScan oracle, run opportunity census.
set -euo pipefail
KEY="${HOME}/.ssh/id_ed25519_kart_server"
mkdir -p "${HOME}/.ssh"
cp -f /mnt/c/Users/jayta/.ssh/id_ed25519_kart_server "$KEY"
chmod 600 "$KEY"
SSH=(ssh -i "$KEY" -o IdentitiesOnly=yes -o BatchMode=yes -o StrictHostKeyChecking=accept-new)
RSYNC_SSH="ssh -i $KEY -o IdentitiesOnly=yes -o BatchMode=yes -o StrictHostKeyChecking=accept-new"
REMOTE=tyq@10.242.104.108
ROOT_WIN=/mnt/f/Projects/LLM_KV

for f in \
  experiments/adapters/generate_cbo_opportunity_wide_joint_v3.py \
  experiments/workloads/bound_ir_cbo_opportunity_candidates_v3.json \
  experiments/workloads/bound_ir_cbo_opportunity_candidates_v3.candidates.json \
  experiments/workloads/bound_ir_cbo_opportunity_candidates_v3.provenance.json \
  scripts/fill-cbo-opportunity-oracle.sh \
  scripts/run-opportunity-census.sh \
  experiments/adapters/summarize_opportunity_census.py
do
  sed -i 's/\r$//' "$ROOT_WIN/$f" 2>/dev/null || true
  rsync -a -e "$RSYNC_SSH" "$ROOT_WIN/$f" "$REMOTE:/home/tyq/projects/llm-kv/$f"
done

"${SSH[@]}" "$REMOTE" 'bash -s' <<'REMOTE'
set -euo pipefail
source ~/.kart_server_env
cd "$KART_DST"
echo "=== doctor ==="
./scripts/kart.sh doctor 2>&1 | egrep 'doctor:|tables=|LLM_' || true
test -f target/kart.jar
jps | egrep 'HMaster|HRegionServer|QuorumPeerMain' || { echo "STACK_DOWN"; exit 2; }

echo "=== fill Oracle for candidates_v3 ==="
if [[ -f experiments/workloads/bound_ir_cbo_opportunity_candidates_v3.oracle.json ]]; then
  echo "oracle already present; skip fill"
else
  bash scripts/fill-cbo-opportunity-oracle.sh --prefix bound_ir_cbo_opportunity_candidates_v3
fi

echo "=== opportunity census wide-joint-v3 ==="
# Discovery pool only (18 queries). Repeat if coarse saving >= 200ms.
# Provisional hybrid extra_plan=900ms for labeling only.
bash scripts/run-opportunity-census.sh \
  --pool experiments/workloads/bound_ir_cbo_opportunity_candidates_v3.json \
  --no-extra-pool \
  --oracle experiments/workloads/bound_ir_cbo_opportunity_candidates_v3.oracle.json \
  --exclude experiments/workloads/bound_ir_holdout_v2_test.json \
  --run-id opportunity-wide-joint-v3 \
  --cache warm \
  --initial-trials 1 \
  --repeat-trials 3 \
  --max-exec-ms 60000 \
  --max-wall-minutes 180 \
  --repeat-gain-threshold-ms 200 \
  --hybrid-extra-plan-ms 900

echo "CENSUS_DONE"
REMOTE
