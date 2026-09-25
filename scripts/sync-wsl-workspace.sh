#!/usr/bin/env bash
# Sync Windows LLM_KV tree -> canonical WSL ext4 workspace.
# Default: KART_SRC=/mnt/f/Projects/LLM_KV → KART_DST=/home/jaytang/projects/llm-kv
# Does NOT touch TMan-spatial or ~/envs. Safe while HBase/ZK are running.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck disable=SC1091
source "$ROOT/scripts/kart-env.sh"

SRC="${KART_SRC}"
DST="${KART_DST}"
COMPAT="${KART_COMPAT}"

if [[ ! -d "$SRC" ]]; then
  echo "ERROR: source missing: $SRC" >&2
  exit 1
fi
if [[ ! -d "$DST" ]]; then
  echo "ERROR: workspace missing: $DST — create /home/jaytang/projects/llm-kv first" >&2
  exit 1
fi

kart_ensure_compat_symlink

# Stamp written into SRC before rsync so both trees share the same marker file.
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
echo "check: bash $DST/scripts/check-wsl-sync.sh"
