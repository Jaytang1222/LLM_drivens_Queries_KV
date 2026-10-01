#!/usr/bin/env bash
# Build only an isolated experimental jar; never overwrites the operational jar.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"
OUT="$ROOT/experiments/refine_7"
mkdir -p "$OUT/classes"
if [[ ! -f "$OUT/before.jar" ]]; then cp target/kart.jar "$OUT/before.jar"; fi
javac -source 1.8 -target 1.8 -cp "$OUT/before.jar" -d "$OUT/classes" \
  src/main/java/kart/cost/CompiledBenefitFeatures.java \
  src/main/java/kart/cost/OnlineBenefitModel.java \
  src/main/java/kart/cost/BenefitCalibrator.java \
  src/main/java/kart/llm/LlmOptions.java \
  src/main/java/kart/llm/OpenAiCompatibleClient.java \
  src/main/java/kart/bench/CboLlmProposalArm.java
cp "$OUT/before.jar" "$OUT/after.jar"
jar uf "$OUT/after.jar" -C "$OUT/classes" .
sha256sum "$OUT/before.jar" "$OUT/after.jar"
