#!/usr/bin/env bash
# Sync Windows LLM_KV tree -> WSL ext4 workspace (/home/jaytang/projects/llm-kv).
# Does NOT touch TMan-spatial or ~/envs. Safe while HBase/ZK are running.
set -euo pipefail

SRC="${KART_SRC:-/mnt/f/Projects/LLM_KV}"
DST="${KART_DST:-/home/jaytang/projects/llm-kv}"
COMPAT="${KART_COMPAT:-/home/jaytang/build/LLM_KV}"

if [[ ! -d "$SRC" ]]; then
  echo "ERROR: source missing: $SRC" >&2
  exit 1
fi
if [[ ! -d "$DST" ]]; then
  echo "ERROR: workspace missing: $DST — create /home/jaytang/projects/llm-kv first" >&2
  exit 1
fi

# Keep compat symlink healthy
if [[ ! -e "$COMPAT" ]]; then
  ln -sfn "$DST" "$COMPAT"
elif [[ ! -L "$COMPAT" ]]; then
  echo "WARN: $COMPAT exists and is not a symlink — leave untouched" >&2
fi

rsync -a \
  --exclude target \
  --exclude datasets \
  --exclude .git \
  --exclude '.jqwik-database' \
  --exclude 'F:\maven-repository' \
  "$SRC/" "$DST/"

# Copy jar back path is handled by rebuild-jar; here only sync sources.
echo "synced: $SRC -> $DST"
