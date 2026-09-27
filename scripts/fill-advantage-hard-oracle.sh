#!/usr/bin/env bash
# FullScan oracle for advantage hard v2 subset (28 queries).
# Requires WSL HBase READY. Does NOT overwrite if oracle already frozen.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck disable=SC1091
source "$ROOT/scripts/kart-env.sh"
cd "$ROOT"

WL="experiments/workloads/bound_ir_advantage_hard_v2.json"
OUT="experiments/workloads/bound_ir_advantage_hard_v2.oracle.json"
PROV="experiments/workloads/bound_ir_advantage_hard_v2.provenance.json"

if [[ ! -f "$WL" ]]; then
  echo "missing $WL — run: python3 experiments/adapters/generate_advantage_hard_v2.py" >&2
  exit 2
fi
if [[ -f "$OUT" ]]; then
  echo "REFUSE: $OUT exists; delete only if intentionally regenerating." >&2
  exit 3
fi
if [[ -f "$PROV" ]] && python3 -c "import json,sys; p=json.load(open(sys.argv[1])); sys.exit(0 if p.get('oracle_status')=='frozen' else 1)" "$PROV"; then
  echo "REFUSE: provenance already oracle_status=frozen" >&2
  exit 3
fi

kart_ensure_jar

echo "=== FullScan oracle: advantage hard v2 ==="
java -Dkart.root="$ROOT" -jar "$ROOT/target/kart.jar" build-oracle-cache \
  --evaluate-workload \
  --workload-in "$WL" \
  --oracle-out "$OUT" \
  --manifest tdrive_v1_ready

sha=$(sha256sum "$OUT" | awk '{print $1}')
echo "oracle sha256=$sha path=$OUT"

python3 - <<'PY'
import json
from pathlib import Path
wl = json.loads(Path("experiments/workloads/bound_ir_advantage_hard_v2.json").read_text(encoding="utf-8"))
ora = json.loads(Path("experiments/workloads/bound_ir_advantage_hard_v2.oracle.json").read_text(encoding="utf-8"))
ids = [q["query_id"] for q in wl["queries"]]
ans = {r["query_id"]: r for r in ora["answers"]}
missing = [i for i in ids if i not in ans]
empty = [i for i in ids if not (ans[i].get("trajectory_ids") or ans[i].get("ids") or [])]
if missing:
    raise SystemExit("oracle missing: " + ", ".join(missing))
if empty:
    raise SystemExit("empty answers (fail-closed): " + ", ".join(empty))
print("PASS hard oracle n=%d all non-empty" % len(ids))
PY
