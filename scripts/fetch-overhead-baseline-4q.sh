#!/usr/bin/env bash
# Fetch baseline 4-query overhead numbers from server run cbo-llm-overhead-v1-20260929-120807
set -euo pipefail
KEY="${HOME}/.ssh/id_ed25519_kart_server"
HOST=tyq@10.242.104.108
ssh -i "$KEY" -o IdentitiesOnly=yes -o BatchMode=yes "$HOST" 'bash -s' <<'REMOTE'
set -euo pipefail
cd ~/projects/llm-kv
RUN=experiments/results/cbo-llm-overhead-v1-20260929-120807
python3 - <<PY
import json
from pathlib import Path
from statistics import median
out = Path("$RUN")
rows = [json.loads(l) for l in (out/"plan.jsonl").read_text(encoding="utf-8").splitlines() if l.strip()]
cbo = {r["query_id"]: r for r in rows if r.get("arm") == "cbo"}
hyb = {r["query_id"]: r for r in rows if (r.get("arm") or "").startswith("cbo-llm-proposal")}
print("run", out.name)
print("model_hint", (hyb[next(iter(hyb))].get("llm_model") if hyb else None))
deltas=[]
lats=[]
for qid in sorted(cbo):
  c,h=cbo[qid],hyb[qid]
  ct,ht=c.get("t_plan_ms"),h.get("t_plan_ms")
  d=None if ct is None or ht is None else int(ht)-int(ct)
  lat=h.get("llm_latency_ms")
  if d is not None: deltas.append(d)
  if lat is not None: lats.append(int(lat))
  print(f"{qid}\tcbo={ct}\thybrid={ht}\tdelta={d}\tllm_lat={lat}\ttrig={h.get('llm_triggered')}\tcache={h.get('cache_hit')}\tcalls={h.get('llm_calls')}\tproposal={h.get('proposal_plan_id')}")
print("delta_median", median(deltas) if deltas else None, "delta_mean", round(sum(deltas)/len(deltas),1) if deltas else None)
print("llm_lat_median", median(lats) if lats else None, "llm_lat_mean", round(sum(lats)/len(lats),1) if lats else None)
PY
REMOTE
