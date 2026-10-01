#!/usr/bin/env bash
set -euo pipefail
source ~/.kart_server_env
cd "$KART_DST"
python3 - <<'PY'
from pathlib import Path
pairs = [
  ("/home/tyq/eval_refine6_feedback_calibration.py", "experiments/adapters/eval_refine6_feedback_calibration.py"),
  ("/home/tyq/rerun-server-refine6-e2e.sh", "scripts/rerun-server-refine6-e2e.sh"),
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
chmod +x scripts/rerun-server-refine6-e2e.sh
pkill -f 'run-server-refine6|rerun-server-refine6' 2>/dev/null || true
sleep 1
rm -f /home/tyq/refine6_rerun.log
setsid bash scripts/rerun-server-refine6-e2e.sh </dev/null >/home/tyq/refine6_rerun.log 2>&1 &
echo "STARTED pid=$!"
sleep 3
head -n 30 /home/tyq/refine6_rerun.log || true
