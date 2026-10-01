#!/usr/bin/env bash
# Hot-patch refine_2 sources (prepare timing/reuse + LLM independent v7) into kart.jar.
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
  src/main/java/kart/llm/LlmOptions.java
  src/main/java/kart/cost/HBaseRegionMapping.java
  src/main/java/kart/cost/CostFeaturesExtractor.java
  src/main/java/kart/query/QueryEngine.java
  src/main/java/kart/bench/CboLlmProposalArm.java
)
for SRC in "${SRCS[@]}"; do
  [[ -f "$SRC" ]] || { echo "FAIL: missing $SRC"; exit 1; }
done

WORKDIR=$(mktemp -d /home/tyq/tmp_patch_XXXXXX)
trap 'rm -rf "$WORKDIR"' EXIT
echo "=== javac patch refine2 into jar ==="
javac -source 1.8 -target 1.8 -cp "$JAR" -d "$WORKDIR" "${SRCS[@]}"
find "$WORKDIR" -name '*.class' | while read -r f; do
  rel="${f#$WORKDIR/}"
  echo "jar uf $rel"
  ( cd "$WORKDIR" && jar uf "$JAR" "$rel" )
done

tmpdir=$(mktemp -d /home/tyq/tmp_jarcheck_XXXXXX)
trap 'rm -rf "$WORKDIR" "$tmpdir"' EXIT
( cd "$tmpdir" && jar xf "$JAR" kart/bench/CboLlmProposalArm.class )
strings "$tmpdir/kart/bench/CboLlmProposalArm.class" | grep -F 'cbo_llm_independent_v7' \
  || { echo "FAIL: jar missing independent_v7 string"; exit 1; }
strings "$tmpdir/kart/bench/CboLlmProposalArm.class" | grep -F 't_fixed_prepare_ms' \
  || { echo "FAIL: jar missing t_fixed_prepare_ms string"; exit 1; }
( cd "$tmpdir" && jar xf "$JAR" kart/query/QueryEngine.class )
strings "$tmpdir/kart/query/QueryEngine.class" | grep -F 'safety_only' \
  || { echo "FAIL: jar missing safety_only string"; exit 1; }
echo "PATCH_OK jar=$JAR"
ls -lh "$JAR"
