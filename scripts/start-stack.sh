#!/usr/bin/env bash
# Start ZooKeeper + HBase and wait until doctor is OK (or timeout).
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck disable=SC1091
source "$ROOT/scripts/kart-env.sh"

echo "=== KART start-stack ==="
echo "ZK_HOME=$ZK_HOME"
echo "HBASE_HOME=$HBASE_HOME"

if [[ ! -x "$ZK_HOME/bin/zkServer.sh" ]]; then
  echo "ERROR: zkServer.sh not found at $ZK_HOME/bin" >&2
  exit 1
fi
if [[ ! -x "$HBASE_HOME/bin/start-hbase.sh" ]]; then
  echo "ERROR: start-hbase.sh not found at $HBASE_HOME/bin" >&2
  exit 1
fi

zk_status() {
  "$ZK_HOME/bin/zkServer.sh" status 2>/dev/null | head -5 || true
}

echo "-- ZooKeeper --"
if echo "$(zk_status)" | grep -qi "Mode:"; then
  echo "ZooKeeper already running"
else
  "$ZK_HOME/bin/zkServer.sh" start
fi

echo "-- HBase --"
# start-hbase is idempotent-ish; ignore if already up
"$HBASE_HOME/bin/start-hbase.sh" || true

echo "-- wait for doctor (max ~90s) --"
kart_ensure_jar
ok=0
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
  echo "-- load fixture into HBase (isolated fixture_* tables) --"
  if java -Dkart.root="$KART_ROOT" -jar "$KART_JAR" load-fixture-hbase; then
    echo "fixture_v1_ready ready for chat / query-nl / query-ir"
  else
    echo "WARN: load-fixture-hbase failed — run: ./scripts/kart.sh load-fixture" >&2
  fi
  exit 0
fi

echo "=== start-stack: doctor not OK yet ===" >&2
echo "Check ZK/HBase logs; retry: ./scripts/kart.sh doctor" >&2
tail -30 /tmp/kart-doctor-boot.log 2>/dev/null || true
exit 1
