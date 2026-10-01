#!/usr/bin/env bash
# Push refine_6 sources to kart-lab (/home/tyq) and start comparative_refine_6.
set -euo pipefail
KEY="${HOME}/.ssh/id_ed25519_kart_server"
HOST=tyq@10.242.104.108
SSH_OPTS=(-i "$KEY" -o IdentitiesOnly=yes -o BatchMode=yes -o ProxyJump=dorm-jump)
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
if [[ -d /mnt/f/Projects/LLM_KV ]]; then
  ROOT=/mnt/f/Projects/LLM_KV
elif [[ -d /mnt/f/code/InventoryApp ]]; then
  ROOT=/mnt/f/code/InventoryApp
fi

FILES=(
  src/main/java/kart/probe/JointCandidateProbe.java
  src/main/java/kart/cost/BenefitCalibrator.java
  src/main/java/kart/bench/CboLlmProposalArm.java
  src/main/java/kart/bench/FixedPlanIdArm.java
  src/main/java/kart/bench/ArmRegistry.java
  src/main/java/kart/bench/SuiteRunner.java
  scripts/server-patch-refine6.sh
  scripts/run-server-refine6.sh
  scripts/remote-start-refine6.sh
  experiments/adapters/eval_refine6_feedback_calibration.py
  experiments/adapters/eval_refine6_action_synth.py
  experiments/adapters/build_refine6_deliverables.py
)

python3 - <<PY
from pathlib import Path
root = Path(r"$ROOT")
for rel in """${FILES[@]}""".split():
    p = root / rel
    if p.exists():
        p.write_bytes(p.read_bytes().replace(b"\r\n", b"\n").replace(b"\r", b"\n"))
        print("lf", rel)
    else:
        raise SystemExit("missing " + rel)
PY

scp "${SSH_OPTS[@]}" \
  "$ROOT/src/main/java/kart/probe/JointCandidateProbe.java" \
  "$ROOT/src/main/java/kart/cost/BenefitCalibrator.java" \
  "$ROOT/src/main/java/kart/bench/CboLlmProposalArm.java" \
  "$ROOT/src/main/java/kart/bench/FixedPlanIdArm.java" \
  "$ROOT/src/main/java/kart/bench/ArmRegistry.java" \
  "$ROOT/src/main/java/kart/bench/SuiteRunner.java" \
  "$ROOT/scripts/server-patch-refine6.sh" \
  "$ROOT/scripts/run-server-refine6.sh" \
  "$ROOT/scripts/remote-start-refine6.sh" \
  "$ROOT/experiments/adapters/eval_refine6_feedback_calibration.py" \
  "$ROOT/experiments/adapters/eval_refine6_action_synth.py" \
  "$ROOT/experiments/adapters/build_refine6_deliverables.py" \
  "$HOST:/home/tyq/"

ssh "${SSH_OPTS[@]}" "$HOST" 'bash /home/tyq/remote-start-refine6.sh'
echo "STARTED_REMOTE refine6; tail /home/tyq/refine6_run.log on server"
