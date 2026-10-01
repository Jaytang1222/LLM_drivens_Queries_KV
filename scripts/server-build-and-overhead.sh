#!/usr/bin/env bash
# Patch CboLlmProposalArm into existing kart.jar on the server (no full Maven).
set -euo pipefail
source ~/.kart_server_env
cd "$KART_DST"
sed -i 's/\r$//' scripts/run-server-llm-overhead.sh \
  src/main/java/kart/bench/CboLlmProposalArm.java \
  experiments/suites/plan-cbo-llm-overhead-v1.yaml \
  2>/dev/null || true
chmod +x scripts/run-server-llm-overhead.sh

JAR="$KART_DST/target/kart.jar"
SRC=src/main/java/kart/bench/CboLlmProposalArm.java
WORKDIR=$(mktemp -d)
trap 'rm -rf "$WORKDIR"' EXIT

echo "=== javac patch CboLlmProposalArm ==="
javac -source 1.8 -target 1.8 -cp "$JAR" -d "$WORKDIR" "$SRC"
find "$WORKDIR" -name 'CboLlmProposalArm*.class' | while read -r f; do
  rel="${f#$WORKDIR/}"
  echo "jar uf $rel"
  ( cd "$WORKDIR" && jar uf "$JAR" "$rel" )
done
jar tf "$JAR" | grep 'kart/bench/CboLlmProposalArm' || true
ls -lh "$JAR"

echo "=== run overhead on server ==="
bash scripts/run-server-llm-overhead.sh
