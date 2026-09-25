#!/usr/bin/env bash
# Run T-Drive smoke vs oracle on HBase (T2.9). Prefer in-process smoke-tdrive.
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
mkdir -p "$SRC/target" "$SRC/experiments/results"
cp -f target/kart.jar "$SRC/target/kart.jar"

if [[ ! -f experiments/workloads/tdrive_smoke.oracle.json ]]; then
  echo "oracle cache missing; building..."
  bash "$DST/scripts/build-tdrive-oracle-cache.sh"
fi

java -Xmx4g -Dkart.root="$DST" -jar target/kart.jar smoke-tdrive \
  --workload experiments/workloads/tdrive_smoke.json \
  --oracle experiments/workloads/tdrive_smoke.oracle.json \
  --catalog catalog \
  --manifest "$MANIFEST" \
  --report experiments/results/tdrive_smoke_report.json \
  --runs runs/smoke-tdrive

cp -f experiments/results/tdrive_smoke_report.json "$SRC/experiments/results/"
echo "smoke report: $SRC/experiments/results/tdrive_smoke_report.json"
