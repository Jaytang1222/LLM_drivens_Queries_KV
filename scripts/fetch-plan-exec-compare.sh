#!/usr/bin/env bash
set -euo pipefail
KEY="${HOME}/.ssh/id_ed25519_kart_server"
ssh -i "$KEY" -o IdentitiesOnly=yes -o BatchMode=yes tyq@10.242.104.108 'bash -s' <<'REMOTE'
set -euo pipefail
cd ~/projects/llm-kv
python3 - <<'PY'
import json
from pathlib import Path

def load(run):
  p = Path("experiments/results") / run / "plan.jsonl"
  e2e = Path("experiments/results") / run / "e2e.jsonl"
  rows = []
  if p.exists():
    rows += [json.loads(l) for l in p.read_text().splitlines() if l.strip()]
  if e2e.exists():
    rows += [json.loads(l) for l in e2e.read_text().splitlines() if l.strip()]
  return rows

def summarize(label, rows, arm_pred, trial=None):
  print(f"\n=== {label} ===")
  qs = ["a2_topk_dtw_wide", "a2_tz_hard_a", "a2_tz_hard_d", "topk_st_3"]
  print("query\tt_plan\tt_exec\tt_e2e\tllm_lat\tcache_hit\tcalls")
  for qid in qs:
    cands = [r for r in rows if r.get("query_id")==qid and arm_pred(r.get("arm") or "")]
    if trial is not None:
      cands = [r for r in cands if int(r.get("trial") or 0)==trial]
    if not cands:
      print(f"{qid}\tMISSING")
      continue
    r = cands[0]
    print(f"{qid}\t{r.get('t_plan_ms')}\t{r.get('t_exec_ms')}\t{r.get('t_e2e_ms')}\t{r.get('llm_latency_ms')}\t{r.get('cache_hit')}\t{r.get('llm_calls')}")

# baseline plan-only
base = load("cbo-llm-overhead-v1-20260929-120807")
print("BASELINE run rows", len(base), "keys sample", list(base[0].keys())[:15] if base else None)
summarize("BASELINE cbo", base, lambda a: a=="cbo")
summarize("BASELINE cbo-llm-proposal cold", base, lambda a: a.startswith("cbo-llm-proposal") and "cached" not in a)

# cache compare
cur = load("cbo-llm-overhead-cache-v1-20260929-153931")
print("\nCACHE run rows", len(cur))
summarize("NOW cbo trial1", cur, lambda a: a=="cbo", trial=1)
summarize("NOW cbo-llm-proposal cold trial1", cur, lambda a: a=="cbo-llm-proposal", trial=1)
summarize("NOW cbo-llm-proposal-cached MISS trial1", cur, lambda a: a=="cbo-llm-proposal-cached", trial=1)
summarize("NOW cbo-llm-proposal-cached HIT trial2", cur, lambda a: a=="cbo-llm-proposal-cached", trial=2)

# look for any e2e overhead on these 4
print("\n=== scanning recent e2e/overhead runs for these queries ===")
root = Path("experiments/results")
for d in sorted(root.glob("cbo-llm-overhead*"), key=lambda x: x.stat().st_mtime)[-12:]:
  for name in ("e2e.jsonl", "plan.jsonl"):
    f = d/name
    if not f.exists():
      continue
    rows = [json.loads(l) for l in f.read_text().splitlines() if l.strip()]
    has_exec = any(r.get("t_exec_ms") is not None for r in rows)
    qids = sorted({r.get("query_id") for r in rows})
    print(d.name, name, "n=", len(rows), "has_exec=", has_exec, "qids=", qids[:6])
PY
REMOTE
