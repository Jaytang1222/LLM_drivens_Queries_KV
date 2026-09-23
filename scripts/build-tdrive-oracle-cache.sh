#!/usr/bin/env bash
# Build T-Drive smoke workload + FullScanOracle answer cache (T2.9).
set -euo pipefail
export JAVA_HOME=/usr/lib/jvm/java-8-openjdk-amd64
export PATH="$JAVA_HOME/bin:/mnt/c/maven/apache-maven-3.6.3/bin:$PATH"
REPO=/home/jaytang/.m2/repository
SRC=/mnt/f/Projects/LLM_KV
DST=/home/jaytang/build/LLM_KV

rsync -a --exclude target --exclude datasets --exclude .git --exclude runs "$SRC/" "$DST/"
cd "$DST"
mvn -Dmaven.repo.local="$REPO" -q package -DskipTests
mkdir -p "$SRC/target"
cp -f target/kart.jar "$SRC/target/kart.jar"

DATA="$DST/datasets/tdrive"
if [[ ! -d "$DATA" ]]; then
  DATA=/mnt/f/Projects/LLM_KV/datasets/tdrive
fi

java -Xmx8g -Dkart.root="$DST" -jar target/kart.jar build-oracle-cache \
  --data "$DATA" \
  --catalog "$DST/catalog" \
  --manifest tdrive_v1_ready \
  --workload-out "$DST/experiments/workloads/tdrive_smoke.json" \
  --oracle-out "$DST/experiments/workloads/tdrive_smoke.oracle.json"

# sync generated workload/oracle back to Windows tree
mkdir -p "$SRC/experiments/workloads"
cp -f "$DST/experiments/workloads/tdrive_smoke.json" "$SRC/experiments/workloads/"
cp -f "$DST/experiments/workloads/tdrive_smoke.oracle.json" "$SRC/experiments/workloads/"
echo "oracle cache ready"
