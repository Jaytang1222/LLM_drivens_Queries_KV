#!/usr/bin/env bash
# Force-sweep diverse rewrite programs on a mine suite; emit merged paired jsonl for admit.
# Usage: bash scripts/force-mine-diversity.sh [--suite PATH] [--run-id ID] [--cache cold|warm]
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck disable=SC1091
source "$ROOT/scripts/kart-env.sh"
SUITE="${SUITE:-experiments/suites/e2e-mine-newidx.yaml}"
RUN_ID="${RUN_ID:-force-mine-$(date +%Y%m%d-%H%M%S)}"
CACHE="${CACHE:-warm}"
TRIALS="${TRIALS:-1}"
OUT_DIR="$ROOT/experiments/results/$RUN_ID"
mkdir -p "$OUT_DIR"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --suite) SUITE="$2"; shift 2 ;;
    --run-id) RUN_ID="$2"; OUT_DIR="$ROOT/experiments/results/$RUN_ID"; mkdir -p "$OUT_DIR"; shift 2 ;;
    --cache) CACHE="$2"; shift 2 ;;
    --trials) TRIALS="$2"; shift 2 ;;
    *) echo "unknown arg $1" >&2; exit 2 ;;
  esac
done

# Diverse programs (canonical slots). Names are run suffixes.
PROGS=(
  'drop_spatial|{"access":"drop_spatial_branch"}'
  'drop_temporal|{"access":"drop_temporal_branch"}'
  'b2000|{"fetch_batch":"b2000"}'
  'r256|{"range_budget":"r256"}'
  'mp|{"meta_probe":"on"}'
  'drop_spatial_mp|{"access":"drop_spatial_branch","meta_probe":"on"}'
  'drop_spatial_b2000_mp|{"access":"drop_spatial_branch","fetch_batch":"b2000","meta_probe":"on"}'
  'r256_b2000|{"range_budget":"r256","fetch_batch":"b2000"}'
  'lb8|{"topk":"lb8"}'
  'lb8_b2000|{"topk":"lb8","fetch_batch":"b2000"}'
  'lb8_r256|{"topk":"lb8","range_budget":"r256"}'
  'drop_temporal_b2000|{"access":"drop_temporal_branch","fetch_batch":"b2000"}'
)

echo "[force-mine] suite=$SUITE run_id=$RUN_ID cache=$CACHE"
echo "[force-mine] step1: cbo baseline"
export KART_ACTION_MODE=dual-rules
unset KART_ACTION_FORCE || true
bash "$ROOT/scripts/bench-e2e.sh" --suite "$SUITE" --run-id "${RUN_ID}-cbo" \
  --arm cbo --cache "$CACHE" --trials "$TRIALS" || true
cp -f "$ROOT/experiments/results/${RUN_ID}-cbo/e2e.jsonl" "$OUT_DIR/cbo.jsonl"

MERGED="$OUT_DIR/paired.jsonl"
: > "$MERGED"
cat "$OUT_DIR/cbo.jsonl" >> "$MERGED"

for entry in "${PROGS[@]}"; do
  name="${entry%%|*}"
  force="${entry#*|}"
  echo "[force-mine] force=$name $force"
  export KART_ACTION_MODE=force
  export KART_ACTION_FORCE="$force"
  bash "$ROOT/scripts/bench-e2e.sh" --suite "$SUITE" --run-id "${RUN_ID}-$name" \
    --arm kart --cache "$CACHE" --trials "$TRIALS" || true
  src="$ROOT/experiments/results/${RUN_ID}-$name/e2e.jsonl"
  if [[ -f "$src" ]]; then
    # tag program name into a sidecar line field via python rewrite
    python3 - "$src" "$MERGED" "$name" "$force" <<'PY'
import json,sys
src, merged, name, force = sys.argv[1:5]
prog=json.loads(force)
with open(merged,"a",encoding="utf-8") as out:
  for line in open(src,encoding="utf-8"):
    if not line.strip(): continue
    r=json.loads(line)
    if r.get("arm")=="kart":
      r["force_tag"]=name
      if not r.get("program"):
        r["program"]=prog
      if not r.get("llm_program"):
        r["llm_program"]=prog
      r["final_selection_source"]=r.get("final_selection_source") or "force"
      r["gate_decision"]=r.get("gate_decision") or "FORCE"
    out.write(json.dumps(r,ensure_ascii=False)+"\n")
PY
  fi
done

python3 - "$MERGED" "$OUT_DIR/mine_summary.json" <<'PY'
import json,sys
from collections import defaultdict,Counter
path, outp = sys.argv[1:3]
rows=[json.loads(l) for l in open(path,encoding="utf-8") if l.strip()]
by=defaultdict(dict)
for r in rows:
  arm=r.get("arm"); q=r.get("query_id")
  if arm=="cbo":
    by[q]["cbo"]=r
  elif arm=="kart":
    by[q].setdefault("kart",[]).append(r)
wins=Counter(); n_pair=0; best_for={}
for q,d in by.items():
  c=d.get("cbo")
  if not c or c.get("t_e2e_ms") is None: continue
  best=None
  for k in d.get("kart") or []:
    if k.get("t_e2e_ms") is None: continue
    n_pair+=1
    sav=c["t_e2e_ms"]-k["t_e2e_ms"]
    tag=k.get("force_tag") or "?"
    if sav>=8:
      wins[tag]+=1
    if best is None or k["t_e2e_ms"]<best[1]:
      best=(tag,k["t_e2e_ms"],sav,k.get("program"))
  if best: best_for[q]=best
summary={"paired_kart_rows":n_pair,"wins_ge_8ms_by_tag":dict(wins),
         "queries_with_best":len(best_for),
         "best_tag_hist":dict(Counter(v[0] for v in best_for.values()))}
open(outp,"w",encoding="utf-8").write(json.dumps(summary,indent=2)+"\n")
print(json.dumps(summary,indent=2))
PY

echo "[force-mine] merged=$MERGED"
echo "[force-mine] done run_id=$RUN_ID"
