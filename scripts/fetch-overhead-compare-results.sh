#!/usr/bin/env bash
set -euo pipefail
KEY="${HOME}/.ssh/id_ed25519_kart_server"
HOST=tyq@10.242.104.108
ssh -i "$KEY" -o IdentitiesOnly=yes -o BatchMode=yes "$HOST" 'bash -s' <<'REMOTE'
set -euo pipefail
source ~/.kart_server_env
cd "$KART_DST"
echo "=== latest overhead runs ==="
ls -1dt experiments/results/cbo-llm-overhead-* 2>/dev/null | head -5
python3 - <<'PY'
import json
from pathlib import Path
root = Path("experiments/results")
runs = sorted(root.glob("cbo-llm-overhead-*"), key=lambda p: p.stat().st_mtime, reverse=True)[:4]
for out in reversed(runs):
  plan = out / "plan.jsonl"
  if not plan.exists():
    continue
  rows = [json.loads(l) for l in plan.read_text(encoding="utf-8").splitlines() if l.strip()]
  cbo = {r["query_id"]: r for r in rows if r.get("arm") == "cbo"}
  hyb = {r["query_id"]: r for r in rows if (r.get("arm") or "").startswith("cbo-llm-proposal")}
  print(f"\nRUN {out.name}")
  for qid in sorted(cbo):
    c, h = cbo[qid], hyb.get(qid, {})
    ct, ht = c.get("t_plan_ms"), h.get("t_plan_ms")
    delta = None if ct is None or ht is None else int(ht) - int(ct)
    print(
      f"  {qid}: cbo={ct} hybrid={ht} delta={delta} "
      f"llm_lat={h.get('llm_latency_ms')} trig={h.get('llm_triggered')} "
      f"fb={h.get('fallback_reason')} proposal={h.get('proposal_plan_id')}"
    )
PY
REMOTE
