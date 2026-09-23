#!/usr/bin/env bash
# Multi-scenario demo / verify — experiment path requires HBase for ALL query cases.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck disable=SC1091
source "$ROOT/scripts/kart-env.sh"
cd "$ROOT"

LOG=/tmp/kart-demo-verify.log
: > "$LOG"
pass=0
fail=0
skip=0

ok()   { echo "PASS  $*" | tee -a "$LOG"; pass=$((pass+1)); }
bad()  { echo "FAIL  $*" | tee -a "$LOG"; fail=$((fail+1)); }
skip() { echo "SKIP  $*" | tee -a "$LOG"; skip=$((skip+1)); }

echo "======== KART demo-verify (HBase experiment path) ========" | tee -a "$LOG"
echo "root=$ROOT time=$(date)" | tee -a "$LOG"

kart_ensure_jar
if [[ -f "$KART_JAR" ]]; then ok "jar present"; else bad "jar missing"; fi

HBASE_OK=0
if java -Dkart.root="$ROOT" -jar "$KART_JAR" doctor > /tmp/kart-dv-doctor.log 2>&1; then
  if grep -q "doctor: OK" /tmp/kart-dv-doctor.log; then
    ok "doctor"
    HBASE_OK=1
  else
    bad "doctor (missing OK — run ./scripts/kart.sh up)"
  fi
else
  bad "doctor (run ./scripts/kart.sh up)"
fi

if [[ "$HBASE_OK" -eq 1 ]]; then
  if java -Dkart.root="$ROOT" -jar "$KART_JAR" load-fixture-hbase > /tmp/kart-dv-fix-fx.log 2>&1; then
    ok "load-fixture-hbase"
  else
    bad "load-fixture-hbase (see /tmp/kart-dv-load-fx.log)"
    HBASE_OK=0
  fi
fi

UTT='Find the 2 trajectories most similar to R inside fixture_box between 2008-02-02T08:00:00+08:00 and 2008-02-02T08:10:00+08:00'
if [[ "$HBASE_OK" -eq 1 ]]; then
  if printf '%s\n' "$UTT" 'y' '/quit' | bash "$ROOT/scripts/kart.sh" chat --mock \
      > /tmp/kart-dv-mock-chat.out 2> /tmp/kart-dv-mock-chat.err; then
    if grep -q 'backend=hbase' /tmp/kart-dv-mock-chat.out \
       && grep -q 'status=OK' /tmp/kart-dv-mock-chat.out \
       && grep -qE 'trajectory_ids=\[A, B\]|trajectory_ids=\[A,B\]' /tmp/kart-dv-mock-chat.out; then
      ok "HBase mock chat Top-K → [A, B]"
    else
      bad "HBase mock chat incomplete (see /tmp/kart-dv-mock-chat.*)"
    fi
  else
    bad "HBase mock chat exited non-zero"
  fi

  if java -Dkart.root="$ROOT" -Dkart.status=false -jar "$KART_JAR" query-nl --mock \
      "Please COUNT how many trajectories intersect fixture_box" \
      > /tmp/kart-dv-count.out 2> /tmp/kart-dv-count.err; then
    bad "COUNT should not succeed"
  else
    code=$?
    if grep -qi 'UNSUPPORTED' /tmp/kart-dv-count.out /tmp/kart-dv-count.err; then
      ok "COUNT unsupported on HBase path (exit=$code)"
    else
      bad "COUNT reject missing UNSUPPORTED"
    fi
  fi
else
  skip "fixture HBase chat/COUNT (needs up + load-fixture)"
fi

if bash "$ROOT/scripts/demo-failures.sh" > /tmp/kart-dv-demo.out 2>&1; then
  ok "demo-failures (MemoryBackend unit demos)"
else
  bad "demo-failures"
fi

if [[ "$HBASE_OK" -eq 1 ]]; then
  if bash "$ROOT/scripts/run-tdrive-smoke.sh" > /tmp/kart-dv-smoke.out 2>&1; then
    if grep -q 'passed=22' /tmp/kart-dv-smoke.out && grep -q 'failed=0' /tmp/kart-dv-smoke.out; then
      ok "tdrive smoke 22/22"
    else
      bad "smoke summary missing 22/22"
    fi
  else
    bad "smoke script failed"
  fi
else
  skip "tdrive smoke"
fi

kart_load_llm_env || true
if [[ -n "${LLM_API_KEY:-}" && "$HBASE_OK" -eq 1 ]]; then
  if bash "$ROOT/scripts/probe-llm.sh" > /tmp/kart-dv-probe.out 2>&1; then
    if grep -q 'PROBE_OK\|PROBE_LLM_OK' /tmp/kart-dv-probe.out; then
      ok "live LLM probe"
    else
      bad "live probe"
    fi
  else
    bad "live probe failed"
  fi
  if bash "$ROOT/scripts/live-nl-acceptance.sh" > /tmp/kart-dv-live.out 2>&1; then
    if grep -q 'LIVE_ACCEPTANCE PASS' /tmp/kart-dv-live.out; then
      ok "live-nl-acceptance (HBase)"
    else
      bad "live-nl-acceptance"
    fi
  else
    if grep -q 'LIVE_ACCEPTANCE PASS' /tmp/kart-dv-live.out; then
      ok "live-nl-acceptance (HBase)"
    else
      bad "live-nl-acceptance"
    fi
  fi
else
  skip "live LLM"
fi

echo "" | tee -a "$LOG"
echo "SUMMARY pass=$pass fail=$fail skip=$skip log=$LOG" | tee -a "$LOG"
if [[ "$fail" -gt 0 ]]; then
  echo "DEMO_VERIFY_FAIL"
  exit 1
fi
echo "DEMO_VERIFY_PASS"
exit 0
