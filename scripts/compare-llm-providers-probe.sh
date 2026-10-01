#!/usr/bin/env bash
# Minimal DeepSeek vs Google Gemini plan-prompt latency probe (same compact payload).
# Usage:
#   bash scripts/compare-llm-providers-probe.sh
#   PROXY=http://127.0.0.1:7897 bash scripts/compare-llm-providers-probe.sh
#   REPEATS=3 MAX_TIME=30 bash scripts/compare-llm-providers-probe.sh
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck disable=SC1091
source "$ROOT/scripts/kart-env.sh"
ENVF="${LLM_ENV_FILE:-$ROOT/.env}"
REPEATS="${REPEATS:-3}"
MAX_TIME="${MAX_TIME:-45}"
PROXY="${PROXY:-${HTTPS_PROXY:-${https_proxy:-}}}"

load_keys() {
  unset LLM_BASE_URL LLM_MODEL LLM_API_KEY LLM_JSON_MODE LLM_PROVIDER
  unset GOOGLE_API_KEY GOOGLE_LLM_BASE_URL GOOGLE_LLM_MODEL
  kart_load_llm_env >/dev/null
  # Re-read Google vars (kart_load may have switched if LLM_PROVIDER already set)
  while IFS= read -r line || [[ -n "$line" ]]; do
    line="${line%$'\r'}"
    [[ -z "$line" || "$line" =~ ^[[:space:]]*# ]] && continue
    local key="${line%%=*}"
    local val="${line#*=}"
    key="$(echo "$key" | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')"
    val="$(echo "$val" | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')"
    val="${val#\"}"; val="${val%\"}"
    case "$key" in
      LLM_BASE_URL|LLM_MODEL|LLM_API_KEY|GOOGLE_API_KEY|GOOGLE_LLM_BASE_URL|GOOGLE_LLM_MODEL)
        export "$key=$val"
        ;;
    esac
  done < "$ENVF"
}

BODY_TMP="$(mktemp)"
python3 - <<'PY' >"$BODY_TMP"
import json
print(json.dumps({
  "temperature": 0,
  "messages": [
    {"role": "system", "content": "Pick one whitelist plan_id. Reply with only JSON {\"plan_id\":\"P_...\"}."},
    {"role": "user", "content": "prompt_version=cbo_llm_compact_v3\nReply ONLY JSON: {\"plan_id\":\"P_...\"}\ncbo_plan_id=P_TZ\nwhitelist=P_T,P_Z\n"},
  ],
}))
PY

probe_one() {
  local name="$1" base="$2" model="$3" key="$4"
  local url="${base%/}/chat/completions"
  local out_dir="/tmp/kart_llm_probe_${name}"
  mkdir -p "$out_dir"
  echo ""
  echo "=== provider=$name model=$model ==="
  echo "url=$url key_len=${#key}"
  local times=()
  local ok=0
  local i
  for i in $(seq 1 "$REPEATS"); do
    local body
    body="$(python3 -c "import json,sys; d=json.load(open(sys.argv[1])); d['model']=sys.argv[2]; print(json.dumps(d))" "$BODY_TMP" "$model")"
    local resp="$out_dir/r${i}.json"
    local curl_args=(-sS -o "$resp" -w "%{http_code} %{time_total} %{time_starttransfer}"
      --connect-timeout 10 --max-time "$MAX_TIME"
      -X POST "$url"
      -H "Authorization: Bearer $key"
      -H "Content-Type: application/json"
      -d "$body")
    if [[ -n "$PROXY" ]]; then
      curl_args+=(--proxy "$PROXY")
    fi
    local meta
    set +e
    meta="$(curl "${curl_args[@]}")"
    local ec=$?
    set -e
    local http t_total t_ttfb
    read -r http t_total t_ttfb <<<"$meta"
    local content="" err=""
    if [[ $ec -ne 0 ]]; then
      err="curl_exit_$ec"
    elif [[ ! -f "$resp" ]]; then
      err="no_body"
    else
      content="$(python3 - <<PY
import json
from pathlib import Path
p=Path("$resp")
try:
  j=json.loads(p.read_text(encoding="utf-8"))
except Exception as e:
  print("PARSE_ERR"); raise SystemExit
if isinstance(j, list):
  j = j[0] if j else {}
err=j.get("error")
if err:
  print("API_ERR:"+str(err)[:120])
  raise SystemExit
c=(j.get("choices") or [{}])[0].get("message",{}).get("content") or ""
print(c.replace("\\n"," ")[:160])
PY
)" || true
      if [[ "$content" == PARSE_ERR* || "$content" == API_ERR* ]]; then
        err="$content"
        content=""
      elif [[ "$http" != "200" ]]; then
        err="http_$http"
      else
        ok=$((ok+1))
      fi
    fi
    times+=("$t_total")
    printf "  try=%d http=%s time_total=%ss ttfb=%ss ok=%s content=%s\n" \
      "$i" "${http:-?}" "${t_total:-?}" "${t_ttfb:-?}" "${err:-yes}" "${content:-(none)}"
  done
  python3 - <<PY
times = list(map(float, """${times[*]}""".split()))
ok = int("$ok")
name = "$name"
if times:
  s = sorted(times)
  med = s[len(s)//2]
  print(f"SUMMARY {name}: n={len(times)} ok={ok}/{len(times)} "
        f"min={min(times):.3f}s med={med:.3f}s max={max(times):.3f}s mean={sum(times)/len(times):.3f}s")
else:
  print(f"SUMMARY {name}: no samples")
PY
}

load_keys
DS_BASE="${LLM_BASE_URL}"
DS_MODEL="${LLM_MODEL}"
DS_KEY="${LLM_API_KEY}"
GG_BASE="${GOOGLE_LLM_BASE_URL:-https://generativelanguage.googleapis.com/v1beta/openai}"
GG_MODEL="${GOOGLE_LLM_MODEL:-gemini-3.5-flash-lite}"
GG_KEY="${GOOGLE_API_KEY:-}"

if [[ -z "$DS_KEY" || -z "$GG_KEY" ]]; then
  echo "Need both DeepSeek LLM_API_KEY and GOOGLE_API_KEY in $ENVF" >&2
  exit 1
fi

echo "compare-llm-providers-probe repeats=$REPEATS max_time=${MAX_TIME}s proxy=${PROXY:-none}"
probe_one "deepseek" "$DS_BASE" "$DS_MODEL" "$DS_KEY"
probe_one "google" "$GG_BASE" "$GG_MODEL" "$GG_KEY"
rm -f "$BODY_TMP"
