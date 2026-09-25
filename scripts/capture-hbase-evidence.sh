#!/usr/bin/env bash
# Capture HBase client/server version evidence + list experiment-scoped tables.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck disable=SC1091
source "$ROOT/scripts/kart-env.sh"

OUT_DIR="$ROOT/experiments/results"
mkdir -p "$OUT_DIR"
OUT="$OUT_DIR/hbase_version_evidence.txt"
MANIFEST="${KART_EXPERIMENT_MANIFEST}"

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

# Mirror to Windows SRC when running on DST
if [[ "$ROOT" == "$KART_DST" && -d "$KART_SRC" ]]; then
  mkdir -p "$KART_SRC/experiments/results"
  cp -f "$OUT" "$KART_SRC/experiments/results/"
fi

echo "wrote $OUT"
