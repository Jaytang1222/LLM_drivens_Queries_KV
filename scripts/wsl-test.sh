#!/usr/bin/env bash
set -euo pipefail
export JAVA_HOME=/usr/lib/jvm/java-8-openjdk-amd64
export PATH="$JAVA_HOME/bin:/mnt/c/maven/apache-maven-3.6.3/bin:$PATH"
# Critical: Windows Maven settings may set localRepository to F:\... which breaks under WSL
export MAVEN_OPTS="${MAVEN_OPTS:-}"
REPO=/home/jaytang/.m2/repository
SRC=/mnt/f/Projects/LLM_KV
DST=/home/jaytang/projects/llm-kv
mkdir -p /home/jaytang/build "$REPO" "$(dirname "$DST")"
if [[ ! -e /home/jaytang/build/LLM_KV ]]; then
  ln -sfn "$DST" /home/jaytang/build/LLM_KV
fi
rsync -a --delete --exclude target --exclude datasets --exclude .git "$SRC/" "$DST/"
cd "$DST"

echo "Using maven.repo.local=$REPO"
mvn -Dmaven.repo.local="$REPO" test -DtrimStackTrace=false 2>&1 | tee /tmp/kart-test.log
mvn -Dmaven.repo.local="$REPO" -q package -DskipTests
mkdir -p "$SRC/target"
cp -f target/kart.jar "$SRC/target/kart.jar"
echo "OK jar -> $SRC/target/kart.jar"
grep -E 'Tests run:|BUILD SUCCESS|BUILD FAILURE' /tmp/kart-test.log | tail -20
