#!/usr/bin/env bash
# Verify Windows SRC and WSL DST share the same sync stamp + key file hashes.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck disable=SC1091
source "$ROOT/scripts/kart-env.sh"

SRC="${KART_SRC}"
DST="${KART_DST}"
FAIL=0

ok() { echo "OK: $*"; }
bad() { echo "FAIL: $*"; FAIL=$((FAIL + 1)); }

if [[ ! -d "$SRC" ]]; then bad "SRC missing: $SRC"; fi
if [[ ! -d "$DST" ]]; then bad "DST missing: $DST"; fi

if [[ -L "$KART_COMPAT" ]]; then
  target="$(readlink -f "$KART_COMPAT" 2>/dev/null || readlink "$KART_COMPAT")"
  if [[ "$target" == "$(readlink -f "$DST" 2>/dev/null || echo "$DST")" ]]; then
    ok "compat symlink $KART_COMPAT → $DST"
  else
    bad "compat symlink $KART_COMPAT → $target (expected $DST)"
  fi
else
  bad "compat path is not a symlink: $KART_COMPAT"
fi

latest_src=""
latest_dst=""
if [[ -f "$SRC/KART_SYNC_LATEST.txt" ]]; then
  latest_src="$(tr -d '\r\n' < "$SRC/KART_SYNC_LATEST.txt")"
fi
if [[ -f "$DST/KART_SYNC_LATEST.txt" ]]; then
  latest_dst="$(tr -d '\r\n' < "$DST/KART_SYNC_LATEST.txt")"
fi

if [[ -z "$latest_src" || -z "$latest_dst" ]]; then
  bad "missing KART_SYNC_LATEST.txt (run ./scripts/sync-wsl-workspace.sh)"
elif [[ "$latest_src" != "$latest_dst" ]]; then
  bad "stamp mismatch SRC=$latest_src DST=$latest_dst"
else
  ok "sync stamp=$latest_src"
fi

if [[ -n "$latest_src" && -f "$SRC/$latest_src" && -f "$DST/$latest_src" ]]; then
  h1="$(sha256sum "$SRC/$latest_src" | awk '{print $1}')"
  h2="$(sha256sum "$DST/$latest_src" | awk '{print $1}')"
  if [[ "$h1" == "$h2" ]]; then
    ok "stamp file hash match"
  else
    bad "stamp file hash mismatch"
  fi
fi

# Key tracked files that must match after sync (exclude gitignored catalog/runs/target).
KEYS=(
  "pom.xml"
  "config/planner.yaml"
  "scripts/kart-env.sh"
  "scripts/sync-wsl-workspace.sh"
  "src/main/java/kart/cost/FeedbackCalibrator.java"
)
for rel in "${KEYS[@]}"; do
  if [[ ! -f "$SRC/$rel" ]]; then
    bad "missing on SRC: $rel"
    continue
  fi
  if [[ ! -f "$DST/$rel" ]]; then
    bad "missing on DST: $rel"
    continue
  fi
  h1="$(sha256sum "$SRC/$rel" | awk '{print $1}')"
  h2="$(sha256sum "$DST/$rel" | awk '{print $1}')"
  if [[ "$h1" == "$h2" ]]; then
    ok "hash $rel"
  else
    bad "hash mismatch $rel (re-run sync-wsl-workspace.sh)"
  fi
done

git_src="$(cd "$SRC" && git rev-parse --short HEAD 2>/dev/null || echo n/a)"
echo "git_src_short=$git_src"
echo "KART_SRC=$SRC"
echo "KART_DST=$DST"
echo "KART_EXPERIMENT_MANIFEST=$KART_EXPERIMENT_MANIFEST"

if [[ "$FAIL" -gt 0 ]]; then
  echo "=== sync-check: FAILED ($FAIL) ==="
  exit 1
fi
echo "=== sync-check: OK ==="
exit 0
