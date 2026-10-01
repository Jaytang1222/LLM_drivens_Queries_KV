#!/usr/bin/env bash
set -euo pipefail
KEY="${HOME}/.ssh/id_ed25519_kart_server"
ssh -i "$KEY" -o IdentitiesOnly=yes -o BatchMode=yes tyq@10.242.104.108 'bash -s' <<'REMOTE'
set -euo pipefail
cd ~/projects/llm-kv
python3 - <<'PY'
import json
from pathlib import Path
paths = sorted(Path("experiments/results").glob("cbo-llm-overhead-*"), key=lambda x: x.stat().st_mtime)[-10:]
for p in paths:
  plan = p / "plan.jsonl"
  if not plan.exists():
    continue
  rows = [json.loads(l) for l in plan.read_text().splitlines() if l.strip()]
  hs = [r for r in rows if str(r.get("arm", "")).startswith("cbo-llm")]
  cs = [r for r in rows if r.get("arm") == "cbo"]
  if not hs or not cs:
    continue
  h, c = hs[0], cs[0]
  print(
    p.name,
    "cbo", c.get("t_plan_ms"),
    "hyb", h.get("t_plan_ms"),
    "delta", int(h["t_plan_ms"]) - int(c["t_plan_ms"]),
    "llm", h.get("llm_latency_ms"),
  )
PY
REMOTE
