#!/usr/bin/env bash
# FullScan oracle for cbo-llm pilot v1 (5 mechanism queries).
# Requires WSL HBase READY. Refuses overwrite if oracle_status=frozen.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck disable=SC1091
source "$ROOT/scripts/kart-env.sh"
cd "$ROOT"

WL="experiments/workloads/bound_ir_cbo_llm_pilot_v1.json"
OUT="experiments/workloads/bound_ir_cbo_llm_pilot_v1.oracle.json"
PROV="experiments/workloads/bound_ir_cbo_llm_pilot_v1.provenance.json"

if [[ ! -f "$WL" ]]; then
  echo "missing $WL" >&2
  exit 2
fi
if [[ -f "$PROV" ]] && python3 -c "import json,sys; p=json.load(open(sys.argv[1])); sys.exit(0 if p.get('oracle_status')=='frozen' else 1)" "$PROV"; then
  echo "REFUSE: provenance already oracle_status=frozen" >&2
  exit 3
fi

kart_ensure_jar
echo "=== FullScan oracle: cbo-llm pilot v1 ==="
java -Dkart.root="$ROOT" -jar "$ROOT/target/kart.jar" build-oracle-cache \
  --evaluate-workload \
  --workload-in "$WL" \
  --oracle-out "$OUT" \
  --manifest tdrive_v1_ready

python3 - <<'PY'
import hashlib, json
from pathlib import Path
root = Path(".")
out = root / "experiments/workloads/bound_ir_cbo_llm_pilot_v1.oracle.json"
prov_p = root / "experiments/workloads/bound_ir_cbo_llm_pilot_v1.provenance.json"
wl = root / "experiments/workloads/bound_ir_cbo_llm_pilot_v1.json"
ora = json.loads(out.read_text(encoding="utf-8"))
answers = {a.get("query_id") for a in ora.get("answers", []) if isinstance(a, dict)}
qids = [q["query_id"] for q in json.loads(wl.read_text(encoding="utf-8"))["queries"]]
missing = [q for q in qids if q not in answers]
prov = json.loads(prov_p.read_text(encoding="utf-8")) if prov_p.is_file() else {}
prov.update({
  "oracle_sha256": hashlib.sha256(out.read_bytes()).hexdigest(),
  "sha256": hashlib.sha256(wl.read_bytes()).hexdigest(),
  "oracle_status": "frozen" if not missing else "pending_fullscan_fill",
  "oracle_missing_query_ids": missing,
})
prov_p.write_text(json.dumps(prov, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
print("oracle_status", prov["oracle_status"], "missing", missing)
PY
