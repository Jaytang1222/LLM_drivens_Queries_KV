#!/usr/bin/env bash
# Shared env for KART (WSL). Sourced by kart.sh and other keepers.
# Canonical workspace: /home/jaytang/projects/llm-kv (ext4). Compat: ~/build/LLM_KV → DST.
# shellcheck disable=SC2034
KART_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# The Windows checkout is this repository; the WSL checkout follows $HOME so
# the scripts also work when the WSL login name and home-directory basename
# differ (as they do on the current machine).
export KART_SRC="${KART_SRC:-/mnt/f/code/InventoryApp}"
export KART_DST="${KART_DST:-${HOME}/projects/llm-kv}"
export KART_COMPAT="${KART_COMPAT:-${HOME}/build/LLM_KV}"
# Windows Maven settings often set localRepository to F:\maven-repository. Under WSL
# that path is relative to the project, so javac cannot see Jackson/json-schema.
# Prefer the mounted repo when it is present; otherwise the Linux ~/.m2 cache.
if [[ -n "${KART_MAVEN_REPO:-}" ]]; then
  export KART_MAVEN_REPO
elif [[ -d /mnt/f/maven-repository/com/networknt ]]; then
  export KART_MAVEN_REPO=/mnt/f/maven-repository
else
  export KART_MAVEN_REPO="$HOME/.m2/repository"
fi
export KART_EXPERIMENT_MANIFEST="${KART_EXPERIMENT_MANIFEST:-tdrive_v1_ready}"

export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-8-openjdk-amd64}"
export PATH="$JAVA_HOME/bin:/mnt/c/maven/apache-maven-3.6.3/bin:${PATH:-}"
export ZK_HOME="${ZK_HOME:-${HOME}/zookeeper}"
export HBASE_HOME="${HBASE_HOME:-${HOME}/hbase}"
export KART_JAR="${KART_JAR:-$KART_ROOT/target/kart.jar}"

# Optional user truststore (kart.sh truststore) — avoids intermittent PKIX on DeepSeek.
if [[ -f "${HOME}/.kart/truststore" ]]; then
  _kart_ts="-Djavax.net.ssl.trustStore=${HOME}/.kart/truststore -Djavax.net.ssl.trustStorePassword=changeit"
  case "${JAVA_TOOL_OPTIONS:-}" in
    *javax.net.ssl.trustStore=*) ;;
    *) export JAVA_TOOL_OPTIONS="${_kart_ts}${JAVA_TOOL_OPTIONS:+ $JAVA_TOOL_OPTIONS}" ;;
  esac
  unset _kart_ts
fi

# Load LLM_* from project .env (does not print secrets).
kart_load_llm_env() {
  local ENV_FILE="${LLM_ENV_FILE:-$KART_ROOT/.env}"
  if [[ ! -f "$ENV_FILE" ]]; then
    echo "missing $ENV_FILE" >&2
    return 1
  fi
  while IFS= read -r line || [[ -n "$line" ]]; do
    line="${line%$'\r'}"
    [[ -z "$line" || "$line" =~ ^[[:space:]]*# ]] && continue
    local key="${line%%=*}"
    local val="${line#*=}"
    key="$(echo "$key" | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')"
    val="$(echo "$val" | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')"
    val="${val#\"}"; val="${val%\"}"
    val="${val#\'}"; val="${val%\'}"
    case "$key" in
      LLM_BASE_URL|LLM_MODEL|LLM_API_KEY|LLM_JSON_MODE|LLM_PROVIDER|OPENAI_API_KEY|deepseek_api_key|GOOGLE_API_KEY|GOOGLE_LLM_BASE_URL|GOOGLE_LLM_MODEL)
        export "$key=$val"
        ;;
    esac
  done < "$ENV_FILE"
  if [[ -z "${LLM_API_KEY:-}" && -n "${OPENAI_API_KEY:-}" ]]; then
    export LLM_API_KEY="$OPENAI_API_KEY"
  fi
  if [[ -z "${LLM_API_KEY:-}" && -n "${deepseek_api_key:-}" ]]; then
    export LLM_API_KEY="$deepseek_api_key"
    export LLM_BASE_URL="${LLM_BASE_URL:-https://api.deepseek.com/v1}"
    export LLM_MODEL="${LLM_MODEL:-deepseek-flash}"
  fi
  # Switch active LLM_* from Google Gemini OpenAI-compatible profile.
  case "${LLM_PROVIDER:-}" in
    google|gemini|GOOGLE|GEMINI)
      if [[ -z "${GOOGLE_API_KEY:-}" ]]; then
        echo "LLM_PROVIDER=google requires GOOGLE_API_KEY in $ENV_FILE" >&2
        return 1
      fi
      export LLM_API_KEY="$GOOGLE_API_KEY"
      export LLM_BASE_URL="${GOOGLE_LLM_BASE_URL:-https://generativelanguage.googleapis.com/v1beta/openai}"
      export LLM_MODEL="${GOOGLE_LLM_MODEL:-gemini-3.5-flash-lite}"
      # Gemini OpenAI compat is flaky with response_format=json_object; rely on prompt.
      export LLM_JSON_MODE="${LLM_JSON_MODE_GOOGLE:-false}"
      ;;
  esac
  if [[ -z "${LLM_API_KEY:-}" || -z "${LLM_BASE_URL:-}" || -z "${LLM_MODEL:-}" ]]; then
    echo "Need LLM_BASE_URL + LLM_MODEL + LLM_API_KEY in $ENV_FILE (see docs/how-to-run.md)" >&2
    return 1
  fi
  echo "llm_env loaded provider=${LLM_PROVIDER:-deepseek} model=${LLM_MODEL} base=${LLM_BASE_URL} key_len=${#LLM_API_KEY} json_mode=${LLM_JSON_MODE:-true}"
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

# Sync Windows SRC → WSL DST, then Maven package jar (canonical rebuild path).
kart_rebuild_jar() {
  bash "$KART_ROOT/scripts/sync-wsl-workspace.sh"
  local REPO="${KART_MAVEN_REPO}" SRC="${KART_SRC}" DST="${KART_DST}"
  # Resolve a relative KART_SRC before changing into the WSL checkout.  This
  # keeps the documented KART_SRC=. fallback from copying a jar onto itself.
  local SRC_ABS
  SRC_ABS="$(cd "$SRC" && pwd)"
  cd "$DST"
  mvn -Dmaven.repo.local="$REPO" -q package -DskipTests
  mkdir -p "$SRC_ABS/target" "$DST/target"
  if [[ "$SRC_ABS/target/kart.jar" != "$DST/target/kart.jar" ]]; then
    cp -f target/kart.jar "$SRC_ABS/target/kart.jar"
  fi
  echo "jar rebuilt -> $SRC_ABS/target/kart.jar (built in $DST)"
}

kart_ensure_jar() {
  if [[ ! -f "$KART_JAR" ]]; then
    echo "Missing $KART_JAR — building…"
    kart_rebuild_jar
  fi
}

kart_java() {
  kart_ensure_jar
  java -Dkart.root="$KART_ROOT" -Dkart.status="${KART_STATUS:-true}" -jar "$KART_JAR" "$@"
}
