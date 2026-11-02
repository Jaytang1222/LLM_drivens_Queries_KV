#!/usr/bin/env bash
# Unified KART operator entry — run inside WSL only.
# Source shared env/helpers: source scripts/kart.sh --lib
KART_AS_LIB=0
if [[ "${1:-}" == "--lib" ]]; then
  KART_AS_LIB=1
  shift
fi
set -euo pipefail
KART_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ROOT="$KART_ROOT"
cd "$KART_ROOT"

# --- shared env (former kart-env.sh) ---
# Canonical workspace: /home/jaytang/projects/llm-kv (ext4). Compat: ~/build/LLM_KV → DST.

# The Windows checkout is this repository; the WSL checkout follows $HOME so
# the scripts also work when the WSL login name and home-directory basename
# differ (as they do on the current machine).
export KART_SRC="${KART_SRC:-/mnt/f/Projects/LLM_KV}"
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
  bash "$KART_ROOT/scripts/sync.sh"
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

if [[ -z "${KART_ACTION_RULES:-}" && -f "$KART_ROOT/experiments/suites/dual_action_rules.json" ]]; then
  export KART_ACTION_RULES="$KART_ROOT/experiments/suites/dual_action_rules.json"
  export KART_ACTION_MODE="${KART_ACTION_MODE:-dual-rules}"
fi
# Classic beam (time/zorder/hash): keeps plan≪5s so dual-rules e2e is not drowned by XZT FastCost.
# Always export (not only when rules file is present); Java defaults off when unset.
export KART_ADVANCED_INDEXES="${KART_ADVANCED_INDEXES:-0}"
# Formal gate (mining may set G1=-1e9); restore default when unset.
export KART_ACTION_G1_MS="${KART_ACTION_G1_MS:-50}"

kart_java() {
  kart_ensure_jar
  java -Dkart.root="$KART_ROOT" -Dkart.status="${KART_STATUS:-true}" -jar "$KART_JAR" "$@"
}


kart_llm_tunnel() {
  local KEY="${KART_SSH_KEY:-$HOME/.ssh/id_ed25519_kart_server}"
  if [[ ! -f "$KEY" && -f /mnt/c/Users/jayta/.ssh/id_ed25519_kart_server ]]; then
    mkdir -p "$HOME/.ssh"
    cp -f /mnt/c/Users/jayta/.ssh/id_ed25519_kart_server "$KEY"
    chmod 600 "$KEY"
  fi
  local REMOTE="${KART_SSH_REMOTE:-tyq@10.242.104.108}"
  local PORT="${KART_SOCKS_PORT:-1080}"
  echo "Starting reverse SOCKS on $REMOTE:127.0.0.1:$PORT via $KEY"
  exec ssh -i "$KEY" \
    -o IdentitiesOnly=yes \
    -o BatchMode=yes \
    -o StrictHostKeyChecking=accept-new \
    -o ServerAliveInterval=30 \
    -o ServerAliveCountMax=3 \
    -o ExitOnForwardFailure=yes \
    -N -T \
    -R "127.0.0.1:${PORT}" \
    "$REMOTE"
}

kart_drop_cache_install() {
  local HELPER="/usr/local/sbin/kart-drop-page-cache"
  local SUDOERS="/etc/sudoers.d/kart-drop-caches"
  local USER_NAME
  USER_NAME="$(id -un)"
  echo "Installing helper at $HELPER for user $USER_NAME"
  sudo tee "$HELPER" >/dev/null <<'EOF'
#!/bin/sh
sync
echo 3 > /proc/sys/vm/drop_caches
EOF
  sudo chmod 755 "$HELPER"
  sudo tee "$SUDOERS" >/dev/null <<EOF
$USER_NAME ALL=(root) NOPASSWD: $HELPER
EOF
  sudo chmod 440 "$SUDOERS"
  sudo visudo -cf "$SUDOERS"
  echo "Self-test:"
  sudo -n "$HELPER"
  echo "EXIT:$? — export KART_DROP_PAGE_CACHE=1 for formal cold runs"
}

kart_framework_check() {
  local FAIL=0
  cd "$KART_ROOT"
  ok() { echo "PASS  $*"; }
  bad() { echo "FAIL  $*"; FAIL=$((FAIL + 1)); }
  need_file() {
    if [[ -f "$1" ]]; then ok "file $1"; else bad "missing $1"; fi
  }

  local SUITES=src/main/resources/experiments/suites.yaml
  need_file "$SUITES"
  need_file src/main/resources/config/kart.yaml
  need_file src/main/resources/config/hbase/hbase-site.xml
  need_file src/main/resources/config/cost_coeffs_uncalibrated.yaml
  need_file src/main/resources/schemas/draft-ir.schema.json
  need_file src/main/resources/schemas/bound-ir.schema.json
  need_file src/main/resources/schemas/plan.schema.json
  need_file src/main/resources/schemas/action-selection.schema.json
  need_file datasets/tdrive-query/E1_query.json
  need_file datasets/tdrive-query/simple_query.json
  need_file datasets/tdrive-query/complex_query.json
  need_file datasets/tdrive-query/query.json
  need_file experiments/adapters/llm_client.py
  need_file experiments/adapters/din/din_sql_bridge.py
  need_file experiments/adapters/sag/sag_mql_bridge.py
  need_file experiments/adapters/direct/direct_draft_bridge.py
  need_file experiments/adapters/translate/sql_to_draft_ir.py
  need_file experiments/adapters/translate/mql_to_draft_ir.py
  need_file experiments/adapters/bao/bao_bridge.py
  need_file experiments/adapters/bao/kart_pg_plan_encode.py
  need_file experiments/adapters/paramtree/paramtree_bridge.py
  need_file experiments/adapters/llmopt/llmopt_bridge.py
  need_file scripts/kart.sh
  need_file scripts/sync.sh
  need_file scripts/comparative.sh
  need_file scripts/parameter.sh
  need_file scripts/ablation.sh

  if grep -q 'sag-mql-nofeedback' "$SUITES" \
    && ! grep -E '^\s+-\s+sag-mql\s*$' "$SUITES" >/dev/null; then
    ok "suites catalog defaults sag-mql-nofeedback"
  else
    bad "parse suite must use sag-mql-nofeedback without bare sag-mql"
  fi

  local id
  for id in plan e2e e2e-learned ablation param; do
    if grep -q "^  ${id}:" "$SUITES" \
      && awk -v id="$id" '
        $0 ~ "^  "id":" {p=1; next}
        p && /^  [a-z]/ {exit}
        p && /workload: datasets\/tdrive-query\/complex_query.json/ {w=1}
        p && /oracle: datasets\/tdrive-query\/complex_query.json/ {o=1}
        END {exit !(w && o)}
      ' "$SUITES"; then
      ok "suite $id → complex_query"
    else
      bad "suite $id must use complex_query workload/oracle"
    fi
  done
  for id in plan-simple e2e-simple e2e-learned-simple ablation-simple param-simple; do
    if grep -q "^  ${id}:" "$SUITES" \
      && awk -v id="$id" '
        $0 ~ "^  "id":" {p=1; next}
        p && /^  [a-z]/ {exit}
        p && /workload: datasets\/tdrive-query\/simple_query.json/ {w=1}
        p && /oracle: datasets\/tdrive-query\/simple_query.json/ {o=1}
        END {exit !(w && o)}
      ' "$SUITES"; then
      ok "suite $id → simple_query"
    else
      bad "suite $id must use simple_query workload/oracle"
    fi
  done

  if awk '
    /^  param:/ {p=1; next}
    p && /^  [a-z]/ {exit}
    p && /grid_mode: oat/ {g=1}
    p && /beam_width:/ {b=1}
    p && /max_candidates:/ {m=1}
    p && /base_arm: kart/ {k=1}
    p && /max_llm_calls:/ {bad=1}
    END {exit !(g && b && m && k && !bad)}
  ' "$SUITES"; then
    ok "param OAT + beam/candidates"
  else
    bad "param suite must be OAT with kart + beam/candidates (no max_llm_calls)"
  fi

  if grep -A20 '^  parse:' "$SUITES" | grep -q 'workload: datasets/tdrive-query/E1_query.json'; then
    ok "parse → E1_query"
  else
    bad "parse suite must use E1_query"
  fi

  python3 - <<'PY' || bad "frozen tdrive-query / suites integrity"
import json
from pathlib import Path

e1 = json.loads(Path("datasets/tdrive-query/E1_query.json").read_text(encoding="utf-8"))
sp = json.loads(Path("datasets/tdrive-query/simple_query.json").read_text(encoding="utf-8"))
cx = json.loads(Path("datasets/tdrive-query/complex_query.json").read_text(encoding="utf-8"))
assert e1.get("frozen") is True and len(e1["queries"]) == 36
assert len(sp["queries"]) == 24 and len(sp["answers"]) == 24
assert len(cx["queries"]) == 24 and len(cx["answers"]) == 24
text = Path("src/main/resources/experiments/suites.yaml").read_text(encoding="utf-8")
for sid in ("parse", "e2e", "e2e-simple", "ablation", "param", "plan", "e2e-learned"):
    assert ("  %s:" % sid) in text, sid
print("PASS  suites catalog + tdrive-query")
PY

  if [[ "$FAIL" -gt 0 ]]; then
    echo "=== framework-check: FAILED ($FAIL) ==="
    return 1
  fi
  echo "=== framework-check: OK ==="
}

kart_run_suite() {
  local suite_id="$1" run_id="$2"
  shift 2
  kart_load_llm_env || true
  export KART_ADVANCED_INDEXES="${KART_ADVANCED_INDEXES:-1}"
  export KART_ACTION_G1_MS="${KART_ACTION_G1_MS:-50}"
  kart_ensure_jar
  if [[ "${KART_DROP_PAGE_CACHE:-}" == "1" ]]; then
    if command -v sudo >/dev/null && [[ -x /usr/local/sbin/kart-drop-page-cache ]]; then
      sudo -n /usr/local/sbin/kart-drop-page-cache || echo "WARN: page-cache drop failed" >&2
    fi
  fi
  echo "=== bench-suite suite=$suite_id run_id=$run_id $* ==="
  java -Dkart.root="$KART_ROOT" -Dkart.status="${KART_STATUS:-true}" -jar "$KART_JAR" \
    bench-suite --suite "$suite_id" --run-id "$run_id" "$@"
}

kart_paper_report() {
  python3 - "$@" <<'KART_PAPER_PY'
"""Build paper-style md tables from bench-suite jsonl (formal result docs)."""
from __future__ import annotations

import argparse
import json
import math
from collections import defaultdict
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

ARM_E1 = {
    "kart": "KART",
    "direct-draftir": "DirectIR",
    "din-sql-spider": "DIN-SQL (Spider)",
    "din-sql-bird": "DIN-SQL (Bird)",
    "sag-mql-nofeedback": "SAG-MQL",
    "sag-mql": "SAG-MQL",
}
ARM_E3 = {
    "kart": "KART",
    "fullscan": "FullScan",
    "rbo": "RBO",
    "cbo": "CBO",
    "bao": "Bao",
    "paramtree": "Paramtree",
}
ABLATION = {
    "kart": "KART",
    "full": "KART",
    "no_rule_llm": "KART w/o rule LLM",
    "no_costmodel_llm": "KART w/o costmodel LLM",
    "no_costmodel": "KART w/o costmodel",
    "no_verify": "KART w/o verify",
}


def load_jsonl(path: Path) -> List[Dict[str, Any]]:
    rows: List[Dict[str, Any]] = []
    if not path.is_file():
        return rows
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        line = line.strip()
        if not line:
            continue
        try:
            rows.append(json.loads(line))
        except Exception:
            continue
    return rows


def find_trials(run_dir: Path) -> List[Dict[str, Any]]:
    rows: List[Dict[str, Any]] = []
    for name in ("e2e.jsonl", "parse.jsonl", "plan.jsonl", "trials.jsonl"):
        rows.extend(load_jsonl(run_dir / name))
    if rows:
        return rows
    for p in sorted(run_dir.glob("*.jsonl")):
        rows.extend(load_jsonl(p))
    return rows


def p50(vals: List[float]) -> Optional[float]:
    xs = sorted(v for v in vals if v is not None and not (isinstance(v, float) and math.isnan(v)))
    if not xs:
        return None
    return float(xs[(len(xs) - 1) // 2])


def p50_per_query(rows: List[Dict[str, Any]], key: str) -> Optional[float]:
    """Median across queries of (per-query median over trials)."""
    by_q: Dict[str, List[float]] = defaultdict(list)
    for r in rows:
        v = num(r, key)
        if v is not None:
            by_q[str(r.get("query_id", "?"))].append(v)
    qs = [p50(vs) for vs in by_q.values()]
    return p50([x for x in qs if x is not None])


def pct_per_query(rows: List[Dict[str, Any]], key: str, p: float) -> Optional[float]:
    """Percentile across queries of (per-query lower-median over trials)."""
    by_q: Dict[str, List[float]] = defaultdict(list)
    for r in rows:
        v = num(r, key)
        if v is not None:
            by_q[str(r.get("query_id", "?"))].append(v)
    qs = [p50(vs) for vs in by_q.values()]
    xs = sorted(x for x in qs if x is not None)
    if not xs:
        return None
    if p == 50:
        return p50(xs)
    return float(xs[int(round((p / 100.0) * (len(xs) - 1)))])


def latency_at(rows: List[Dict[str, Any]], p: float = 50) -> Tuple[Optional[float], Optional[float], Optional[float]]:
    """plan/exec via per-query median → overall p; e2e := plan+exec (never independent t_e2e pct)."""
    plan = pct_per_query(rows, "t_plan_ms", p)
    exe = pct_per_query(rows, "t_exec_ms", p)
    if plan is not None and exe is not None:
        return plan + exe, plan, exe
    return None, plan, exe


def latency_p50(rows: List[Dict[str, Any]]) -> Tuple[Optional[float], Optional[float], Optional[float]]:
    return latency_at(rows, 50)


def num(row: Dict[str, Any], *keys: str) -> Optional[float]:
    for k in keys:
        v = row.get(k)
        if isinstance(v, (int, float)) and not (isinstance(v, float) and math.isnan(v)):
            return float(v)
    return None


def fmt(v: Optional[float]) -> str:
    if v is None:
        return "-"
    if abs(v - round(v)) < 1e-6:
        return str(int(round(v)))
    return f"{v:.2f}"


def rate(ok: int, n: int) -> str:
    if n <= 0:
        return "-"
    return f"{ok / n:.4g}".rstrip("0").rstrip(".") if ok / n not in (0.0, 1.0) else f"{ok / n:.2f}"


def _e1_level_map() -> Dict[str, str]:
    p = Path("datasets/tdrive-query/E1_query.json")
    if not p.is_file():
        return {}
    try:
        wl = json.loads(p.read_text(encoding="utf-8"))
        return {str(q.get("query_id")): str(q.get("eval_level", "")).lower() for q in wl.get("queries", [])}
    except Exception:
        return {}


def _qclass(row: Dict[str, Any]) -> str:
    qc = str(row.get("query_class", "")).lower()
    if qc in ("reject", "clarify"):
        return qc
    return "pass"


def _qlevel(row: Dict[str, Any], level_of: Dict[str, str]) -> str:
    el = row.get("eval_level")
    if el:
        return str(el).lower()
    return level_of.get(str(row.get("query_id", "")), "")


def write_e1(run_dir: Path, out: Path) -> None:
    level_of = _e1_level_map()
    rows = [r for r in find_trials(run_dir) if str(r.get("stage", "")).lower() == "parse" or "query_class" in r]
    by_arm: Dict[str, List[Dict[str, Any]]] = defaultdict(list)
    for r in rows:
        by_arm[str(r.get("arm", "?"))].append(r)

    lines = [
        "### E1 NL→IR",
        "",
        "| method | complex Pass | complex Reject | complex Clarify | simple Pass | simple Reject | simple Clarify | complex t_parse p50 | simple t_parse p50 |",
        "|---|---:|---:|---:|---:|---:|---:|---:|---:|",
    ]
    order = [a for a in ARM_E1 if a in by_arm] + [a for a in by_arm if a not in ARM_E1]
    for arm in order:
        rs = by_arm[arm]
        cells = []
        for level in ("complex", "simple"):
            sub = [r for r in rs if _qlevel(r, level_of) == level]
            for qc in ("pass", "reject", "clarify"):
                bucket = [r for r in sub if _qclass(r) == qc]
                ok = sum(1 for r in bucket if r.get("ok_ex") is True)
                cells.append(rate(ok, len(bucket)))
        for level in ("complex", "simple"):
            sub = [
                r
                for r in rs
                if _qlevel(r, level_of) == level and _qclass(r) == "pass" and r.get("ok_ex") is True
            ]
            lat = [num(r, "t_parse_total_ms", "t_parse_ms") for r in sub]
            cells.append(fmt(p50([x for x in lat if x is not None])))
        lines.append("| " + " | ".join([ARM_E1.get(arm, arm)] + cells) + " |")
    lines.append("")
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print("wrote", out)


def write_e3(complex_dir: Path, simple_dir: Path, out: Path) -> None:
    def table(run_dir: Path, title: str) -> List[str]:
        rows = [r for r in find_trials(run_dir) if str(r.get("stage", "e2e")).lower() == "e2e"]
        by_arm: Dict[str, List[Dict[str, Any]]] = defaultdict(list)
        for r in rows:
            by_arm[str(r.get("arm", "?"))].append(r)
        lines = [f"### {title}", "", "| arm | e2e50 | plan50 | exec50 |", "|---|---:|---:|---:|"]
        order = [a for a in ARM_E3 if a in by_arm] + [a for a in by_arm if a not in ARM_E3]
        for arm in order:
            rs = [r for r in by_arm[arm] if r.get("ok_oracle") is True or r.get("ok") is True]
            if not rs:
                rs = by_arm[arm]
            e2e, plan, exe = latency_p50(rs)
            lines.append(f"| {ARM_E3.get(arm, arm)} | {fmt(e2e)} | {fmt(plan)} | {fmt(exe)} |")
        lines.append("")
        return lines

    body = table(complex_dir, "complex") + table(simple_dir, "simple")
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text("\n".join(body) + "\n", encoding="utf-8")
    print("wrote", out)


def _ablation_label(row: Dict[str, Any]) -> str:
    factor = row.get("factor") or (row.get("extras") or {}).get("factor") if isinstance(row.get("extras"), dict) else None
    if not factor or factor in ("full", "None", "null"):
        arm = str(row.get("arm", "kart"))
        return ABLATION.get(arm, "KART")
    return ABLATION.get(str(factor), str(factor))


def write_ablation(complex_dir: Path, simple_dir: Path, out: Path) -> None:
    def table(run_dir: Path, title: str) -> List[str]:
        rows = [r for r in find_trials(run_dir) if str(r.get("stage", "e2e")).lower() == "e2e"]
        by: Dict[str, List[Dict[str, Any]]] = defaultdict(list)
        for r in rows:
            by[_ablation_label(r)].append(r)
        lines = [
            f"### {title}",
            "",
            "| arm | e2e50 | plan50 | exec50 | pass |",
            "|---|---:|---:|---:|---:|",
        ]
        order = [ABLATION[k] for k in ABLATION if ABLATION[k] in by]
        order += [k for k in by if k not in order]
        for label in order:
            rs = by[label]
            ok_rs = [r for r in rs if r.get("ok_oracle") is True]
            n = len(rs)
            ok = len(ok_rs) if ok_rs else sum(1 for r in rs if r.get("ok") is True)
            use = ok_rs if ok_rs else rs
            e2e, plan, exe = latency_p50(use)
            lines.append(f"| {label} | {fmt(e2e)} | {fmt(plan)} | {fmt(exe)} | {rate(ok, n)} |")
        lines.append("")
        return lines

    body = table(complex_dir, "complex warm") + table(simple_dir, "simple warm")
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text("\n".join(body) + "\n", encoding="utf-8")
    print("wrote", out)


def write_parameter(complex_dir: Path, simple_dir: Path, out: Path) -> None:
    # OAT pins match kart.yaml / suite defaults: beam=3, max_candidates=8.
    DEF_BEAM = 3
    DEF_MC = 8

    cx = [r for r in find_trials(complex_dir) if str(r.get("stage", "e2e")).lower() == "e2e"]
    sp = [r for r in find_trials(simple_dir) if str(r.get("stage", "e2e")).lower() == "e2e"]

    def ok_rows(rows: List[Dict[str, Any]], pred) -> List[Dict[str, Any]]:
        rs = [r for r in rows if pred(r)]
        ok = [r for r in rs if r.get("ok_oracle") is True or r.get("ok") is True]
        return ok if ok else rs

    def metric_p50(rows: List[Dict[str, Any]], key: str, pred) -> Optional[float]:
        use = ok_rows(rows, pred)
        if key == "t_e2e_ms":
            e2e, _, _ = latency_p50(use)
            return e2e
        if key == "t_plan_ms":
            _, plan, _ = latency_p50(use)
            return plan
        if key == "t_exec_ms":
            _, _, exe = latency_p50(use)
            return exe
        return p50_per_query(use, key)

    def beam_table(metric: str) -> List[str]:
        lines = [
            f"#### beamwidth (max_candidates={DEF_MC})",
            "",
            "| beamwidth | complex | simple |",
            "|---:|---:|---:|",
        ]
        for bw in (1, 3, 5, 7):
            def pred(r, b=bw):
                o = r.get("overrides") or {}
                return int(o.get("beam_width", r.get("beam_width", -1)) or -1) == b and int(
                    o.get("max_candidates", r.get("max_candidates", DEF_MC)) or DEF_MC
                ) == DEF_MC

            lines.append(
                f"| {bw} | {fmt(metric_p50(cx, metric, pred))} | {fmt(metric_p50(sp, metric, pred))} |"
            )
        lines.append("")
        return lines

    def cand_table(metric: str) -> List[str]:
        lines = [
            f"#### max_candidates (beamwidth={DEF_BEAM})",
            "",
            "| max_candidates | complex | simple |",
            "|---:|---:|---:|",
        ]
        for mc in (4, 6, 8, 10):
            def pred(r, m=mc):
                o = r.get("overrides") or {}
                return int(o.get("beam_width", r.get("beam_width", DEF_BEAM)) or DEF_BEAM) == DEF_BEAM and int(
                    o.get("max_candidates", r.get("max_candidates", -1)) or -1
                ) == m

            lines.append(
                f"| {mc} | {fmt(metric_p50(cx, metric, pred))} | {fmt(metric_p50(sp, metric, pred))} |"
            )
        lines.append("")
        return lines

    def qtype_table(metric: str) -> List[str]:
        lines = [
            f"#### query type (beamwidth={DEF_BEAM}, max_candidates={DEF_MC})",
            "",
            "| query type | complex | simple |",
            "|---|---:|---:|",
        ]
        for qt in ("SRQ", "ST-Ranges", "SS", "topk"):
            def pred(r, q=qt):
                o = r.get("overrides") or {}
                if int(o.get("beam_width", r.get("beam_width", DEF_BEAM)) or DEF_BEAM) != DEF_BEAM:
                    return False
                if int(o.get("max_candidates", r.get("max_candidates", DEF_MC)) or DEF_MC) != DEF_MC:
                    return False
                return str(r.get("query_type", "")) == q

            lines.append(
                f"| {qt} | {fmt(metric_p50(cx, metric, pred))} | {fmt(metric_p50(sp, metric, pred))} |"
            )
        lines.append("")
        return lines

    body = ["### E2E", ""] + beam_table("t_e2e_ms") + cand_table("t_e2e_ms") + qtype_table("t_e2e_ms")
    body += ["### Plan", ""] + beam_table("t_plan_ms") + cand_table("t_plan_ms") + qtype_table("t_plan_ms")
    body += ["### Exec", ""] + beam_table("t_exec_ms") + cand_table("t_exec_ms") + qtype_table("t_exec_ms")
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text("\n".join(body) + "\n", encoding="utf-8")
    print("wrote", out)


QTYPE_ORDER = ["SRQ", "ST-Ranges", "SS", "topk"]


def _infer_qtype(query_id: str, explicit: Any) -> str:
    if explicit:
        t = str(explicit).strip()
        if t:
            if t.lower() in ("stranges", "st_ranges", "st-ranges"):
                return "ST-Ranges"
            if t.lower() in ("ss", "similarity"):
                return "SS"
            if t.lower() in ("topk", "top-k", "top_k"):
                return "topk"
            if t.upper() == "SRQ":
                return "SRQ"
            return t
    qid = str(query_id or "")
    if "_ss_" in qid:
        return "SS"
    if "_topk_" in qid:
        return "topk"
    if "_str_" in qid:
        return "ST-Ranges"
    return "SRQ"


def _index_name(plan_id: Optional[str]) -> str:
    """Paper-facing index label from plan_id (not the plan id itself)."""
    if not plan_id:
        return "-"
    pid = str(plan_id).upper()
    # Strip optional suffixes like _MP / fetch variants.
    core = pid.split("+")[0].split("-")[0]
    mapping = {
        "P_FULL": "fullscan",
        "P_T": "time",
        "P_Z": "z",
        "P_H": "hash",
        "P_TZ": "time+z",
        "P_TH": "time+hash",
        "P_ZH": "z+hash",
        "P_TZH": "time+z+hash",
        "P_XZ": "XZ*",
        "P_TS": "TShape",
        "P_XZT": "XZT",
        "P_XZTXZ": "XZT+XZ*",
        "P_XZTTS": "XZT+TShape",
        "P_XZTZ": "XZT+z",
        "P_XZTH": "XZT+hash",
        "P_XZH": "XZ*+hash",
        "P_TSH": "TShape+hash",
        "P_XZTXZH": "XZT+XZ*+hash",
        "P_XZTTSH": "XZT+TShape+hash",
        "P_XZTZH": "XZT+z+hash",
    }
    if core in mapping:
        return mapping[core]
    # Prefix fallbacks (longer first).
    for key, name in sorted(mapping.items(), key=lambda kv: -len(kv[0])):
        if core.startswith(key):
            return name
    return core


def _mode_plan_id(rows: List[Dict[str, Any]]) -> Optional[str]:
    counts: Dict[str, int] = defaultdict(int)
    for r in rows:
        pid = r.get("plan_id")
        if pid:
            counts[str(pid)] += 1
    if not counts:
        return None
    return sorted(counts.items(), key=lambda kv: (-kv[1], kv[0]))[0][0]


def _pick_case_queries(rows: List[Dict[str, Any]]) -> Dict[str, str]:
    """Stable typical query per type: lexicographically first query_id present."""
    by_type: Dict[str, List[str]] = defaultdict(list)
    for r in rows:
        qid = str(r.get("query_id", ""))
        if not qid:
            continue
        qt = _infer_qtype(qid, r.get("query_type"))
        by_type[qt].append(qid)
    picked: Dict[str, str] = {}
    for qt in QTYPE_ORDER:
        ids = sorted(set(by_type.get(qt, [])))
        if ids:
            picked[qt] = ids[0]
    return picked


def write_casestudy(complex_dir: Path, simple_dir: Path, out: Path) -> None:
    """Per query-type typical query: chosen plan/index + plan/exec/e2e (per-query median). MD only."""

    def section(run_dir: Path, title: str) -> List[str]:
        rows = [r for r in find_trials(run_dir) if str(r.get("stage", "e2e")).lower() == "e2e"]
        picked = _pick_case_queries(rows)
        lines = [f"### {title}", ""]
        if not picked:
            lines.append("_no e2e trials_")
            lines.append("")
            return lines
        lines.append("| query_type | query_id | arm | plan_id (index) | plan50 | exec50 | e2e50 |")
        lines.append("|---|---|---|---|---:|---:|---:|")
        for qt in QTYPE_ORDER:
            qid = picked.get(qt)
            if not qid:
                continue
            lines.append(f"| **{qt}** | `{qid}` | | | | | |")
            by_arm: Dict[str, List[Dict[str, Any]]] = defaultdict(list)
            for r in rows:
                if str(r.get("query_id")) != qid:
                    continue
                by_arm[str(r.get("arm", "?"))].append(r)
            order = [a for a in ARM_E3 if a in by_arm] + [a for a in by_arm if a not in ARM_E3]
            for arm in order:
                rs_ok = [r for r in by_arm[arm] if r.get("ok_oracle") is True or r.get("ok") is True]
                rs = rs_ok if rs_ok else by_arm[arm]
                pid = _mode_plan_id(rs_ok) if rs_ok else None
                plan = p50([x for x in (num(r, "t_plan_ms") for r in rs_ok) if x is not None]) if rs_ok else None
                exe = p50([x for x in (num(r, "t_exec_ms") for r in rs_ok) if x is not None]) if rs_ok else None
                e2e = p50([x for x in (num(r, "t_e2e_ms") for r in rs_ok) if x is not None]) if rs_ok else None
                label = _index_name(pid) if rs_ok else "-"
                lines.append(
                    f"| {qt} | {qid} | {ARM_E3.get(arm, arm)} | {label} | {fmt(plan)} | {fmt(exe)} | {fmt(e2e)} |"
                )
            lines.append("")
        return lines

    body = ["# Case Study", "", "Typical query per type from comparative E3; timings = median over trials.", ""]
    body += section(complex_dir, "complex")
    body += section(simple_dir, "simple")
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text("\n".join(body) + "\n", encoding="utf-8")
    print("wrote", out)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("kind", choices=["e1", "e3", "ablation", "parameter", "casestudy"])
    ap.add_argument("--out", required=True)
    ap.add_argument("--run", action="append", default=[])
    args = ap.parse_args()
    out = Path(args.out)
    runs = [Path(p) for p in args.run]
    if args.kind == "e1":
        write_e1(runs[0], out)
    elif args.kind == "e3":
        write_e3(runs[0], runs[1], out)
    elif args.kind == "ablation":
        write_ablation(runs[0], runs[1], out)
    elif args.kind == "parameter":
        write_parameter(runs[0], runs[1], out)
    else:
        write_casestudy(runs[0], runs[1], out)


if __name__ == "__main__":
    main()
KART_PAPER_PY
}

kart_print_server_env() {
  cat <<'KART_SERVER_ENV'
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

# Ensure .env is auto-loaded for LLM experiments when present
if [[ -f "$HOME/projects/llm-kv/.env" ]]; then
  set -a
  # shellcheck disable=SC1090
  source "$HOME/projects/llm-kv/.env"
  set +a
fi

# Egress via laptop SOCKS reverse-forward: ./scripts/kart.sh tunnel
# Prefer HTTP CONNECT proxy over SOCKS for Java HttpURLConnection stability.
export KART_HTTP_PROXY="${KART_HTTP_PROXY:-http://127.0.0.1:17890}"
export ALL_PROXY="$KART_HTTP_PROXY"
export HTTPS_PROXY="$KART_HTTP_PROXY"
export HTTP_PROXY="$KART_HTTP_PROXY"
export https_proxy="$KART_HTTP_PROXY"
export http_proxy="$KART_HTTP_PROXY"
export no_proxy="127.0.0.1,localhost"
export NO_PROXY="127.0.0.1,localhost"
# Strip any prior SOCKS JVM flags; set HTTP(S) proxy for OpenAiCompatibleClient.
_jto="${JAVA_TOOL_OPTIONS:-}"
_jto="$(printf '%s' "$_jto" | sed -E 's/-DsocksProxyHost=[^ ]*//g; s/-DsocksProxyPort=[^ ]*//g; s/-Dhttps.proxyHost=[^ ]*//g; s/-Dhttps.proxyPort=[^ ]*//g; s/-Dhttp.proxyHost=[^ ]*//g; s/-Dhttp.proxyPort=[^ ]*//g; s/  +/ /g; s/^ //; s/ $//')"
_jto="${_jto:+$_jto }-Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=17890 -Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=17890"
export JAVA_TOOL_OPTIONS="$_jto"
KART_SERVER_ENV
}

kart_start_stack() {
  echo "=== KART start-stack ==="
  echo "ZK_HOME=$ZK_HOME"
  echo "HBASE_HOME=$HBASE_HOME"
  if [[ ! -x "$ZK_HOME/bin/zkServer.sh" ]]; then
    echo "ERROR: zkServer.sh not found at $ZK_HOME/bin" >&2
    return 1
  fi
  if [[ ! -x "$HBASE_HOME/bin/start-hbase.sh" ]]; then
    echo "ERROR: start-hbase.sh not found at $HBASE_HOME/bin" >&2
    return 1
  fi
  zk_status() { "$ZK_HOME/bin/zkServer.sh" status 2>/dev/null | head -5 || true; }
  echo "-- ZooKeeper --"
  if echo "$(zk_status)" | grep -qi "Mode:"; then
    echo "ZooKeeper already running"
  else
    "$ZK_HOME/bin/zkServer.sh" start
  fi
  echo "-- HBase --"
  "$HBASE_HOME/bin/start-hbase.sh" || true
  echo "-- wait for doctor (max ~90s) --"
  kart_ensure_jar
  local ok=0 i
  for i in $(seq 1 18); do
    if java -Dkart.root="$KART_ROOT" -jar "$KART_JAR" doctor 2>/dev/null | tee /tmp/kart-doctor-boot.log | grep -q "doctor: OK"; then
      ok=1
      break
    fi
    echo "  retry $i/18…"
    sleep 5
  done
  if [[ "$ok" -eq 1 ]]; then
    echo "=== start-stack: OK ==="
    grep -E "tables=|HBase|ZooKeeper|doctor" /tmp/kart-doctor-boot.log | tail -15 || true
    local MANIFEST="$KART_ROOT/catalog/tdrive_v1_ready.manifest.json"
    if [[ -f "$MANIFEST" ]]; then
      echo "tdrive_v1_ready present — ready for chat / query-nl / query-ir / smoke"
    else
      echo "WARN: missing $MANIFEST" >&2
      echo "  run: ./scripts/kart.sh run build-snapshot --data datasets/tdrive" >&2
    fi
    return 0
  fi
  echo "=== start-stack: doctor not OK yet ===" >&2
  echo "Check ZK/HBase logs; retry: ./scripts/kart.sh doctor" >&2
  tail -30 /tmp/kart-doctor-boot.log 2>/dev/null || true
  return 1
}

kart_stop_stack() {
  echo "=== KART stop-stack ==="
  if [[ -x "$HBASE_HOME/bin/stop-hbase.sh" ]]; then
    "$HBASE_HOME/bin/stop-hbase.sh" || true
  else
    echo "WARN: stop-hbase.sh missing"
  fi
  if [[ -x "$ZK_HOME/bin/zkServer.sh" ]]; then
    "$ZK_HOME/bin/zkServer.sh" stop || true
  else
    echo "WARN: zkServer.sh missing"
  fi
  echo "=== stop-stack: done ==="
}

kart_probe_llm() {
  kart_load_llm_env
  local BASE="${LLM_BASE_URL%/}" MODEL="$LLM_MODEL" KEY="$LLM_API_KEY"
  probe_one() {
    local url="$1" with_json="$2" body tmp code
    if [[ "$with_json" == "1" ]]; then
      body=$(cat <<EOF
{"model":"$MODEL","temperature":0,"messages":[{"role":"user","content":"Return a JSON object with keys ok (boolean true) and model_echo (string repeating the model id you are)."}],"response_format":{"type":"json_object"}}
EOF
)
    else
      body=$(cat <<EOF
{"model":"$MODEL","temperature":0,"messages":[{"role":"user","content":"Reply with exactly: {\"ok\":true}"}]}
EOF
)
    fi
    tmp=$(mktemp)
    code=$(curl -sS -o "$tmp" -w "%{http_code}" \
      -X POST "$url" \
      -H "Authorization: Bearer $KEY" \
      -H "Content-Type: application/json" \
      --connect-timeout 20 \
      --max-time 90 \
      -d "$body" || echo "000")
    echo "PROBE url=$url json_mode=$with_json http=$code"
    python3 - "$tmp" <<'PY'
import json,sys
p=sys.argv[1]
raw=open(p,encoding='utf-8',errors='replace').read()
print('body_prefix=', raw[:800].replace('\n',' '))
try:
  j=json.loads(raw)
  c=j.get('choices') or []
  if c:
    msg=(c[0].get('message') or {}).get('content')
    print('assistant_content=', (msg or '')[:500])
  if 'error' in j:
    print('error=', j['error'])
except Exception as e:
  print('parse_err=', e)
PY
    rm -f "$tmp"
    [[ "$code" == "200" ]]
  }
  echo "=== OI-1 probe model=$MODEL ==="
  local CANDIDATES=("$BASE/v1/chat/completions" "$BASE/chat/completions")
  if [[ "$BASE" == */v1 ]]; then
    CANDIDATES=("$BASE/chat/completions" "${BASE%/v1}/chat/completions")
  fi
  local ok_url="" json_ok="unknown" plain_ok="unknown" url
  for url in "${CANDIDATES[@]}"; do
    echo "--- try $url with json_object ---"
    if probe_one "$url" 1; then
      ok_url="$url"; json_ok="yes"; break
    fi
    echo "json_object failed; try plain ---"
    if probe_one "$url" 0; then
      ok_url="$url"; json_ok="no"; plain_ok="yes"; break
    fi
  done
  if [[ -z "$ok_url" ]]; then
    echo "PROBE_FAILED"
    return 1
  fi
  local JAVA_BASE="${ok_url%/chat/completions}"
  echo "PROBE_OK endpoint=$ok_url java_base=$JAVA_BASE json_object=$json_ok plain=$plain_ok"
  mkdir -p "$ROOT/runs/llm-probe"
  cat > "$ROOT/runs/llm-probe/last.json" <<EOF
{"endpoint":"$ok_url","java_base":"$JAVA_BASE","model":"$MODEL","json_object":"$json_ok","provider":"PinAI"}
EOF
  echo "wrote runs/llm-probe/last.json"
}

kart_wsl_test() {
  local REPO="${KART_MAVEN_REPO}" SRC="${KART_SRC}" DST="${KART_DST}"
  bash "$KART_ROOT/scripts/sync.sh"
  bash "$DST/scripts/sync.sh" --check
  cd "$DST"
  echo "Using maven.repo.local=$REPO (cwd=$DST)"
  mvn -Dmaven.repo.local="$REPO" test -DtrimStackTrace=false 2>&1 | tee /tmp/kart-test.log
  mvn -Dmaven.repo.local="$REPO" -q package -DskipTests
  mkdir -p "$SRC/target"
  cp -f target/kart.jar "$SRC/target/kart.jar"
  echo "OK jar -> $SRC/target/kart.jar"
  grep -E 'Tests run:|BUILD SUCCESS|BUILD FAILURE' /tmp/kart-test.log | tail -20
}

kart_demo_verify() {
  local LOG=/tmp/kart-demo-verify.log
  : > "$LOG"
  local pass=0 fail=0 skip=0
  ok()   { echo "PASS  $*" | tee -a "$LOG"; pass=$((pass+1)); }
  bad()  { echo "FAIL  $*" | tee -a "$LOG"; fail=$((fail+1)); }
  skip() { echo "SKIP  $*" | tee -a "$LOG"; skip=$((skip+1)); }
  echo "======== KART demo-verify (Live LLM → HBase T-Drive) ========" | tee -a "$LOG"
  echo "root=$ROOT time=$(date)" | tee -a "$LOG"
  kart_ensure_jar
  if [[ -f "$KART_JAR" ]]; then ok "jar present"; else bad "jar missing"; fi
  local HBASE_OK=0
  if java -Dkart.root="$ROOT" -jar "$KART_JAR" doctor > /tmp/kart-dv-doctor.log 2>&1; then
    if grep -q "doctor: OK" /tmp/kart-dv-doctor.log; then
      ok "doctor"; HBASE_OK=1
    else
      bad "doctor (missing OK — run ./scripts/kart.sh up)"
    fi
  else
    bad "doctor (run ./scripts/kart.sh up)"
  fi
  if [[ "$HBASE_OK" -eq 1 ]]; then
    if [[ -f "$ROOT/catalog/tdrive_v1_ready.manifest.json" ]]; then
      ok "catalog tdrive_v1_ready"
    else
      bad "catalog missing tdrive_v1_ready — run build-snapshot"
      HBASE_OK=0
    fi
  fi
  # Formal path uses datasets/tdrive-query + comparative/ablation/parameter.sh.
  skip "tdrive smoke (use ./scripts/comparative.sh)"
  kart_load_llm_env || true
  if [[ -n "${LLM_API_KEY:-}" && "$HBASE_OK" -eq 1 ]]; then
    if kart_probe_llm > /tmp/kart-dv-probe.out 2>&1; then
      if grep -q 'PROBE_OK\|PROBE_LLM_OK' /tmp/kart-dv-probe.out; then
        ok "live LLM probe"
      else
        bad "live probe"
      fi
    else
      bad "live probe failed"
    fi
    if kart_chat_easy > /tmp/kart-dv-chat-easy.out 2>&1; then
      if grep -q 'CHAT_EASY_ACCEPTANCE PASS' /tmp/kart-dv-chat-easy.out; then
        ok "chat-easy-acceptance"
      else
        bad "chat-easy-acceptance"
      fi
    else
      if grep -q 'CHAT_EASY_ACCEPTANCE PASS' /tmp/kart-dv-chat-easy.out; then
        ok "chat-easy-acceptance"
      else
        bad "chat-easy-acceptance"
      fi
    fi
  else
    skip "live LLM"
  fi
  echo "" | tee -a "$LOG"
  echo "SUMMARY pass=$pass fail=$fail skip=$skip log=$LOG" | tee -a "$LOG"
  if [[ "$fail" -gt 0 ]]; then
    echo "DEMO_VERIFY_FAIL"
    return 1
  fi
  echo "DEMO_VERIFY_PASS"
  return 0
}

kart_demo_failures() {
  echo "=== demo-failures: unit evidence (CoordinatorFailureTest + dialog COUNT reject) ==="
  mvn -Dmaven.repo.local="${KART_MAVEN_REPO}" -q -Dtest=CoordinatorFailureTest,QueryNlAcceptanceTest test
  echo ""
  echo "Expected status mapping (from tests / dialog):"
  echo "  RESOURCE_EXHAUSTED   — max_candidate_chunks=1 | soft_memory_bytes tiny"
  echo "  DATA_INTEGRITY_ERROR — missing raw chunk on FETCH/BATCH_GET"
  echo "  UNSUPPORTED_QUERY    — COUNT / aggregation rejected in Dialog/DraftIrParser"
  echo "  parse/LLM failure    — INVALID_IR paths in QueryNlAcceptanceTest (ScriptedLlmClient)"
  if [[ "${KART_LIVE:-0}" == "1" ]]; then
    echo ""
    echo "=== demo-failures: live UNSUPPORTED (COUNT) via chat one-shot if available ==="
    echo "COUNT how many trajectories intersect beijing_core" | kart_java chat --once 2>&1 | tail -20 || true
  fi
  echo ""
  echo "demo-failures OK"
}

kart_hbase_evidence() {
  local OUT_DIR="$ROOT/experiments/results"
  mkdir -p "$OUT_DIR"
  local OUT="$OUT_DIR/hbase_version_evidence.txt"
  local MANIFEST="${KART_EXPERIMENT_MANIFEST}"
  {
    echo "=== KART HBase version evidence ==="
    echo "utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "git_commit=$(cd "$ROOT" && git rev-parse HEAD 2>/dev/null || echo n/a)"
    echo "KART_ROOT=$ROOT"
    echo "KART_DST=$KART_DST"
    echo "HBASE_HOME=${HBASE_HOME:-}"
    echo "experiment_manifest=$MANIFEST"
    echo
    echo "--- doctor ---"
    kart_java doctor || true
    echo
    echo "--- HBASE_CLASSPATH hint (TMan-spatial may pin cluster status to 2.1.2) ---"
    if [[ -n "${HBASE_HOME:-}" && -d "$HBASE_HOME" ]]; then
      ls -1 "$HBASE_HOME"/lib 2>/dev/null | grep -iE 'tman|hbase-client|hbase-common' | head -40 || true
    fi
    echo
    echo "--- experiment-scoped tables (prefix from manifest naming) ---"
    echo "Only tables belonging to manifest=$MANIFEST are in experiment scope."
    echo "Other tables on the cluster must be ignored for latency/correctness claims."
  } | tee "$OUT"
  if [[ "$ROOT" == "$KART_DST" && -d "$KART_SRC" ]]; then
    mkdir -p "$KART_SRC/experiments/results"
    cp -f "$OUT" "$KART_SRC/experiments/results/"
  fi
  echo "wrote $OUT"
}

kart_truststore() {
  local SYS_CACERTS="$JAVA_HOME/jre/lib/security/cacerts"
  if [[ ! -f "$SYS_CACERTS" ]]; then
    SYS_CACERTS="$JAVA_HOME/lib/security/cacerts"
  fi
  local OUT_DIR="${HOME}/.kart" OUT_STORE="$OUT_DIR/truststore"
  local ALIAS="trustasia-dv-tls-rsa-ca-2025"
  mkdir -p "$OUT_DIR"
  if [[ ! -f "$OUT_STORE" ]]; then
    cp "$SYS_CACERTS" "$OUT_STORE"
  fi
  if keytool -list -keystore "$OUT_STORE" -storepass changeit -alias "$ALIAS" >/dev/null 2>&1; then
    echo "CA already installed in $OUT_STORE ($ALIAS)"
  else
    local TMP
    TMP=$(mktemp -d)
    trap 'rm -rf "$TMP"' RETURN
    echo | openssl s_client -showcerts -connect api.deepseek.com:443 -servername api.deepseek.com 2>/dev/null \
      | awk '/BEGIN CERTIFICATE/{i++} {print > "'"$TMP"'/cert"i".pem"}'
    local INTER="$TMP/cert2.pem"
    [[ -s "$INTER" ]] || INTER="$TMP/cert1.pem"
    keytool -importcert -noprompt -alias "$ALIAS" -file "$INTER" \
      -keystore "$OUT_STORE" -storepass changeit
    echo "installed $ALIAS into $OUT_STORE"
  fi
  echo "export JAVA_TOOL_OPTIONS=\"-Djavax.net.ssl.trustStore=$OUT_STORE -Djavax.net.ssl.trustStorePassword=changeit\${JAVA_TOOL_OPTIONS:+ \$JAVA_TOOL_OPTIONS}\""
}

kart_chat_easy() {
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-8-openjdk-amd64}"
export PATH="$JAVA_HOME/bin:$PATH"
JAR="$ROOT/target/kart.jar"
OUT="$ROOT/runs/chat-easy"
mkdir -p "$OUT"
LOG="$OUT/acceptance.log"
: > "$LOG"

run_nl() {
  local name="$1"
  local utterance="$2"
  local answers="${3:-}"
  local tmo="${4:-180}"
  echo "===== $name =====" | tee -a "$LOG"
  echo "utterance=$utterance" | tee -a "$LOG"
  local cmd=(java -Dkart.root="$ROOT" -jar "$JAR" query-nl --runs "$OUT/runs-$name" --manifest tdrive_v1_ready --dataset tdrive_v1)
  if [[ -n "$answers" ]]; then
    cmd+=(--answers "$answers")
  fi
  cmd+=("$utterance")
  set +e
  timeout "$tmo" "${cmd[@]}" > "$OUT/$name.out" 2>&1
  local code=$?
  set -e
  if [[ $code -eq 124 ]]; then
    echo "TIMEOUT $name after ${tmo}s" | tee -a "$LOG"
    echo 124 > "$OUT/$name.exit"
  else
    echo "$code" > "$OUT/$name.exit"
  fi
  echo "exit=$code name=$name" | tee -a "$LOG"
  strings "$OUT/$name.out" | grep -E 'UNSUPPORTED|I need|predicate=|trajectory_ids=|status=|Confirm|BIND_ERROR|FAILED' | tail -20 | tee -a "$LOG" || true
}

echo "=== probe ===" | tee -a "$LOG"
java -Dkart.root="$ROOT" -jar "$JAR" probe-llm > "$OUT/probe-llm.out" 2>&1
grep -E 'PROBE' "$OUT/probe-llm.out" | tee -a "$LOG"

printf 'y\n' > "$OUT/answers-y.txt"
printf 'DTW\ny\n' > "$OUT/answers-metric-dtw-y.txt"
printf 'I do not know\ntdrive_smoke_anchor\nn\n' > "$OUT/answers-r5.txt"

ensure_hbase() {
  if ss -lntp 2>/dev/null | grep -q ':16020'; then
    return 0
  fi
  echo "HBase RS :16020 not listening — restarting stack" | tee -a "$LOG"
  bash "$ROOT/scripts/kart.sh" down || true
  sleep 3
  bash "$ROOT/scripts/kart.sh" up
}

# Early rejects (no HBase exec) — short timeout
run_nl r1_count "COUNT how many trajectories intersect beijing_core between 2008-02-03T21:20:00+08:00 and 2008-02-03T21:30:00+08:00" "" 60
run_nl r2_continuous "Find trajectories whose continuous path segment crosses the ring road (not just GPS samples)" "" 60
run_nl r3_edit "Top-3 trajectories most similar to 8857-8857_14 using EDIT_DISTANCE" "" 90
run_nl r4_cfrechet "Use continuous Fréchet distance to find neighbors of 8857-8857_14" "" 60
run_nl r5_mars "Trajectories through 火星广场 yesterday" "$OUT/answers-r5.txt" 180

# Executing cases — ensure RS is up (LLM-long connects can leave stale RS)
ensure_hbase
run_nl v1_full "Find trajectories of taxi 8857 between 2008-02-03T21:20:00+08:00 and 2008-02-03T21:30:00+08:00" "$OUT/answers-y.txt" 240
ensure_hbase
run_nl v2_anchor "Show trajectories of taxi 8857 that intersect tdrive_smoke_anchor between 2008-02-03T20:50:00+08:00 and 2008-02-03T21:50:00+08:00" "$OUT/answers-y.txt" 240
ensure_hbase
run_nl v6_clarify_metric "Find k=3 trajectories most similar to 8857-8857_14 intersecting tdrive_topk_box between 2008-02-03T20:50:00+08:00 and 2008-02-03T21:20:00+08:00" "$OUT/answers-metric-dtw-y.txt" 300

python3 - "$OUT" <<'PY'
import json, pathlib, re, sys
root = pathlib.Path(sys.argv[1])

def text(name):
    p = root / f"{name}.out"
    if not p.exists():
        return ""
    # strip NULs from StatusLog binary noise
    return p.read_bytes().decode("utf-8", "replace").replace("\x00", "")

def exit_code(name):
    p = root / f"{name}.exit"
    return int(p.read_text().strip()) if p.exists() else -1

def has_ids(t, expected):
    m = re.search(r"trajectory_ids=\[([^\]]*)\]", t)
    if not m:
        return False
    got = [x.strip() for x in m.group(1).split(",") if x.strip()]
    return got == expected

def count_ids(t):
    m = re.search(r"trajectory_ids=\[([^\]]*)\]", t)
    if not m:
        return 0
    return len([x for x in m.group(1).split(",") if x.strip()])

cases = []
t,c = text("v1_full"), exit_code("v1_full")
cases.append(("v1_full", c==0 and "predicate=vehicle_id EQ 8857" in t and has_ids(t,["8857-8857_14"]), "unique id + predicate"))
t,c = text("v2_anchor"), exit_code("v2_anchor")
cases.append(("v2_anchor", c==0 and has_ids(t,["8857-8857_14"]) and "tdrive_smoke_anchor" in t, "anchor unique"))
t,c = text("v6_clarify_metric"), exit_code("v6_clarify_metric")
clar = "I need a bit more information" in t
cases.append(("v6_clarify_metric", clar and c==0 and has_ids(t,["2406-2406_9","4920-4920_24"]), "clarify metric + topk"))
t,c = text("r1_count"), exit_code("r1_count")
cases.append(("r1_count", c==3 and "UNSUPPORTED_QUERY" in t and "COUNT" in t, "COUNT early"))
t,c = text("r2_continuous"), exit_code("r2_continuous")
cases.append(("r2_continuous", c==3 and "UNSUPPORTED_QUERY" in t and "continuous" in t.lower(), "continuous early"))
t,c = text("r3_edit"), exit_code("r3_edit")
cases.append(("r3_edit", (c==3 or "UNSUPPORTED" in t) and "EDIT_DISTANCE" in t, "EDIT_DISTANCE"))
t,c = text("r4_cfrechet"), exit_code("r4_cfrechet")
cases.append(("r4_cfrechet", c==3 and "UNSUPPORTED_QUERY" in t, "cFrechet early"))
t,c = text("r5_mars"), exit_code("r5_mars")
cases.append(("r5_mars", ("I need a bit more information" in t or "NEED_CLARIFICATION" in t) and count_ids(t)<1000, "no huge dump"))

probe = text("probe-llm")
probe_ok = "PROBE_LLM_OK" in probe or "PROBE_OK" in probe
all_ok = probe_ok and all(o for _,o,_ in cases)
print("PROBE_OK", probe_ok)
for n,o,note in cases:
    print(("PASS" if o else "FAIL"), n, "-", note, "exit", exit_code(n))
print("CHAT_EASY_ACCEPTANCE", "PASS" if all_ok else "FAIL")
(root/"summary.json").write_text(json.dumps({"pass":all_ok,"cases":[{"name":n,"ok":o,"note":note} for n,o,note in cases]}, indent=2))
raise SystemExit(0 if all_ok else 1)
PY
}

kart_chat_handbook() {
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-8-openjdk-amd64}"
export PATH="$JAVA_HOME/bin:$PATH"
JAR="$ROOT/target/kart.jar"
OUT="$ROOT/runs/chat-handbook"
mkdir -p "$OUT"
LOG="$OUT/run.log"
: > "$LOG"

ensure_hbase() {
  if ss -lntp 2>/dev/null | grep -q ':16020' \
     && java -Dkart.root="$ROOT" -jar "$JAR" doctor > /tmp/hb-doc.log 2>&1 \
     && grep -q 'doctor: OK' /tmp/hb-doc.log; then
    return 0
  fi
  echo "HBase not healthy — restarting" | tee -a "$LOG"
  bash "$ROOT/scripts/kart.sh" down || true
  sleep 3
  bash "$ROOT/scripts/kart.sh" up
}

run_nl() {
  local name="$1"
  local utterance="$2"
  local answers="${3:-}"
  local tmo="${4:-240}"
  echo "===== $name =====" | tee -a "$LOG"
  echo "utterance=$utterance" | tee -a "$LOG"
  local cmd=(java -Dkart.root="$ROOT" -jar "$JAR" query-nl
    --runs "$OUT/runs-$name" --manifest tdrive_v1_ready --dataset tdrive_v1)
  if [[ -n "$answers" ]]; then
    cmd+=(--answers "$answers")
  fi
  cmd+=("$utterance")
  set +e
  timeout "$tmo" "${cmd[@]}" > "$OUT/$name.out" 2>&1
  local code=$?
  set -e
  if [[ $code -eq 124 ]]; then
    echo "TIMEOUT $name" | tee -a "$LOG"
    echo 124 > "$OUT/$name.exit"
  else
    echo "$code" > "$OUT/$name.exit"
  fi
  echo "exit=$code name=$name" | tee -a "$LOG"
  strings "$OUT/$name.out" | grep -E 'UNSUPPORTED|I need|predicate=|region_name=|trajectory_ids=|status=|Confirm|BIND_ERROR|FAILED|similarity=' | tail -25 | tee -a "$LOG" || true
}

printf 'y\n' > "$OUT/y.txt"
printf 'DTW\ny\n' > "$OUT/dtw_y.txt"
printf 'FRECHET\ny\n' > "$OUT/frechet_y.txt"
printf 'I do not know a registered region\nn\n' > "$OUT/r5.txt"
# H3: point LLM at registered box covering h_s_small
printf 'tdrive_topk_box\ny\n' > "$OUT/topk_box_y.txt"

ensure_hbase

echo "=== probe ===" | tee -a "$LOG"
java -Dkart.root="$ROOT" -jar "$JAR" probe-llm > "$OUT/probe.out" 2>&1
grep PROBE "$OUT/probe.out" | tee -a "$LOG"

# -------- EASY --------
run_nl e_v1_full "Find trajectories of taxi 8857 between 2008-02-03T21:20:00+08:00 and 2008-02-03T21:30:00+08:00" "$OUT/y.txt" 240
ensure_hbase
run_nl e_v1_short "List all trajectories belonging to taxi 8857" "$OUT/y.txt" 240
ensure_hbase
run_nl e_v2 "Show trajectories of taxi 8857 that intersect tdrive_smoke_anchor between 2008-02-03T20:50:00+08:00 and 2008-02-03T21:50:00+08:00" "$OUT/y.txt" 240
ensure_hbase
run_nl e_v3 "List trajectory ids intersecting tdrive_topk_box between 2008-02-03T21:20:00+08:00 and 2008-02-03T21:30:00+08:00" "$OUT/y.txt" 240
ensure_hbase
run_nl e_v4 "Among trajectories intersecting tdrive_topk_box between 2008-02-03T20:50:00+08:00 and 2008-02-03T21:20:00+08:00, find 3 most similar to 8857-8857_14 using DTW" "$OUT/y.txt" 300
ensure_hbase
run_nl e_v5 "Top-3 DTW neighbors of 8857-8857_14 intersecting tdrive_smoke_anchor between 2008-02-03T21:20:00+08:00 and 2008-02-03T21:50:00+08:00" "$OUT/y.txt" 300
ensure_hbase
run_nl e_v6 "Find k=3 trajectories most similar to 8857-8857_14 intersecting tdrive_topk_box between 2008-02-03T20:50:00+08:00 and 2008-02-03T21:20:00+08:00" "$OUT/dtw_y.txt" 300

run_nl e_r1 "COUNT how many trajectories intersect beijing_core between 2008-02-03T21:20:00+08:00 and 2008-02-03T21:30:00+08:00" "" 60
run_nl e_r2 "Find trajectories whose continuous path segment crosses the ring road (not just GPS samples)" "" 60
run_nl e_r3 "Top-3 trajectories most similar to 8857-8857_14 using EDIT_DISTANCE" "" 60
run_nl e_r4 "Use continuous Fréchet distance to find neighbors of 8857-8857_14" "" 60
run_nl e_r5 "Trajectories through 火星广场 yesterday" "$OUT/r5.txt" 180

# -------- HARD (chat-runnable with registered regions / explicit times) --------
ensure_hbase
run_nl h_h1 "List all trajectories belonging to taxi 8857" "$OUT/y.txt" 240
ensure_hbase
run_nl h_h2 "Find trajectories of taxi 8857 between 2008-02-02T13:30:00+08:00 and 2008-02-03T13:30:00+08:00" "$OUT/y.txt" 300
ensure_hbase
run_nl h_h3 "Which trajectories of taxi 8857 intersect tdrive_topk_box" "$OUT/y.txt" 300
ensure_hbase
run_nl h_h8 "Among trajectories intersecting tdrive_topk_wide between 2008-02-03T20:50:00+08:00 and 2008-02-03T21:30:00+08:00, find 5 most similar to 8857-8857_14 using DTW" "$OUT/y.txt" 360
ensure_hbase
run_nl h_h9 "Among trajectories intersecting tdrive_topk_wide between 2008-02-03T20:50:00+08:00 and 2008-02-03T21:30:00+08:00, find 5 most similar to 8857-8857_14 using FRECHET" "$OUT/y.txt" 360
ensure_hbase
run_nl h_h11 "Find k=5 neighbors of 8857-8857_14 intersecting tdrive_topk_wide between 2008-02-03T20:50:00+08:00 and 2008-02-03T21:30:00+08:00" "$OUT/dtw_y.txt" 360

run_nl h_r1 "Find pairs of taxis that were within 50m of each other in beijing_core tonight" "" 90
run_nl h_r3 "Which trajectories continuously crossed the 2nd Ring as a polyline, not just GPS samples inside a box" "" 60
run_nl h_r4 "Top-50 trajectories most similar to 8857-8857_14 using LCSS" "" 60
run_nl h_r5 "Dump all trajectories through 火星广场 yesterday" "$OUT/r5.txt" 180

python3 - "$OUT" "$ROOT" <<'PY'
import json, pathlib, re, sys, hashlib
root = pathlib.Path(sys.argv[1])
proj = pathlib.Path(sys.argv[2])
ev = json.load(open(proj / "docs/hard_query_example/evidence.json"))
by = {c["query_id"]: c for c in ev["cases"]}

def text(name):
    p = root / f"{name}.out"
    if not p.exists():
        return ""
    return p.read_bytes().decode("utf-8", "replace").replace("\x00", "")

def exit_code(name):
    p = root / f"{name}.exit"
    return int(p.read_text().strip()) if p.exists() else -1

def parse_ids(t):
    m = re.search(r"trajectory_ids=\[([^\]]*)\]", t)
    if not m:
        return None
    return [x.strip() for x in m.group(1).split(",") if x.strip()]

def contains_8857_14(ids):
    return ids is not None and "8857-8857_14" in ids

rows = []

def check(name, kind, expect=None, pred=None, note=""):
    t = text(name)
    c = exit_code(name)
    ids = parse_ids(t)
    ok = False
    detail = ""
    if kind == "ids_exact":
        ok = c == 0 and ids == expect
        detail = f"got={ids} expect={expect}"
    elif kind == "ids_contains":
        ok = c == 0 and ids is not None and all(x in ids for x in expect)
        detail = f"n={len(ids) if ids else None} contains={expect}"
    elif kind == "ids_n":
        ok = c == 0 and ids is not None and len(ids) == expect
        detail = f"n={len(ids) if ids else None} expect_n={expect}"
    elif kind == "unsupported":
        ok = c == 3 and "UNSUPPORTED_QUERY" in t
        detail = "unsupported"
    elif kind == "clarify_no_huge":
        clar = "I need a bit more information" in t or "NEED_CLARIFICATION" in t
        n = len(ids) if ids else 0
        ok = clar and n < 1000
        detail = f"clar={clar} n={n}"
    elif kind == "clarify_metric":
        clar = "I need a bit more information" in t and ("metric" in t.lower() or "DTW" in t)
        ok = clar and c == 0 and ids == expect
        detail = f"clar={clar} got={ids}"
    elif kind == "custom":
        ok = pred(t, c, ids)
        detail = note
    rows.append({"name": name, "ok": ok, "exit": c, "ids": ids, "detail": detail, "note": note})
    print(("PASS" if ok else "FAIL"), name, "-", note or detail)

# Easy expected
H1 = by["h_eq_1"]["ids"]
H2 = by["h_t_large"]["ids"]
H3 = by["h_s_small"]["ids"]
V4 = ["2406-2406_9", "4920-4920_24"]
V5 = ["5340-5340_15", "2376-2376_10", "289-289_12"]
H8 = by["topk_st_2"]["ids"]
H9 = by["topk_st_2_frechet"]["ids"]

check("e_v1_full", "ids_exact", ["8857-8857_14"], note="easy V1 unique")
check("e_v1_short", "ids_exact", H1, note="easy V1 short = H1 24")
check("e_v2", "ids_exact", ["8857-8857_14"], note="easy V2 anchor")
check("e_v3", "ids_contains", ["8857-8857_14"], note="easy V3 contains anchor tid")
check("e_v4", "ids_exact", V4, note="easy V4 topk_st_1 order")
check("e_v5", "ids_exact", V5, note="easy V5 topk_st_3 order")
check("e_v6", "clarify_metric", V4, note="easy V6 clarify then V4")
check("e_r1", "unsupported", note="easy R1 COUNT")
check("e_r2", "unsupported", note="easy R2 continuous")
check("e_r3", "unsupported", note="easy R3 EDIT_DISTANCE")
check("e_r4", "unsupported", note="easy R4 continuous Frechet")
check("e_r5", "clarify_no_huge", note="easy R5 unknown place")

check("h_h1", "ids_exact", H1, note="hard H1 24")
check("h_h2", "ids_exact", H2, note="hard H2 7")
check("h_h3", "ids_exact", H3, note="hard H3 13 via tdrive_topk_box")
check("h_h8", "ids_exact", H8, note="hard H8 DTW k=5")
check("h_h9", "ids_exact", H9, note="hard H9 FRECHET k=5")
check("h_h11", "clarify_metric", H8, note="hard H11 clarify DTW -> H8")
check("h_r1", "unsupported", note="hard R1 pairs (may FAIL if LLM not early-rejected)")
check("h_r3", "unsupported", note="hard R3 continuous polyline")
check("h_r4", "unsupported", note="hard R4 LCSS")
check("h_r5", "clarify_no_huge", note="hard R5 mars dump")

# soft note for h_r1 if not early-rejected — still report
all_ok = all(r["ok"] for r in rows)
print("HANDBOOK_CHAT", "PASS" if all_ok else "FAIL")
(root / "summary.json").write_text(json.dumps({"pass": all_ok, "cases": rows}, indent=2, ensure_ascii=False), encoding="utf-8")
raise SystemExit(0 if all_ok else 1)
PY
}

if [[ "$KART_AS_LIB" -eq 1 ]]; then
  return 0 2>/dev/null || true
fi

cmd="${1:-help}"
shift || true

case "$cmd" in
  up|start) kart_start_stack ;;
  down|stop) kart_stop_stack ;;
  chat|repl)
    kart_load_llm_env
    export KART_STATUS="${KART_STATUS:-true}"
    kart_java chat --manifest "${KART_EXPERIMENT_MANIFEST:-tdrive_v1_ready}" --dataset tdrive_v1 "$@"
    ;;
  check|demo-verify) kart_demo_verify "$@" ;;
  doctor) kart_java doctor "$@" ;;
  probe) kart_probe_llm "$@" ;;
  smoke)
    echo "smoke harness removed; use: ./scripts/comparative.sh" >&2
    exit 2
    ;;
  sync) bash "$KART_ROOT/scripts/sync.sh" "$@" ;;
  sync-check) bash "$KART_ROOT/scripts/sync.sh" --check "$@" ;;
  fit-cost-pack|gen-cost-ir)
    echo "cost calib pack scripts removed; frozen coeffs live in src/main/resources/config/kart.yaml" >&2
    exit 2
    ;;
  hbase-evidence) kart_hbase_evidence "$@" ;;
  demo-failures) kart_demo_failures "$@" ;;
  test) kart_wsl_test "$@" ;;
  rebuild) kart_rebuild_jar "$@" ;;
  chat-easy)
    kart_load_llm_env
    kart_chat_easy "$@"
    ;;
  chat-handbook)
    kart_load_llm_env
    kart_chat_handbook "$@"
    ;;
  truststore) kart_truststore "$@" ;;
  tunnel) kart_llm_tunnel "$@" ;;
  drop-cache-install) kart_drop_cache_install "$@" ;;
  framework-check) kart_framework_check "$@" ;;
  server-env) kart_print_server_env "$@" ;;
  run)
    kart_load_llm_env
    kart_java "$@"
    ;;
  help|-h|--help)
    cat <<'EOF'
KART — run all of this in WSL (not PowerShell).

Daily:
  ./scripts/kart.sh up
  ./scripts/kart.sh run build-snapshot --data datasets/tdrive   # if catalog missing
  ./scripts/kart.sh chat          # Live LLM → HBase
  ./scripts/kart.sh down

Also: doctor | probe | check | rebuild | test | run <cli-args>
      sync | sync-check | chat-easy | chat-handbook | demo-failures | hbase-evidence | truststore
Formal experiments:
  ./scripts/comparative.sh [--date YYYYMMDD]   # → comparativeE1/E3-DATE.md
  ./scripts/ablation.sh    [--date YYYYMMDD]
  ./scripts/parameter.sh   [--date YYYYMMDD]
Also: sync | sync-check | drop-cache-install | tunnel | framework-check | server-env
EOF
    ;;
  *)
    echo "Unknown: $cmd — try ./scripts/kart.sh help" >&2
    exit 2
    ;;
esac
