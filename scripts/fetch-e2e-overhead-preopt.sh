#!/usr/bin/env bash
set -euo pipefail
KEY="${HOME}/.ssh/id_ed25519_kart_server"
ssh -i "$KEY" -o IdentitiesOnly=yes -o BatchMode=yes tyq@10.242.104.108 'bash -s' <<'REMOTE'
set -euo pipefail
cd ~/projects/llm-kv
python3 - <<'PY'
import json
from pathlib import Path

rows = [json.loads(l) for l in Path("experiments/results/cbo-llm-overhead-e2e-v1-20260929-131347/e2e.jsonl").read_text().splitlines() if l.strip()]
print("=== PRE-OPT E2E cbo-llm-overhead-e2e-v1-20260929-131347 ===")
print("query\tarm\tt_plan\tt_exec\tt_e2e\tllm_lat\tplan_id")
qs = ["a2_topk_dtw_wide", "a2_tz_hard_a", "a2_tz_hard_d", "topk_st_3"]
for qid in qs:
  for arm in ["cbo", "cbo-llm-proposal"]:
    cands = [r for r in rows if r.get("query_id")==qid and (r.get("arm") or "").startswith(arm) and (arm!="cbo" or r.get("arm")=="cbo")]
    # refine
    if arm == "cbo":
      cands = [r for r in rows if r.get("query_id")==qid and r.get("arm")=="cbo"]
    else:
      cands = [r for r in rows if r.get("query_id")==qid and str(r.get("arm","")).startswith("cbo-llm-proposal")]
    for r in cands:
      print(f"{qid}\t{r.get('arm')}\t{r.get('t_plan_ms')}\t{r.get('t_exec_ms')}\t{r.get('t_e2e_ms')}\t{r.get('llm_latency_ms')}\t{r.get('selected_plan_id') or r.get('plan_id')}")
PY
REMOTE
