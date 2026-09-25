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
  down|stop)
    bash "$ROOT/scripts/stop-stack.sh"
    ;;
  chat|repl)
    kart_load_llm_env
    export KART_STATUS="${KART_STATUS:-true}"
    # Live LLM → HBase tdrive_v1_ready (default)
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
  sync)
    bash "$ROOT/scripts/sync-wsl-workspace.sh" "$@"
    ;;
  sync-check)
    bash "$ROOT/scripts/check-wsl-sync.sh" "$@"
    ;;
  fit-cost-pack)
    bash "$ROOT/scripts/publish-cost-calib-pack.sh" "$@"
    ;;
  hbase-evidence)
    bash "$ROOT/scripts/capture-hbase-evidence.sh" "$@"
    ;;
  demo-failures)
    bash "$ROOT/scripts/demo-failures.sh" "$@"
    ;;
  test)
    bash "$ROOT/scripts/wsl-test.sh" "$@"
    ;;
  rebuild)
    bash "$ROOT/scripts/rebuild-jar.sh" "$@"
    ;;
  chat-easy)
    kart_load_llm_env
    bash "$ROOT/scripts/chat-easy-acceptance.sh" "$@"
    ;;
  chat-handbook)
    kart_load_llm_env
    bash "$ROOT/scripts/chat-handbook-acceptance.sh" "$@"
    ;;
  run)
    kart_load_llm_env
    kart_java "$@"
    ;;
  help|-h|--help)
    cat <<'EOF'
KART — run all of this in WSL (not PowerShell).

Experiment path (Live LLM → HBase T-Drive):
  ./scripts/kart.sh up            # ZK + HBase
  ./scripts/kart.sh run build-snapshot --data datasets/tdrive   # if catalog missing
  ./scripts/kart.sh chat          # Live LLM → HBase (need .env)
  ./scripts/kart.sh smoke         # T-Drive vs Oracle 24/24
  ./scripts/kart.sh down

Also: doctor | probe | check | demo-failures | chat-easy | chat-handbook | rebuild | test | run <cli-args>
      sync | sync-check | fit-cost-pack | hbase-evidence
See docs/how-to-run.md, docs/experiment-scope.md, docs/easy_query_example.md.
EOF
    ;;
  *)
    echo "Unknown: $cmd — try ./scripts/kart.sh help" >&2
    echo "Doc: docs/how-to-run.md" >&2
    exit 2
    ;;
esac
