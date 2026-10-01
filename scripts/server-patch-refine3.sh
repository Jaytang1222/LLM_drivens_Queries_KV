#!/usr/bin/env bash
set -euo pipefail
source ~/.kart_server_env
cd "$KART_DST"
case "$KART_DST" in
  /home/tyq/*) ;;
  *) echo "FAIL: KART_DST=$KART_DST"; exit 1 ;;
esac
JAR="$KART_DST/target/kart.jar"
[[ -f "$JAR" ]] || { echo "FAIL: missing $JAR"; exit 1; }

SRCS=(
  src/main/java/kart/validation/ValidatorTiming.java
  src/main/java/kart/validation/RangeCoverageIndex.java
  src/main/java/kart/validation/ValidationReport.java
  src/main/java/kart/validation/PlanValidator.java
  src/main/java/kart/query/QueryEngine.java
  src/main/java/kart/bench/CboLlmProposalArm.java
  src/main/java/kart/llm/LlmOptions.java
)
for SRC in "${SRCS[@]}"; do
  [[ -f "$SRC" ]] || { echo "FAIL: missing $SRC"; exit 1; }
done

WORKDIR=$(mktemp -d /home/tyq/tmp_patch_XXXXXX)
trap 'rm -rf "$WORKDIR"' EXIT
echo "=== javac patch refine3 validator+hint ==="
javac -source 1.8 -target 1.8 -cp "$JAR" -d "$WORKDIR" "${SRCS[@]}"
find "$WORKDIR" -name '*.class' | while read -r f; do
  rel="${f#$WORKDIR/}"
  ( cd "$WORKDIR" && jar uf "$JAR" "$rel" )
done

tmpdir=$(mktemp -d /home/tyq/tmp_jarcheck_XXXXXX)
trap 'rm -rf "$WORKDIR" "$tmpdir"' EXIT
( cd "$tmpdir" && jar xf "$JAR" kart/validation/RangeCoverageIndex.class )
strings "$tmpdir/kart/validation/RangeCoverageIndex.class" | grep -F 'prefixMaxStop' \
  || { echo "FAIL: missing RangeCoverageIndex"; exit 1; }
( cd "$tmpdir" && jar xf "$JAR" kart/bench/CboLlmProposalArm.class )
strings "$tmpdir/kart/bench/CboLlmProposalArm.class" | grep -F 'KART_CBO_LLM_HINT' \
  || { echo "FAIL: missing hint mode"; exit 1; }
echo "PATCH_OK jar=$JAR"
ls -lh "$JAR"
