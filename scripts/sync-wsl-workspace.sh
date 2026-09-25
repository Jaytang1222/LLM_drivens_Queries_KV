#!/usr/bin/env bash
# Sync Windows LLM_KV → WSL ext4 workspace, or verify sync (--check).
# Default: KART_SRC=/mnt/f/Projects/LLM_KV → KART_DST=/home/jaytang/projects/llm-kv
# Usage: sync-wsl-workspace.sh [--check]
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck disable=SC1091
source "$ROOT/scripts/kart-env.sh"

SRC="${KART_SRC}"
DST="${KART_DST}"
COMPAT="${KART_COMPAT}"

kart_sync_check() {
  local FAIL=0
  ok() { echo "OK: $*"; }
  bad() { echo "FAIL: $*"; FAIL=$((FAIL + 1)); }

  if ! test -d "$SRC"; then bad "SRC missing: $SRC"; fi
  if ! test -d "$DST"; then bad "DST missing: $DST"; fi

  if test -L "$KART_COMPAT"; then
    local target
    target="$(readlink -f "$KART_COMPAT" 2>/dev/null || readlink "$KART_COMPAT")"
    if [[ "$target" == "$(readlink -f "$DST" 2>/dev/null || echo "$DST")" ]]; then
      ok "compat symlink $KART_COMPAT → $DST"
    else
      bad "compat symlink $KART_COMPAT → $target (expected $DST)"
    fi
  else
    bad "compat path is not a symlink: $KART_COMPAT"
  fi

  local latest_src="" latest_dst=""
  if test -f "$SRC/KART_SYNC_LATEST.txt"; then
    latest_src="$(tr -d '\r\n' < "$SRC/KART_SYNC_LATEST.txt")"
  fi
  if test -f "$DST/KART_SYNC_LATEST.txt"; then
    latest_dst="$(tr -d '\r\n' < "$DST/KART_SYNC_LATEST.txt")"
  fi

  if [[ -z "$latest_src" || -z "$latest_dst" ]]; then
    bad "missing KART_SYNC_LATEST.txt (run ./scripts/kart.sh sync)"
  elif [[ "$latest_src" != "$latest_dst" ]]; then
    bad "stamp mismatch SRC=$latest_src DST=$latest_dst"
  else
    ok "sync stamp=$latest_src"
  fi

  if [[ -n "$latest_src" ]] && test -f "$SRC/$latest_src" && test -f "$DST/$latest_src"; then
    local h1 h2
    h1="$(sha256sum "$SRC/$latest_src" | awk '{print $1}')"
    h2="$(sha256sum "$DST/$latest_src" | awk '{print $1}')"
    if [[ "$h1" == "$h2" ]]; then
      ok "stamp file hash match"
    else
      bad "stamp file hash mismatch"
    fi
  fi

  local KEYS=(
    "pom.xml"
    "config/planner.yaml"
    "scripts/kart-env.sh"
    "scripts/sync-wsl-workspace.sh"
    "scripts/kart.sh"
    "src/main/java/kart/cost/FeedbackCalibrator.java"
  )
  local rel h1 h2
  for rel in "${KEYS[@]}"; do
    if ! test -f "$SRC/$rel"; then
      bad "missing on SRC: $rel"
      continue
    fi
    if ! test -f "$DST/$rel"; then
      bad "missing on DST: $rel"
      continue
    fi
    h1="$(sha256sum "$SRC/$rel" | awk '{print $1}')"
    h2="$(sha256sum "$DST/$rel" | awk '{print $1}')"
    if [[ "$h1" == "$h2" ]]; then
      ok "hash $rel"
    else
      bad "hash mismatch $rel (re-run ./scripts/kart.sh sync)"
    fi
  done

  local git_src
  git_src="$(cd "$SRC" && git rev-parse --short HEAD 2>/dev/null || echo n/a)"
  echo "git_src_short=$git_src"
  echo "KART_SRC=$SRC"
  echo "KART_DST=$DST"
  echo "KART_EXPERIMENT_MANIFEST=$KART_EXPERIMENT_MANIFEST"

  if [[ "$FAIL" -gt 0 ]]; then
    echo "=== sync-check: FAILED ($FAIL) ==="
    return 1
  fi
  echo "=== sync-check: OK ==="
  return 0
}

if [[ "${1:-}" == "--check" || "${1:-}" == "check" ]]; then
  kart_sync_check
  exit $?
fi

if ! test -d "$SRC"; then
  echo "ERROR: source missing: $SRC" >&2
  exit 1
fi
if ! test -d "$DST"; then
  echo "ERROR: workspace missing: $DST — create the WSL workspace first" >&2
  exit 1
fi

kart_ensure_compat_symlink

STAMP_DIR="$SRC"
STAMP_PREFIX="KART_SYNC_"
TS="$(date -u +%Y%m%d_%H%M%S)Z"
HOST="$(hostname 2>/dev/null || echo unknown)"
NOTE="${KART_SYNC_NOTE:-manual}"
find "$STAMP_DIR" -maxdepth 1 -type f -name "${STAMP_PREFIX}*" -delete 2>/dev/null || true
STAMP_NAME="${STAMP_PREFIX}${TS}"
STAMP_PATH="$STAMP_DIR/$STAMP_NAME"
{
  echo "kart_sync_id=${TS}"
  echo "utc=${TS}"
  echo "host=${HOST}"
  echo "note=${NOTE}"
  echo "src=${SRC}"
  echo "dst=${DST}"
  echo "compat=${COMPAT}"
  echo "git_head=$(cd "$SRC" && git rev-parse HEAD 2>/dev/null || echo n/a)"
  echo "git_head_short=$(cd "$SRC" && git rev-parse --short HEAD 2>/dev/null || echo n/a)"
} > "$STAMP_PATH"
printf '%s\n' "$STAMP_NAME" > "$STAMP_DIR/KART_SYNC_LATEST.txt"

rsync -a --delete \
  --exclude target \
  --exclude datasets \
  --exclude catalog \
  --exclude runs \
  --exclude .git \
  --exclude '.jqwik-database' \
  --exclude 'F:\maven-repository' \
  --exclude .env \
  "$SRC/" "$DST/"

cp -f "$STAMP_PATH" "$DST/$STAMP_NAME"
cp -f "$STAMP_DIR/KART_SYNC_LATEST.txt" "$DST/KART_SYNC_LATEST.txt"

echo "synced: $SRC -> $DST"
echo "sync_stamp_file: $STAMP_NAME"
echo "check: ./scripts/kart.sh sync-check"
