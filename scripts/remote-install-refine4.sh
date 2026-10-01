#!/usr/bin/env bash
set -euo pipefail
source ~/.kart_server_env
cd "$KART_DST"
case "$KART_DST" in
  /home/tyq/*) ;;
  *) echo "FAIL: KART_DST=$KART_DST"; exit 1 ;;
esac
python3 - <<'PY'
from pathlib import Path
pairs = [
  ("/home/tyq/BenefitCalibrator.java", "src/main/java/kart/cost/BenefitCalibrator.java"),
  ("/home/tyq/FixedPlanIdArm.java", "src/main/java/kart/bench/FixedPlanIdArm.java"),
  ("/home/tyq/CboLlmProposalArm.java", "src/main/java/kart/bench/CboLlmProposalArm.java"),
  ("/home/tyq/ArmRegistry.java", "src/main/java/kart/bench/ArmRegistry.java"),
  ("/home/tyq/SuiteRunner.java", "src/main/java/kart/bench/SuiteRunner.java"),
  ("/home/tyq/server-patch-refine4.sh", "scripts/server-patch-refine4.sh"),
  ("/home/tyq/run-server-refine4.sh", "scripts/run-server-refine4.sh"),
  ("/home/tyq/build_refine4_deliverables.py", "experiments/adapters/build_refine4_deliverables.py"),
  ("/home/tyq/e2e-ais-fixed-plan-diag.yaml", "experiments/suites/e2e-ais-fixed-plan-diag.yaml"),
]
for src, dst in pairs:
  raw = Path(src).read_bytes().replace(b"\r\n", b"\n").replace(b"\r", b"\n")
  Path(dst).parent.mkdir(parents=True, exist_ok=True)
  Path(dst).write_bytes(raw)
  print("wrote", dst, "bytes", len(raw))
PY
chmod +x scripts/server-patch-refine4.sh scripts/run-server-refine4.sh
exec bash scripts/run-server-refine4.sh
