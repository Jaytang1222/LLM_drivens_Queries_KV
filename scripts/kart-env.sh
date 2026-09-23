#!/usr/bin/env bash
# Shared env for KART scripts (WSL).
# shellcheck disable=SC2034
KART_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-8-openjdk-amd64}"
export PATH="$JAVA_HOME/bin:/mnt/c/maven/apache-maven-3.6.3/bin:${PATH:-}"
export ZK_HOME="${ZK_HOME:-/home/jaytang/zookeeper}"
export HBASE_HOME="${HBASE_HOME:-/home/jaytang/hbase}"
export KART_JAR="${KART_JAR:-$KART_ROOT/target/kart.jar}"

kart_load_llm_env() {
  if [[ -f "$KART_ROOT/scripts/load-llm-env.sh" ]]; then
    # shellcheck disable=SC1091
    source "$KART_ROOT/scripts/load-llm-env.sh" || true
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
