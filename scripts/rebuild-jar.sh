#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck disable=SC1091
source "$ROOT/scripts/kart-env.sh"

REPO="${KART_MAVEN_REPO}"
SRC="${KART_SRC}"
DST="${KART_DST}"

bash "$ROOT/scripts/sync-wsl-workspace.sh"
cd "$DST"
mvn -Dmaven.repo.local="$REPO" -q package -DskipTests
mkdir -p "$SRC/target" "$DST/target"
cp -f target/kart.jar "$SRC/target/kart.jar"
echo "jar rebuilt -> $SRC/target/kart.jar (built in $DST)"
