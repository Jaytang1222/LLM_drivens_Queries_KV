#!/usr/bin/env bash
# Build T-Drive smoke workload + FullScanOracle answer cache (T2.9).
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck disable=SC1091
source "$ROOT/scripts/kart-env.sh"

REPO="${KART_MAVEN_REPO}"
SRC="${KART_SRC}"
DST="${KART_DST}"
MANIFEST="${KART_EXPERIMENT_MANIFEST}"

bash "$ROOT/scripts/sync-wsl-workspace.sh"
cd "$DST"
mvn -Dmaven.repo.local="$REPO" -q package -DskipTests
mkdir -p "$SRC/target"
cp -f target/kart.jar "$SRC/target/kart.jar"

DATA="$DST/datasets/tdrive"
if [[ ! -d "$DATA" ]]; then
  DATA="$SRC/datasets/tdrive"
fi

java -Xmx8g -Dkart.root="$DST" -jar target/kart.jar build-oracle-cache \
  --data "$DATA" \
  --catalog "$DST/catalog" \
  --manifest "$MANIFEST" \
  --workload-out "$DST/experiments/workloads/tdrive_smoke.json" \
  --oracle-out "$DST/experiments/workloads/tdrive_smoke.oracle.json"

mkdir -p "$SRC/experiments/workloads"
cp -f "$DST/experiments/workloads/tdrive_smoke.json" "$SRC/experiments/workloads/"
cp -f "$DST/experiments/workloads/tdrive_smoke.oracle.json" "$SRC/experiments/workloads/"
echo "oracle cache ready"
