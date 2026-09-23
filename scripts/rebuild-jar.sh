#!/usr/bin/env bash
set -euo pipefail
export JAVA_HOME=/usr/lib/jvm/java-8-openjdk-amd64
export PATH="$JAVA_HOME/bin:/mnt/c/maven/apache-maven-3.6.3/bin:$PATH"
REPO=/home/jaytang/.m2/repository
SRC=/mnt/f/Projects/LLM_KV
# Prefer projects/llm-kv; build/LLM_KV is a compat symlink to the same tree
DST=/home/jaytang/projects/llm-kv
mkdir -p "$(dirname "$DST")"
# Ensure compat symlink used by older scripts
if [[ ! -e /home/jaytang/build/LLM_KV ]]; then
  mkdir -p /home/jaytang/build
  ln -sfn "$DST" /home/jaytang/build/LLM_KV
fi
rsync -a --exclude target --exclude datasets --exclude .git "$SRC/" "$DST/"
cd "$DST"
mvn -Dmaven.repo.local="$REPO" -q package -DskipTests
mkdir -p "$SRC/target"
cp -f target/kart.jar "$SRC/target/kart.jar"
echo "jar rebuilt"
