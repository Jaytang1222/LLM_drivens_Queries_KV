#!/usr/bin/env bash
# Unified KART operator entry — run inside WSL only.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck disable=SC1091
source "$ROOT/scripts/kart-env.sh"
cd "$ROOT"

cmd="${1:-help}"
shift || true

case "$cmd" in
  up|start)
    bash "$ROOT/scripts/start-stack.sh"
    ;;
  load-fixture|fixture-hbase)
    kart_java load-fixture-hbase "$@"
    ;;
  down|stop)
    bash "$ROOT/scripts/stop-stack.sh"
    ;;
  chat|repl)
    kart_load_llm_env
    export KART_STATUS="${KART_STATUS:-true}"
    # Experiment path: HBase fixture (default). Pass --memory only for offline unit demos.
    kart_java chat "$@"
    ;;
  check|demo-verify)
    bash "$ROOT/scripts/demo-verify.sh" "$@"
    ;;
  doctor)
    kart_java doctor "$@"
    ;;
  probe)
    kart_load_llm_env
    bash "$ROOT/scripts/probe-llm.sh" "$@"
    ;;
  smoke)
    bash "$ROOT/scripts/run-tdrive-smoke.sh" "$@"
    ;;
  test)
    bash "$ROOT/scripts/wsl-test.sh" "$@"
    ;;
  demo|failures)
    bash "$ROOT/scripts/demo-failures.sh" "$@"
    ;;
  rebuild)
    bash "$ROOT/scripts/rebuild-jar.sh" "$@"
    ;;
  verify|strict)
    bash "$ROOT/scripts/strict-verify-all.sh" "$@"
    ;;
  live-accept)
    kart_load_llm_env
    bash "$ROOT/scripts/live-nl-acceptance.sh" "$@"
    ;;
  run)
    kart_load_llm_env
    kart_java "$@"
    ;;
  help|-h|--help)
    cat <<'EOF'
KART — run all of this in WSL (not PowerShell).

Experiment path (all queries via HBase + live LLM):
  ./scripts/kart.sh up            # ZK + HBase + load-fixture
  ./scripts/kart.sh chat          # Live LLM → HBase (need .env)
  ./scripts/kart.sh smoke         # T-Drive vs Oracle
  ./scripts/kart.sh down

Regression (optional): chat --mock | check | load-fixture | doctor
Keep fixture/testdata/Mock/unit tests — see docs/scaffolding.md
EOF
    ;;
  *)
    echo "Unknown: $cmd — try ./scripts/kart.sh help" >&2
    echo "Doc: docs/how-to-run.md" >&2
    exit 2
    ;;
esac
