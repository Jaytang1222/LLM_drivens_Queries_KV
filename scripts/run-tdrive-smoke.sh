#!/usr/bin/env bash
# Run T-Drive smoke vs oracle on HBase (T2.9). Prefer in-process smoke-tdrive.
set -euo pipefail
export JAVA_HOME=/usr/lib/jvm/java-8-openjdk-amd64
export PATH="$JAVA_HOME/bin:/mnt/c/maven/apache-maven-3.6.3/bin:$PATH"
REPO=/home/jaytang/.m2/repository
SRC=/mnt/f/Projects/LLM_KV
DST=/home/jaytang/build/LLM_KV

rsync -a --exclude target --exclude datasets --exclude .git --exclude runs "$SRC/" "$DST/"
cd "$DST"
mvn -Dmaven.repo.local="$REPO" -q package -DskipTests
mkdir -p "$SRC/target" "$SRC/experiments/results"
cp -f target/kart.jar "$SRC/target/kart.jar"

# ensure workload+oracle exist
if [[ ! -f experiments/workloads/tdrive_smoke.oracle.json ]]; then
  echo "oracle cache missing; building..."
  bash "$DST/scripts/build-tdrive-oracle-cache.sh"
fi

java -Xmx4g -Dkart.root="$DST" -jar target/kart.jar smoke-tdrive \
  --workload experiments/workloads/tdrive_smoke.json \
  --oracle experiments/workloads/tdrive_smoke.oracle.json \
  --catalog catalog \
  --manifest tdrive_v1_ready \
  --report experiments/results/tdrive_smoke_report.json \
  --runs runs/smoke-tdrive

cp -f experiments/results/tdrive_smoke_report.json "$SRC/experiments/results/"
echo "smoke report: $SRC/experiments/results/tdrive_smoke_report.json"
