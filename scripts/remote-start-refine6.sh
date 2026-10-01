#!/usr/bin/env bash
set -euo pipefail
source ~/.kart_server_env
cd "$KART_DST"
python3 - <<'PY'
from pathlib import Path
pairs = [
  ("/home/tyq/JointCandidateProbe.java", "src/main/java/kart/probe/JointCandidateProbe.java"),
  ("/home/tyq/BenefitCalibrator.java", "src/main/java/kart/cost/BenefitCalibrator.java"),
  ("/home/tyq/CboLlmProposalArm.java", "src/main/java/kart/bench/CboLlmProposalArm.java"),
  ("/home/tyq/FixedPlanIdArm.java", "src/main/java/kart/bench/FixedPlanIdArm.java"),
  ("/home/tyq/ArmRegistry.java", "src/main/java/kart/bench/ArmRegistry.java"),
  ("/home/tyq/SuiteRunner.java", "src/main/java/kart/bench/SuiteRunner.java"),
  ("/home/tyq/server-patch-refine6.sh", "scripts/server-patch-refine6.sh"),
  ("/home/tyq/run-server-refine6.sh", "scripts/run-server-refine6.sh"),
  ("/home/tyq/eval_refine6_feedback_calibration.py", "experiments/adapters/eval_refine6_feedback_calibration.py"),
  ("/home/tyq/eval_refine6_action_synth.py", "experiments/adapters/eval_refine6_action_synth.py"),
  ("/home/tyq/build_refine6_deliverables.py", "experiments/adapters/build_refine6_deliverables.py"),
]
for src, dst in pairs:
  p = Path(src)
  if not p.exists():
    print("skip", src)
    continue
  raw = p.read_bytes().replace(b"\r\n", b"\n").replace(b"\r", b"\n")
  Path(dst).parent.mkdir(parents=True, exist_ok=True)
  Path(dst).write_bytes(raw)
  print("wrote", dst, len(raw))
PY
chmod +x scripts/server-patch-refine6.sh scripts/run-server-refine6.sh
pkill -f 'run-server-refine6.sh' 2>/dev/null || true
sleep 1
rm -f /home/tyq/refine6_run.log
setsid bash scripts/run-server-refine6.sh </dev/null >/home/tyq/refine6_run.log 2>&1 &
echo "STARTED pid=$!"
sleep 3
head -n 30 /home/tyq/refine6_run.log || true
