#!/usr/bin/env bash
set -euo pipefail
export JAVA_HOME=/usr/lib/jvm/java-8-openjdk-amd64
export PATH="$JAVA_HOME/bin:$PATH"
SSH_KEY="$HOME/.ssh/id_ed25519_kart_server"
SSH=(ssh -i "$SSH_KEY" -o IdentitiesOnly=yes -o BatchMode=yes -o StrictHostKeyChecking=accept-new)
RSYNC_SSH="ssh -i $SSH_KEY -o IdentitiesOnly=yes -o BatchMode=yes -o StrictHostKeyChecking=accept-new"
REMOTE=tyq@10.242.104.108

echo "=== jps before ==="
jps || true

# Stop leftover HBase/ZK JVMs if still up
mapfile -t PIDS < <(jps | awk '/HMaster|HRegionServer|HQuorumPeer|QuorumPeerMain/{print $1}')
if ((${#PIDS[@]})); then
  echo "killing: ${PIDS[*]}"
  kill "${PIDS[@]}" 2>/dev/null || true
  sleep 3
  kill -9 "${PIDS[@]}" 2>/dev/null || true
fi
echo "=== jps after ==="
jps || true

echo "=== remote mkdir ==="
"${SSH[@]}" "$REMOTE" 'mkdir -p /home/tyq/projects /home/tyq/envs /home/tyq/kart-migrate-inbox /home/tyq/build; ls -la /home/tyq; df -h /home | tail -1'

echo "=== rsync CODE from Windows mount ==="
# Prefer live Windows tree for latest uncommitted work
rsync -a --info=progress2 \
  --exclude '.git/' \
  --exclude 'target/' \
  --exclude 'runs/' \
  --exclude 'experiments/results/' \
  --exclude '.jqwik-database' \
  -e "$RSYNC_SSH" \
  /mnt/f/Projects/LLM_KV/ \
  "$REMOTE:/home/tyq/projects/llm-kv/"

# Ensure .env from WSL canonical if Windows missing it
if [[ -f /home/jaytang/projects/llm-kv/.env ]]; then
  rsync -a -e "$RSYNC_SSH" \
    /home/jaytang/projects/llm-kv/.env \
    "$REMOTE:/home/tyq/projects/llm-kv/.env"
fi

echo "=== rsync HBASE install+data ==="
rsync -a --info=progress2 -e "$RSYNC_SSH" \
  /home/jaytang/envs/hbase-2.2.3/ \
  "$REMOTE:/home/tyq/envs/hbase-2.2.3/"

echo "=== rsync ZOOKEEPER install+data ==="
rsync -a --info=progress2 -e "$RSYNC_SSH" \
  /home/jaytang/envs/zookeeper-3.4.10/ \
  "$REMOTE:/home/tyq/envs/zookeeper-3.4.10/"

echo "=== APPLY PATHS / SYMLINKS ON SERVER ==="
"${SSH[@]}" "$REMOTE" 'bash -s' <<'REMOTE_EOF'
set -euo pipefail
HOME_TYQ=/home/tyq
ln -sfn "$HOME_TYQ/envs/hbase-2.2.3" "$HOME_TYQ/hbase"
ln -sfn "$HOME_TYQ/envs/zookeeper-3.4.10" "$HOME_TYQ/zookeeper"
mkdir -p "$HOME_TYQ/build"
ln -sfn "$HOME_TYQ/projects/llm-kv" "$HOME_TYQ/build/LLM_KV"

HB_SITE="$HOME_TYQ/envs/hbase-2.2.3/conf/hbase-site.xml"
ZK_CFG="$HOME_TYQ/envs/zookeeper-3.4.10/conf/zoo.cfg"
[[ -f "$HB_SITE" ]] && sed -i 's|/home/jaytang|/home/tyq|g' "$HB_SITE"
[[ -f "$ZK_CFG" ]] && sed -i 's|/home/jaytang|/home/tyq|g' "$ZK_CFG"

# Also rewrite any jaytang paths inside copied repo configs if present
if [[ -f "$HOME_TYQ/projects/llm-kv/config/hbase/hbase-site.xml" ]]; then
  sed -i 's|/home/jaytang|/home/tyq|g' "$HOME_TYQ/projects/llm-kv/config/hbase/hbase-site.xml" || true
fi

cat > "$HOME_TYQ/.kart_server_env" <<'EOF'
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-8-openjdk-amd64}"
export PATH="$JAVA_HOME/bin:$PATH"
export ZK_HOME="$HOME/zookeeper"
export HBASE_HOME="$HOME/hbase"
export KART_DST="$HOME/projects/llm-kv"
export KART_SRC="$KART_DST"
export KART_COMPAT="$HOME/build/LLM_KV"
export KART_MAVEN_REPO="$HOME/.m2/repository"
export KART_EXPERIMENT_MANIFEST=tdrive_v1_ready
mkdir -p "$(dirname "$KART_COMPAT")"
[[ -e "$KART_COMPAT" ]] || ln -sfn "$KART_DST" "$KART_COMPAT"
EOF

grep -q 'kart_server_env' "$HOME_TYQ/.bashrc" 2>/dev/null \
  || echo 'source "$HOME/.kart_server_env"' >> "$HOME_TYQ/.bashrc"

echo "--- hbase-site rootdir ---"
grep -E 'rootdir|dataDir' "$HB_SITE" || true
echo "--- zoo.cfg ---"
grep -E '^dataDir|^clientPort' "$ZK_CFG" || true
echo "--- tree ---"
ls -la "$HOME_TYQ" | head -30
ls -la "$HOME_TYQ/projects/llm-kv" | head -20
du -sh "$HOME_TYQ/projects/llm-kv" "$HOME_TYQ/envs/hbase-2.2.3" "$HOME_TYQ/envs/zookeeper-3.4.10"
REMOTE_EOF

echo "ALL_TRANSFER_DONE"
