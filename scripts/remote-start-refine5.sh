#!/usr/bin/env bash
set -euo pipefail
source ~/.kart_server_env
cd "$KART_DST"
python3 - <<'PY'
from pathlib import Path
pairs = [
  ("/home/tyq/BenefitCalibrator.java", "src/main/java/kart/cost/BenefitCalibrator.java"),
  ("/home/tyq/FixedPlanIdArm.java", "src/main/java/kart/bench/FixedPlanIdArm.java"),
  ("/home/tyq/CboLlmProposalArm.java", "src/main/java/kart/bench/CboLlmProposalArm.java"),
  ("/home/tyq/ArmRegistry.java", "src/main/java/kart/bench/ArmRegistry.java"),
  ("/home/tyq/SuiteRunner.java", "src/main/java/kart/bench/SuiteRunner.java"),
  ("/home/tyq/server-patch-refine5.sh", "scripts/server-patch-refine5.sh"),
  ("/home/tyq/run-server-refine5.sh", "scripts/run-server-refine5.sh"),
  ("/home/tyq/eval_refine5_synthetic_decisions.py", "experiments/adapters/eval_refine5_synthetic_decisions.py"),
  ("/home/tyq/eval_refine5_saving_residual.py", "experiments/adapters/eval_refine5_saving_residual.py"),
  ("/home/tyq/build_refine5_deliverables.py", "experiments/adapters/build_refine5_deliverables.py"),
  ("/home/tyq/e2e-ais-fixed-plan-diag.yaml", "experiments/suites/e2e-ais-fixed-plan-diag.yaml"),
]
for src, dst in pairs:
  p = Path(src)
  if not p.exists():
    print("skip missing", src)
    continue
  raw = p.read_bytes().replace(b"\r\n", b"\n").replace(b"\r", b"\n")
  Path(dst).parent.mkdir(parents=True, exist_ok=True)
  Path(dst).write_bytes(raw)
  print("wrote", dst, "bytes", len(raw))
PY
chmod +x scripts/server-patch-refine5.sh scripts/run-server-refine5.sh
pkill -f 'run-server-refine5.sh' 2>/dev/null || true
sleep 1
rm -f /home/tyq/refine5_run.log
setsid bash scripts/run-server-refine5.sh </dev/null >/home/tyq/refine5_run.log 2>&1 &
echo "STARTED pid=$!"
sleep 2
head -n 25 /home/tyq/refine5_run.log || true
