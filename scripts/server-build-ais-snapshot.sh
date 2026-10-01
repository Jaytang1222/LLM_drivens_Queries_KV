#!/usr/bin/env bash
# Build ais_v1_ready on the server without touching T-Drive tables.
set -euo pipefail
source ~/.kart_server_env
cd "$KART_DST"

# Count T-Drive raw rows before (best-effort via HBase shell is heavy; use doctor + table list).
echo "=== preflight: T-Drive tables must remain ==="
hbase_cmd() {
  "$HBASE_HOME/bin/hbase" shell -n 2>/dev/null <<EOF || true
list
EOF
}
BEFORE=$(hbase_cmd | tr -d '\r' || true)
echo "$BEFORE" | grep -E 'traj_raw_v1|idx_time_v1' || echo "WARN: could not list tables via hbase shell"

echo "=== build-snapshot ais_v1_ready ==="
./scripts/kart.sh run build-snapshot \
  --data datasets/ais \
  --format ais \
  --manifest-id ais_v1_ready \
  --crs EPSG:32634 \
  --table-prefix ais_v1

test -f catalog/ais_v1_ready.manifest.json
python3 - <<'PY'
import json
from pathlib import Path
m=json.loads(Path("catalog/ais_v1_ready.manifest.json").read_text())
assert m["status"]=="READY" or m.get("status")=="READY"
assert m["layout"]["crs"]=="EPSG:32634"
tables=m["layout"]["tables"]
for k,v in tables.items():
  assert "ais_v1" in v, (k,v)
  assert not v.endswith("_v1") or "ais" in v
print("manifest_ok trajs=", m.get("build_report",{}).get("trajectories"),
      "points=", m.get("build_report",{}).get("points"))
print("tables", tables)
PY

AFTER=$(hbase_cmd | tr -d '\r' || true)
echo "$AFTER" | grep -q 'traj_raw_v1' && echo "TDRIVE_TABLE_STILL_PRESENT=yes" || echo "TDRIVE_TABLE_STILL_PRESENT=unknown"
echo "$AFTER" | grep -E 'traj_raw_ais_v1|idx_time_ais_v1' || echo "WARN: ais tables not visible in list"
echo AIS_SNAPSHOT_DONE
