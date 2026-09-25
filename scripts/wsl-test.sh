#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck disable=SC1091
source "$ROOT/scripts/kart-env.sh"

REPO="${KART_MAVEN_REPO}"
SRC="${KART_SRC}"
DST="${KART_DST}"

bash "$ROOT/scripts/sync-wsl-workspace.sh"
bash "$DST/scripts/check-wsl-sync.sh"
cd "$DST"

echo "Using maven.repo.local=$REPO (cwd=$DST)"
mvn -Dmaven.repo.local="$REPO" test -DtrimStackTrace=false 2>&1 | tee /tmp/kart-test.log
mvn -Dmaven.repo.local="$REPO" -q package -DskipTests
mkdir -p "$SRC/target"
cp -f target/kart.jar "$SRC/target/kart.jar"
echo "OK jar -> $SRC/target/kart.jar"
grep -E 'Tests run:|BUILD SUCCESS|BUILD FAILURE' /tmp/kart-test.log | tail -20
