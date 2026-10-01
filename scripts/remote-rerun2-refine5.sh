#!/usr/bin/env bash
set -euo pipefail
# kill stale
pkill -9 -f 'refine5-ais-e2e-rerun' 2>/dev/null || true
pkill -9 -f 'run-server-refine5-rerun' 2>/dev/null || true
pkill -9 -f 'refine5-td-e2e-rerun' 2>/dev/null || true
sleep 2
pgrep -af refine5 || echo "cleared"

# install updated rerun script
python3 - <<'PY'
from pathlib import Path
src = Path("/home/tyq/run-server-refine5-rerun-e2e.sh")
dst = Path("/home/tyq/projects/llm-kv/scripts/run-server-refine5-rerun-e2e.sh")
raw = src.read_bytes().replace(b"\r\n", b"\n").replace(b"\r", b"\n")
dst.write_bytes(raw)
print("installed", dst, len(raw))
# also refresh java sources if present
for s, d in [
  ("/home/tyq/CboLlmProposalArm.java", "/home/tyq/projects/llm-kv/src/main/java/kart/bench/CboLlmProposalArm.java"),
  ("/home/tyq/FixedPlanIdArm.java", "/home/tyq/projects/llm-kv/src/main/java/kart/bench/FixedPlanIdArm.java"),
  ("/home/tyq/eval_refine5_synthetic_decisions.py", "/home/tyq/projects/llm-kv/experiments/adapters/eval_refine5_synthetic_decisions.py"),
]:
  p = Path(s)
  if p.exists():
    Path(d).write_bytes(p.read_bytes().replace(b"\r\n", b"\n").replace(b"\r", b"\n"))
    print("wrote", d)
PY
chmod +x /home/tyq/projects/llm-kv/scripts/run-server-refine5-rerun-e2e.sh
cd /home/tyq/projects/llm-kv
rm -f /home/tyq/refine5_rerun2.log
setsid bash scripts/run-server-refine5-rerun-e2e.sh experiments/refine_5/refine5-20261001-201514 \
  </dev/null >/home/tyq/refine5_rerun2.log 2>&1 &
echo STARTED pid=$!
sleep 4
head -n 40 /home/tyq/refine5_rerun2.log
