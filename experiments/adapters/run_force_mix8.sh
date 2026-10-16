#!/usr/bin/env bash
set -euo pipefail
source ~/.kart_server_env
cd "$KART_DST"
export KART_ADVANCED_INDEXES=1
export KART_ACTION_MODE=dual-rules
unset KART_ACTION_FORCE || true
RUN=adv-mix-force8-$(date +%Y%m%d-%H%M%S)
OUT=experiments/results/$RUN
mkdir -p "$OUT"
PLANS=(P_FULL P_Z P_TZ P_H P_XZ P_TS P_XZT P_XZTXZ)
echo "run=$RUN" | tee "$OUT/force.log"
for pid in "${PLANS[@]}"; do
  echo "[force] $pid $(date -Is)" | tee -a "$OUT/force.log"
  export KART_FIXED_PLAN_ID="$pid"
  bash scripts/bench-e2e.sh --suite experiments/suites/e2e-adv-mix-force.yaml \
    --run-id "${RUN}-${pid}" --cache warm \
    >>"$OUT/force.log" 2>&1 || echo "FAIL $pid" | tee -a "$OUT/force.log"
done
python3 - "$OUT" "$RUN" <<'PY'
import json,sys,csv
from pathlib import Path
from collections import defaultdict
out=Path(sys.argv[1]); run=sys.argv[2]
plans=["P_FULL","P_Z","P_TZ","P_H","P_XZ","P_TS","P_XZT","P_XZTXZ"]
rows=[]
for pid in plans:
  p=out.parent/f"{run}-{pid}"/"e2e.jsonl"
  if not p.exists():
    print("missing",p); continue
  for line in p.read_text().splitlines():
    if not line.strip(): continue
    r=json.loads(line)
    err=r.get("error")
    ok = err in (None, "", "missing oracle answer")
    rows.append({
      "qid": r.get("query_id"),
      "plan": pid,
      "t_plan": r.get("t_plan_ms"),
      "t_exec": r.get("t_exec_ms"),
      "status": "OK" if ok else "ERR",
      "postings": r.get("index_rows"),
      "candidates": r.get("fetch_chunks"),
      "fetch_ms": None,
      "index_ms": None,
      "sel": r.get("plan_id"),
      "error": err,
      "t_e2e": r.get("t_e2e_ms"),
    })
tsv=out/"force_mix8.tsv"
with tsv.open("w",newline="",encoding="utf-8") as f:
  w=csv.DictWriter(f,fieldnames=["qid","plan","t_plan","t_exec","status","postings","candidates","fetch_ms","index_ms","sel","error","t_e2e"],delimiter="\t")
  w.writeheader(); w.writerows(rows)
print("wrote",tsv,"n=",len(rows))
by=defaultdict(list)
for r in rows:
  if r["t_exec"] is None: continue
  by[r["qid"]].append(r)
for q in sorted(by):
  rs=sorted(by[q], key=lambda x: x["t_exec"])
  top=", ".join(f"{x['plan']}:{x['t_exec']}" for x in rs[:4])
  print(f"{q}: BEST={rs[0]['plan']} {rs[0]['t_exec']} | {top}")
PY
echo DONE_FORCE $(date -Is) | tee -a "$OUT/force.log"
echo "$RUN" > /tmp/last_mix_force_run.txt
