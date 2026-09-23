#!/usr/bin/env bash
# Source project .env into current shell (LLM_* only). Does not print secrets.
# Usage: source scripts/load-llm-env.sh
#
# Required:
#   LLM_BASE_URL=https://api.deepseek.com/v1
#   LLM_MODEL=deepseek-chat
#   LLM_API_KEY=sk-...
#   LLM_JSON_MODE=true
#
# Also accepted: OPENAI_API_KEY / deepseek_api_key as aliases for LLM_API_KEY.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${LLM_ENV_FILE:-$ROOT/.env}"
if [[ ! -f "$ENV_FILE" ]]; then
  echo "missing $ENV_FILE" >&2
  return 1 2>/dev/null || exit 1
fi
while IFS= read -r line || [[ -n "$line" ]]; do
  line="${line%$'\r'}"
  [[ -z "$line" || "$line" =~ ^[[:space:]]*# ]] && continue
  key="${line%%=*}"
  val="${line#*=}"
  key="$(echo "$key" | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')"
  val="$(echo "$val" | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')"
  val="${val#\"}"; val="${val%\"}"
  val="${val#\'}"; val="${val%\'}"
  case "$key" in
    LLM_BASE_URL|LLM_MODEL|LLM_API_KEY|LLM_JSON_MODE|OPENAI_API_KEY|deepseek_api_key)
      export "$key=$val"
      ;;
  esac
done < "$ENV_FILE"

# Aliases
if [[ -z "${LLM_API_KEY:-}" && -n "${OPENAI_API_KEY:-}" ]]; then
  export LLM_API_KEY="$OPENAI_API_KEY"
fi
if [[ -z "${LLM_API_KEY:-}" && -n "${deepseek_api_key:-}" ]]; then
  export LLM_API_KEY="$deepseek_api_key"
  export LLM_BASE_URL="${LLM_BASE_URL:-https://api.deepseek.com/v1}"
  export LLM_MODEL="${LLM_MODEL:-deepseek-chat}"
fi

if [[ -z "${LLM_API_KEY:-}" || -z "${LLM_BASE_URL:-}" || -z "${LLM_MODEL:-}" ]]; then
  echo "Need LLM_BASE_URL + LLM_MODEL + LLM_API_KEY in $ENV_FILE (see docs/how-to-run.md)" >&2
  return 1 2>/dev/null || exit 1
fi
echo "llm_env loaded model=${LLM_MODEL} base=${LLM_BASE_URL} key_len=${#LLM_API_KEY} json_mode=${LLM_JSON_MODE:-true}"
