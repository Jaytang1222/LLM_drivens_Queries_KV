#!/usr/bin/env bash
# Push refine_4 sources to kart-lab (/home/tyq) and run comparative_refine_4.
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
  src/main/java/kart/cost/BenefitCalibrator.java
  src/main/java/kart/bench/FixedPlanIdArm.java
  src/main/java/kart/bench/CboLlmProposalArm.java
  src/main/java/kart/bench/ArmRegistry.java
  src/main/java/kart/bench/SuiteRunner.java
  scripts/server-patch-refine4.sh
  scripts/run-server-refine4.sh
  experiments/adapters/build_refine4_deliverables.py
  experiments/suites/e2e-ais-fixed-plan-diag.yaml
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

# scp flat into /home/tyq then relocate on server (avoid deep mkdir over scp)
scp "${SSH_OPTS[@]}" \
  "$ROOT/src/main/java/kart/cost/BenefitCalibrator.java" \
  "$ROOT/src/main/java/kart/bench/FixedPlanIdArm.java" \
  "$ROOT/src/main/java/kart/bench/CboLlmProposalArm.java" \
  "$ROOT/src/main/java/kart/bench/ArmRegistry.java" \
  "$ROOT/src/main/java/kart/bench/SuiteRunner.java" \
  "$ROOT/scripts/server-patch-refine4.sh" \
  "$ROOT/scripts/run-server-refine4.sh" \
  "$ROOT/experiments/adapters/build_refine4_deliverables.py" \
  "$ROOT/experiments/suites/e2e-ais-fixed-plan-diag.yaml" \
  "$HOST:/home/tyq/"

ssh "${SSH_OPTS[@]}" "$HOST" 'bash -s' <<'REMOTE'
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
bash scripts/run-server-refine4.sh
REMOTE
