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
  src/main/java/kart/probe/JointCandidateProbe.java
  src/main/java/kart/cost/BenefitCalibrator.java
  src/main/java/kart/bench/CboLlmProposalArm.java
  src/main/java/kart/bench/FixedPlanIdArm.java
  src/main/java/kart/bench/ArmRegistry.java
  src/main/java/kart/bench/SuiteRunner.java
)
for SRC in "${SRCS[@]}"; do
  [[ -f "$SRC" ]] || { echo "FAIL: missing $SRC"; exit 1; }
done

WORKDIR=$(mktemp -d /home/tyq/tmp_patch_XXXXXX)
trap 'rm -rf "$WORKDIR"' EXIT
echo "=== javac patch refine6 action+probe+trace ==="
export JAVA_TOOL_OPTIONS=""
javac -source 1.8 -target 1.8 -cp "$JAR" -d "$WORKDIR" "${SRCS[@]}"
find "$WORKDIR" -name '*.class' | while read -r f; do
  rel="${f#$WORKDIR/}"
  ( cd "$WORKDIR" && jar uf "$JAR" "$rel" )
done

tmpdir=$(mktemp -d /home/tyq/tmp_jarcheck_XXXXXX)
trap 'rm -rf "$WORKDIR" "$tmpdir"' EXIT
( cd "$tmpdir" && jar xf "$JAR" kart/probe/JointCandidateProbe.class )
strings "$tmpdir/kart/probe/JointCandidateProbe.class" | grep -F 'PROBE_JOINT_CANDIDATES' \
  || { echo "FAIL: missing probe"; exit 1; }
( cd "$tmpdir" && jar xf "$JAR" kart/bench/CboLlmProposalArm.class )
strings "$tmpdir/kart/bench/CboLlmProposalArm.class" | grep -F 'cbo_llm_action_v10' \
  || { echo "FAIL: missing action v10"; exit 1; }
strings "$tmpdir/kart/bench/CboLlmProposalArm.class" | grep -F 'llm_proposed_action' \
  || { echo "FAIL: missing proposed_action field"; exit 1; }
echo "PATCH_OK jar=$JAR"
ls -lh "$JAR"
