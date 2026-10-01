#!/usr/bin/env bash
# Run ON the server as tyq after archives land under ~/kart-migrate-inbox/
# Layout target:
#   /home/tyq/projects/llm-kv
#   /home/tyq/envs/hbase-2.2.3
#   /home/tyq/envs/zookeeper-3.4.10
#   /home/tyq/hbase -> envs/hbase-2.2.3
#   /home/tyq/zookeeper -> envs/zookeeper-3.4.10
set -euo pipefail

HOME_TYQ="${HOME:-/home/tyq}"
INBOX="${1:-$HOME_TYQ/kart-migrate-inbox}"
cd "$HOME_TYQ"

mkdir -p "$HOME_TYQ/projects" "$HOME_TYQ/envs" "$INBOX"

if [[ -f "$INBOX/llm-kv-code.tgz" ]]; then
  echo "[code] extract"
  rm -rf "$HOME_TYQ/projects/llm-kv"
  tar -C "$HOME_TYQ/projects" -xzf "$INBOX/llm-kv-code.tgz"
fi
if [[ -f "$INBOX/llm-kv.env" ]]; then
  cp -f "$INBOX/llm-kv.env" "$HOME_TYQ/projects/llm-kv/.env"
  chmod 600 "$HOME_TYQ/projects/llm-kv/.env"
fi

if [[ -f "$INBOX/hbase-2.2.3.tgz" ]]; then
  echo "[hbase] extract (long)"
  rm -rf "$HOME_TYQ/envs/hbase-2.2.3"
  tar -C "$HOME_TYQ/envs" -xzf "$INBOX/hbase-2.2.3.tgz"
fi
if [[ -f "$INBOX/zookeeper-3.4.10.tgz" ]]; then
  echo "[zk] extract (long)"
  rm -rf "$HOME_TYQ/envs/zookeeper-3.4.10"
  tar -C "$HOME_TYQ/envs" -xzf "$INBOX/zookeeper-3.4.10.tgz"
fi

ln -sfn "$HOME_TYQ/envs/hbase-2.2.3" "$HOME_TYQ/hbase"
ln -sfn "$HOME_TYQ/envs/zookeeper-3.4.10" "$HOME_TYQ/zookeeper"

# Rewrite jaytang absolute paths -> tyq
HB_SITE="$HOME_TYQ/envs/hbase-2.2.3/conf/hbase-site.xml"
if [[ -f "$HB_SITE" ]]; then
  sed -i 's|/home/jaytang|/home/tyq|g' "$HB_SITE"
  echo "[hbase-site] rewritten paths:"
  grep -E 'rootdir|dataDir' "$HB_SITE" || true
fi
ZK_CFG="$HOME_TYQ/envs/zookeeper-3.4.10/conf/zoo.cfg"
if [[ -f "$ZK_CFG" ]]; then
  sed -i 's|/home/jaytang|/home/tyq|g' "$ZK_CFG"
  echo "[zoo.cfg] rewritten:"
  grep -E '^dataDir|^clientPort' "$ZK_CFG" || true
fi

# Shell env snippet
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

echo
echo "=== toolchain check ==="
command -v java && java -version 2>&1 | head -2 || echo "NEED: openjdk-8"
command -v mvn && mvn -v 2>&1 | head -2 || echo "NEED: maven"
command -v python3 && python3 --version || echo "NEED: python3"

echo
echo "Next (after JDK8+Maven present):"
echo "  source ~/.kart_server_env"
echo "  cd ~/projects/llm-kv"
echo "  ./scripts/kart.sh up"
echo "  ./scripts/kart.sh rebuild"
echo "  ./scripts/kart.sh doctor"
echo "  ./scripts/kart.sh smoke"
