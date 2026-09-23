#!/usr/bin/env bash
# Stop HBase then ZooKeeper.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck disable=SC1091
source "$ROOT/scripts/kart-env.sh"

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
