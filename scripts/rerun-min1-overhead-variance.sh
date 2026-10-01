#!/usr/bin/env bash
set -euo pipefail
KEY="${HOME}/.ssh/id_ed25519_kart_server"
HOST=tyq@10.242.104.108
ssh -i "$KEY" -o IdentitiesOnly=yes -o BatchMode=yes "$HOST" 'bash -s' <<'REMOTE'
set -euo pipefail
source ~/.kart_server_env
cd "$KART_DST"
set -a; source .env; set +a
export LLM_PROVIDER=google
export LLM_API_KEY="$GOOGLE_API_KEY"
export LLM_BASE_URL="${GOOGLE_LLM_BASE_URL%/}"
export LLM_MODEL="$GOOGLE_LLM_MODEL"
export LLM_JSON_MODE=false
export KART_CBO_LLM_BUDGET_MS=120000
echo "model=$LLM_MODEL"
for i in 1 2; do
  RUN_ID="cbo-llm-overhead-google-r${i}-$(date +%Y%m%d-%H%M%S)"
  export RUN_ID
  ./scripts/bench-plan.sh \
    --suite experiments/suites/plan-cbo-llm-overhead-min1.yaml \
    --run-id "$RUN_ID" --trials 1 --arm cbo,cbo-llm-proposal >/tmp/oh_g_$i.log 2>&1 || {
      echo "FAIL run $i"; tail -40 /tmp/oh_g_$i.log; exit 1; }
  I="$i" python3 - <<'PY'
import json, os
from pathlib import Path
i = os.environ["I"]
out=Path("experiments/results/" + os.environ["RUN_ID"])
rows=[json.loads(l) for l in (out/"plan.jsonl").read_text().splitlines() if l.strip()]
cbo={r["query_id"]:r for r in rows if r.get("arm")=="cbo"}
hyb={r["query_id"]:r for r in rows if str(r.get("arm","")).startswith("cbo-llm-proposal")}
for qid in sorted(cbo):
  c,h=cbo[qid],hyb[qid]
  print(f"google_r{i} {qid}: cbo={c.get('t_plan_ms')} hybrid={h.get('t_plan_ms')} delta={int(h['t_plan_ms'])-int(c['t_plan_ms'])} llm_lat={h.get('llm_latency_ms')} proposal={h.get('proposal_plan_id')}")
PY
done
# also deepseek twice for fair variance
unset LLM_PROVIDER
set -a; source .env; set +a
export LLM_PROVIDER=deepseek
export KART_CBO_LLM_BUDGET_MS=120000
echo "model=$LLM_MODEL"
for i in 1 2; do
  RUN_ID="cbo-llm-overhead-deepseek-r${i}-$(date +%Y%m%d-%H%M%S)"
  export RUN_ID
  ./scripts/bench-plan.sh \
    --suite experiments/suites/plan-cbo-llm-overhead-min1.yaml \
    --run-id "$RUN_ID" --trials 1 --arm cbo,cbo-llm-proposal >/tmp/oh_d_$i.log 2>&1 || {
      echo "FAIL run $i"; tail -40 /tmp/oh_d_$i.log; exit 1; }
  I="$i" python3 - <<'PY'
import json, os
from pathlib import Path
i = os.environ["I"]
out=Path("experiments/results/" + os.environ["RUN_ID"])
rows=[json.loads(l) for l in (out/"plan.jsonl").read_text().splitlines() if l.strip()]
cbo={r["query_id"]:r for r in rows if r.get("arm")=="cbo"}
hyb={r["query_id"]:r for r in rows if str(r.get("arm","")).startswith("cbo-llm-proposal")}
for qid in sorted(cbo):
  c,h=cbo[qid],hyb[qid]
  print(f"deepseek_r{i} {qid}: cbo={c.get('t_plan_ms')} hybrid={h.get('t_plan_ms')} delta={int(h['t_plan_ms'])-int(c['t_plan_ms'])} llm_lat={h.get('llm_latency_ms')} proposal={h.get('proposal_plan_id')}")
PY
done
REMOTE
