#!/usr/bin/env bash
# Shared env for KART scripts (WSL).
# Canonical workspace: /home/jaytang/projects/llm-kv (ext4 copy).
# Compat symlink only: /home/jaytang/build/LLM_KV → same tree. Never rsync *to* the symlink path as a second tree.
# shellcheck disable=SC2034
KART_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# --- Canonical paths (override with env if needed) ---
export KART_SRC="${KART_SRC:-/mnt/f/Projects/LLM_KV}"
export KART_DST="${KART_DST:-/home/jaytang/projects/llm-kv}"
export KART_COMPAT="${KART_COMPAT:-/home/jaytang/build/LLM_KV}"
export KART_MAVEN_REPO="${KART_MAVEN_REPO:-$HOME/.m2/repository}"
export KART_EXPERIMENT_MANIFEST="${KART_EXPERIMENT_MANIFEST:-tdrive_v1_ready}"

export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-8-openjdk-amd64}"
export PATH="$JAVA_HOME/bin:/mnt/c/maven/apache-maven-3.6.3/bin:${PATH:-}"
export ZK_HOME="${ZK_HOME:-/home/jaytang/zookeeper}"
export HBASE_HOME="${HBASE_HOME:-/home/jaytang/hbase}"
export KART_JAR="${KART_JAR:-$KART_ROOT/target/kart.jar}"

# Optional user truststore (scripts/import-deepseek-ca.sh) — avoids intermittent PKIX on DeepSeek.
if [[ -f "${HOME}/.kart/truststore" ]]; then
  _kart_ts="-Djavax.net.ssl.trustStore=${HOME}/.kart/truststore -Djavax.net.ssl.trustStorePassword=changeit"
  case "${JAVA_TOOL_OPTIONS:-}" in
    *javax.net.ssl.trustStore=*) ;;
    *) export JAVA_TOOL_OPTIONS="${_kart_ts}${JAVA_TOOL_OPTIONS:+ $JAVA_TOOL_OPTIONS}" ;;
  esac
  unset _kart_ts
fi

kart_load_llm_env() {
  if [[ -f "$KART_ROOT/scripts/load-llm-env.sh" ]]; then
    # shellcheck disable=SC1091
    source "$KART_ROOT/scripts/load-llm-env.sh" || true
  fi
}

kart_ensure_compat_symlink() {
  mkdir -p "$(dirname "$KART_COMPAT")"
  if [[ ! -e "$KART_COMPAT" ]]; then
    ln -sfn "$KART_DST" "$KART_COMPAT"
  elif [[ -L "$KART_COMPAT" ]]; then
    ln -sfn "$KART_DST" "$KART_COMPAT"
  else
    echo "WARN: $KART_COMPAT exists and is not a symlink — leave untouched" >&2
  fi
}

kart_ensure_jar() {
  if [[ ! -f "$KART_JAR" ]]; then
    echo "Missing $KART_JAR — building…"
    bash "$KART_ROOT/scripts/rebuild-jar.sh"
  fi
}

kart_java() {
  kart_ensure_jar
  java -Dkart.root="$KART_ROOT" -Dkart.status="${KART_STATUS:-true}" -jar "$KART_JAR" "$@"
}
