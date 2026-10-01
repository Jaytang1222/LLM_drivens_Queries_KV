#!/usr/bin/env bash
# Hot-patch CBO-LLM proposal arm + max_tokens client bits into kart.jar under /home/tyq.
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
  src/main/java/kart/llm/OpenAiCompatibleClient.java
  src/main/java/kart/bench/CboLlmProposalArm.java
)
for SRC in "${SRCS[@]}"; do
  [[ -f "$SRC" ]] || { echo "FAIL: missing $SRC"; exit 1; }
done

WORKDIR=$(mktemp -d /home/tyq/tmp_patch_XXXXXX)
trap 'rm -rf "$WORKDIR"' EXIT
echo "=== javac patch LLM-on (max_tokens + speculative P_T) into jar ==="
javac -source 1.8 -target 1.8 -cp "$JAR" -d "$WORKDIR" "${SRCS[@]}"
find "$WORKDIR" -name '*.class' | while read -r f; do
  rel="${f#$WORKDIR/}"
  echo "jar uf $rel"
  ( cd "$WORKDIR" && jar uf "$JAR" "$rel" )
done

tmpdir=$(mktemp -d /home/tyq/tmp_jarcheck_XXXXXX)
trap 'rm -rf "$WORKDIR" "$tmpdir"' EXIT
( cd "$tmpdir" && jar xf "$JAR" kart/bench/CboLlmProposalArm.class )
strings "$tmpdir/kart/bench/CboLlmProposalArm.class" | grep -F 'cbo_llm_compact_v6' \
  || { echo "FAIL: jar missing compact_v6 string"; exit 1; }
strings "$tmpdir/kart/bench/CboLlmProposalArm.class" | grep -F 'speculative_used' \
  || { echo "FAIL: jar missing speculative_used string"; exit 1; }
( cd "$tmpdir" && jar xf "$JAR" kart/llm/LlmOptions.class )
strings "$tmpdir/kart/llm/LlmOptions.class" | grep -F 'KART_CBO_LLM_MAX_TOKENS' \
  || { echo "FAIL: jar missing KART_CBO_LLM_MAX_TOKENS string"; exit 1; }
echo "PATCH_OK jar=$JAR"
ls -lh "$JAR"
