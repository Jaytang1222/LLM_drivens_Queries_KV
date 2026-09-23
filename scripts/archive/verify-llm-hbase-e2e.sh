#!/usr/bin/env bash
# Verify LLM → Dialog → QueryEngine → HBase end-to-end.
set -euo pipefail
ROOT=/home/jaytang/projects/llm-kv
cd "$ROOT"
# sync sources
bash /mnt/f/Projects/LLM_KV/scripts/sync-wsl-workspace.sh >/dev/null || true
test -f target/kart.jar || bash scripts/rebuild-jar.sh

echo "======== 1) restart stack ========"
bash scripts/kart.sh down || true
sleep 3
bash scripts/kart.sh up
bash scripts/kart.sh doctor | tee /tmp/kart-e2e-doctor.txt | tail -25
grep -q 'doctor: OK' /tmp/kart-e2e-doctor.txt

echo "======== 2) ensure fixture on HBase ========"
bash scripts/kart.sh load-fixture | tee /tmp/kart-e2e-fx.txt | tail -10
test -f catalog/fixture_v1_ready.manifest.json

echo "======== 3) Mock LLM → HBase (A1) ========"
UTT='Find the 2 trajectories most similar to R inside fixture_box between 2008-02-02T08:00:00+08:00 and 2008-02-02T08:10:00+08:00'
printf '%s\n' "$UTT" 'y' '/quit' | bash scripts/kart.sh chat --mock \
  > /tmp/kart-e2e-mock.out 2> /tmp/kart-e2e-mock.err || true
echo '--- mock out ---'
grep -E 'backend=|status=|trajectory_ids=|ERROR|Confirm' /tmp/kart-e2e-mock.out || true
MOCK_OK=0
if grep -q 'backend=hbase' /tmp/kart-e2e-mock.out \
   && grep -q 'status=OK' /tmp/kart-e2e-mock.out \
   && grep -qE 'trajectory_ids=\[A, B\]|trajectory_ids=\[A,B\]' /tmp/kart-e2e-mock.out; then
  MOCK_OK=1
  echo 'MOCK_HBASE: PASS'
else
  echo 'MOCK_HBASE: FAIL'
  tail -40 /tmp/kart-e2e-mock.err || true
fi

echo "======== 4) Live LLM → HBase (if .env) ========"
# shellcheck disable=SC1091
source scripts/load-llm-env.sh || true
LIVE_OK=-1
if [[ -n "${LLM_API_KEY:-}" ]]; then
  printf '%s\n' "$UTT" 'y' '/quit' | bash scripts/kart.sh chat \
    > /tmp/kart-e2e-live.out 2> /tmp/kart-e2e-live.err || true
  echo '--- live out ---'
  grep -E 'backend=|status=|trajectory_ids=|ERROR|UNSUPPORTED|Confirm|NEED' /tmp/kart-e2e-live.out || true
  if grep -q 'backend=hbase' /tmp/kart-e2e-live.out \
     && grep -q 'status=OK' /tmp/kart-e2e-live.out \
     && grep -qE 'trajectory_ids=\[A, B\]|trajectory_ids=\[A,B\]' /tmp/kart-e2e-live.out; then
    LIVE_OK=1
    echo 'LIVE_HBASE: PASS'
  else
    LIVE_OK=0
    echo 'LIVE_HBASE: FAIL'
    tail -50 /tmp/kart-e2e-live.err || true
  fi
else
  echo 'LIVE_HBASE: SKIP (no LLM_API_KEY)'
fi

echo "======== 5) query-ir HBase (no LLM) ========"
python3 - <<'PY'
import json
from pathlib import Path
t0=1201910400000
ir={
  "ir_version":"1.0","query_id":"q_fixture_e2e",
  "source":{"dataset_id":"fixture_v1","entity":"trajectory"},
  "temporal":{"start_ms":t0,"end_ms":t0+600000},
  "spatial":{"min_x":4.0,"min_y":4.0,"max_x":8.0,"max_y":8.0,"relation":"INTERSECTS","boundary":"INCLUDED"},
  "predicates":[],"semantics":{"mode":"OBSERVED_POINT","coupling":"SAME_POINT"},
  "similarity":{"metric":"DTW","reference_tid":4,"scope":"FULL_TRAJECTORY","exclude_reference":True,
                "local_distance":"EUCLIDEAN","normalization":"NONE"},
  "result":{"mode":"TOP_K","k":2,"tie_breaker":"TID_ASC"},
  "snapshot":{"manifest_id":"fixture_v1_ready","semantics_version":"point_dtw_v1"}
}
Path("/tmp/fx_e2e.json").write_text(json.dumps(ir,indent=2))
PY
java -Xmx2g -Dkart.root="$PWD" -jar target/kart.jar query-ir \
  --ir /tmp/fx_e2e.json --catalog catalog --manifest fixture_v1_ready \
  --runs /tmp/kart-e2e-ir-runs \
  > /tmp/kart-e2e-ir.out 2>/tmp/kart-e2e-ir.err || true
grep -E 'status=|trajectory_ids=|selected_plan=' /tmp/kart-e2e-ir.out || true
IR_OK=0
if grep -q 'status=OK' /tmp/kart-e2e-ir.out \
   && grep -qE 'trajectory_ids=\[A, B\]|trajectory_ids=\[A,B\]' /tmp/kart-e2e-ir.out; then
  IR_OK=1
  echo 'QUERY_IR_HBASE: PASS'
else
  echo 'QUERY_IR_HBASE: FAIL'
  tail -30 /tmp/kart-e2e-ir.err || true
fi

echo ""
echo "======== SUMMARY ========"
echo "doctor+fixture: OK"
echo "mock_llm→hbase: $([[ $MOCK_OK -eq 1 ]] && echo PASS || echo FAIL)"
echo "live_llm→hbase: $([[ $LIVE_OK -eq 1 ]] && echo PASS || ([[ $LIVE_OK -eq -1 ]] && echo SKIP || echo FAIL))"
echo "query_ir→hbase: $([[ $IR_OK -eq 1 ]] && echo PASS || echo FAIL)"
if [[ $MOCK_OK -eq 1 && $IR_OK -eq 1 ]]; then
  if [[ $LIVE_OK -eq 0 ]]; then
    echo 'OVERALL: PARTIAL (HBase exec OK; live LLM failed)'
    exit 2
  fi
  echo 'OVERALL: PASS (LLM path to HBase verified; live skipped or ok)'
  exit 0
fi
echo 'OVERALL: FAIL'
exit 1
