#!/usr/bin/env bash
# Build jar in WSL, push latency-pack sources+jar to server, set deepseek-flash, run plan smoke.
set -euo pipefail
KEY="${HOME}/.ssh/id_ed25519_kart_server"
mkdir -p "${HOME}/.ssh"
cp -f /mnt/c/Users/jayta/.ssh/id_ed25519_kart_server "$KEY"
chmod 600 "$KEY"
SSH=(ssh -i "$KEY" -o IdentitiesOnly=yes -o BatchMode=yes -o StrictHostKeyChecking=accept-new)
RSYNC_SSH="ssh -i $KEY -o IdentitiesOnly=yes -o BatchMode=yes -o StrictHostKeyChecking=accept-new"
REMOTE=tyq@10.242.104.108
WIN=/mnt/f/Projects/LLM_KV
WSL=/home/jaytang/projects/llm-kv

export JAVA_HOME=/usr/lib/jvm/java-8-openjdk-amd64
export PATH="$JAVA_HOME/bin:$PATH"

echo "=== sync sources to WSL + package ==="
rsync -a --exclude target --exclude .git "$WIN/src/" "$WSL/src/"
rsync -a "$WIN/scripts/kart-env.sh" "$WSL/scripts/kart-env.sh"
cd "$WSL"
mvn -q package -DskipTests
ls -lh target/kart.jar

echo "=== push to server ==="
"${SSH[@]}" "$REMOTE" 'mkdir -p /home/tyq/projects/llm-kv/target /home/tyq/projects/llm-kv/src/main/java/kart/bench /home/tyq/projects/llm-kv/experiments/suites /home/tyq/projects/llm-kv/scripts'
rsync -a -e "$RSYNC_SSH" "$WSL/target/kart.jar" "$REMOTE:/home/tyq/projects/llm-kv/target/kart.jar"
rsync -a -e "$RSYNC_SSH" "$WIN/src/main/java/kart/bench/CboLlmProposalArm.java" \
  "$REMOTE:/home/tyq/projects/llm-kv/src/main/java/kart/bench/CboLlmProposalArm.java"
rsync -a -e "$RSYNC_SSH" "$WIN/src/main/java/kart/bench/CboLlmProposalCache.java" \
  "$REMOTE:/home/tyq/projects/llm-kv/src/main/java/kart/bench/CboLlmProposalCache.java"
rsync -a -e "$RSYNC_SSH" "$WIN/experiments/suites/plan-cbo-llm-latency-pack-dev.yaml" \
  "$REMOTE:/home/tyq/projects/llm-kv/experiments/suites/plan-cbo-llm-latency-pack-dev.yaml"
rsync -a -e "$RSYNC_SSH" "$WIN/scripts/kart-env.sh" "$REMOTE:/home/tyq/projects/llm-kv/scripts/kart-env.sh"

echo "=== set LLM_MODEL=deepseek-flash on server .env ==="
"${SSH[@]}" "$REMOTE" 'bash -s' <<'REMOTE'
set -euo pipefail
ENVF=/home/tyq/projects/llm-kv/.env
if grep -q '^LLM_MODEL=' "$ENVF"; then
  sed -i 's/^LLM_MODEL=.*/LLM_MODEL=deepseek-flash/' "$ENVF"
else
  echo 'LLM_MODEL=deepseek-flash' >> "$ENVF"
fi
grep '^LLM_MODEL=' "$ENVF"
# SOCKS listener check
ss -ltn | grep -q ':1080' && echo SOCKS_OK || echo SOCKS_MISSING
source /home/tyq/.kart_server_env
cd "$KART_DST"
./scripts/kart.sh doctor 2>&1 | egrep 'doctor:|tables=|LLM_' || true
echo "=== plan-only latency pack (1 trial) ==="
./scripts/bench-plan.sh \
  --suite experiments/suites/plan-cbo-llm-latency-pack-dev.yaml \
  --run-id cbo-llm-latency-pack-dev-$(date +%Y%m%d-%H%M%S) \
  --trials 1
echo PACK_DONE
REMOTE
