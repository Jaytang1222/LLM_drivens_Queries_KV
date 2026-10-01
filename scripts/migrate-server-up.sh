#!/usr/bin/env bash
set -euo pipefail
source ~/.kart_server_env
cd "$KART_DST"
ls -lh target/kart.jar
echo "=== UP ==="
./scripts/kart.sh up
echo "=== DOCTOR ==="
./scripts/kart.sh doctor
echo "STACK_READY"
