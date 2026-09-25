#!/usr/bin/env bash
# Operator verify: doctor + tdrive smoke + optional live NL (no Mock/fixture).
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

echo "======== KART demo-verify (Live LLM → HBase T-Drive) ========" | tee -a "$LOG"
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
  if [[ -f "$ROOT/catalog/tdrive_v1_ready.manifest.json" ]]; then
    ok "catalog tdrive_v1_ready"
  else
    bad "catalog missing tdrive_v1_ready — run build-snapshot"
    HBASE_OK=0
  fi
fi

if [[ "$HBASE_OK" -eq 1 ]]; then
  if bash "$ROOT/scripts/run-tdrive-smoke.sh" > /tmp/kart-dv-smoke.out 2>&1; then
    if grep -q 'passed=24' /tmp/kart-dv-smoke.out && grep -q 'failed=0' /tmp/kart-dv-smoke.out; then
      ok "tdrive smoke 24/24"
    else
      bad "smoke summary missing 24/24"
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
  if bash "$ROOT/scripts/chat-easy-acceptance.sh" > /tmp/kart-dv-chat-easy.out 2>&1; then
    if grep -q 'CHAT_EASY_ACCEPTANCE PASS' /tmp/kart-dv-chat-easy.out; then
      ok "chat-easy-acceptance"
    else
      bad "chat-easy-acceptance"
    fi
  else
    if grep -q 'CHAT_EASY_ACCEPTANCE PASS' /tmp/kart-dv-chat-easy.out; then
      ok "chat-easy-acceptance"
    else
      bad "chat-easy-acceptance"
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
