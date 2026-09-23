#!/usr/bin/env bash
# T5.7 failure-path demos (MemoryBackend + COUNT + LLM unavailable).
set -euo pipefail
export JAVA_HOME=/usr/lib/jvm/java-8-openjdk-amd64
export PATH="$JAVA_HOME/bin:/mnt/c/maven/apache-maven-3.6.3/bin:$PATH"
REPO=/home/jaytang/.m2/repository
SRC=/mnt/f/Projects/LLM_KV
DST=/home/jaytang/build/LLM_KV

rsync -a --exclude target --exclude datasets --exclude .git --exclude runs "$SRC/" "$DST/"
cd "$DST"
mvn -Dmaven.repo.local="$REPO" -q package -DskipTests
java -Dkart.root="$DST" -jar target/kart.jar demo-failures --config-root "$DST"
