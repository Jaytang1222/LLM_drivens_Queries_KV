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
  src/main/java/kart/cost/BenefitCalibrator.java
  src/main/java/kart/bench/FixedPlanIdArm.java
  src/main/java/kart/bench/CboLlmProposalArm.java
  src/main/java/kart/bench/ArmRegistry.java
  src/main/java/kart/bench/SuiteRunner.java
)
for SRC in "${SRCS[@]}"; do
  [[ -f "$SRC" ]] || { echo "FAIL: missing $SRC"; exit 1; }
done

WORKDIR=$(mktemp -d /home/tyq/tmp_patch_XXXXXX)
trap 'rm -rf "$WORKDIR"' EXIT
echo "=== javac patch refine5 v9+cbo_parallel+iso_timing ==="
# clear proxy for local javac/jar
export JAVA_TOOL_OPTIONS=""
javac -source 1.8 -target 1.8 -cp "$JAR" -d "$WORKDIR" "${SRCS[@]}"
find "$WORKDIR" -name '*.class' | while read -r f; do
  rel="${f#$WORKDIR/}"
  ( cd "$WORKDIR" && jar uf "$JAR" "$rel" )
done

tmpdir=$(mktemp -d /home/tyq/tmp_jarcheck_XXXXXX)
trap 'rm -rf "$WORKDIR" "$tmpdir"' EXIT
( cd "$tmpdir" && jar xf "$JAR" kart/bench/CboLlmProposalArm.class )
strings "$tmpdir/kart/bench/CboLlmProposalArm.class" | grep -F 'cbo_llm_short_v9' \
  || { echo "FAIL: missing v9 prompt"; exit 1; }
strings "$tmpdir/kart/bench/CboLlmProposalArm.class" | grep -F 'cbo_parallel' \
  || { echo "FAIL: missing cbo_parallel"; exit 1; }
( cd "$tmpdir" && jar xf "$JAR" kart/bench/FixedPlanIdArm.class )
strings "$tmpdir/kart/bench/FixedPlanIdArm.class" | grep -F 'llm_call_ms' \
  || { echo "FAIL: missing llm_call_ms"; exit 1; }
echo "PATCH_OK jar=$JAR"
ls -lh "$JAR"
