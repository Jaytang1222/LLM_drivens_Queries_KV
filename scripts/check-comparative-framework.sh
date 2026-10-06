#!/usr/bin/env bash
# Local comparative-framework gate check (no HBase, no large bench).
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
FAIL=0
ok() { echo "PASS  $*"; }
bad() { echo "FAIL  $*"; FAIL=$((FAIL + 1)); }

need_file() {
  if [[ -f "$1" ]]; then ok "file $1"; else bad "missing $1"; fi
}

need_file experiments/suites/parse.yaml
need_file experiments/suites/plan.yaml
need_file experiments/suites/e2e.yaml
need_file experiments/suites/plan-shared-pool.yaml
need_file experiments/suites/ablation.yaml

need_file experiments/workloads/nl_ir_v2.json
need_file experiments/workloads/bound_ir_cmp_v3.json
need_file experiments/workloads/bound_ir_cmp_v3.oracle.json
need_file experiments/workloads/bound_ir_cmp_v3.provenance.json
need_file experiments/workloads/bound_ir_v1.json
need_file experiments/workloads/bound_ir_v1.oracle.json
need_file experiments/workloads/tdrive_smoke.json
need_file experiments/workloads/tdrive_smoke.oracle.json
need_file experiments/workloads/bound_ir_holdout_v2.json
need_file experiments/workloads/bound_ir_holdout_v2.provenance.json
need_file experiments/workloads/bound_ir_holdout_v2.splits.json
need_file experiments/workloads/bound_ir_holdout_v2_train.json
need_file experiments/workloads/bound_ir_holdout_v2_train.oracle.json
need_file experiments/workloads/bound_ir_holdout_v2_val.json
need_file experiments/workloads/bound_ir_holdout_v2_val.oracle.json
need_file experiments/workloads/bound_ir_holdout_v2_test.json
need_file experiments/workloads/bound_ir_holdout_v2_test.oracle.json

need_file experiments/adapters/llm_client.py
need_file experiments/adapters/TRANSPLANT.md
need_file experiments/adapters/din/din_sql_bridge.py
need_file experiments/adapters/sag/sag_mql_bridge.py
need_file experiments/adapters/direct/direct_draft_bridge.py
need_file experiments/adapters/translate/sql_to_draft_ir.py
need_file experiments/adapters/translate/mql_to_draft_ir.py
need_file experiments/adapters/bao/kart_plan_family_weights.json
need_file experiments/adapters/llmopt/llmopt_bridge.py

if grep -q 'sag-mql-nofeedback' experiments/suites/parse.yaml \
  && ! grep -E '^\s*-\s*sag-mql\s*$' experiments/suites/parse.yaml >/dev/null; then
  ok "parse.yaml defaults to sag-mql-nofeedback (no bare sag-mql arm)"
else
  bad "parse.yaml must default sag-mql-nofeedback without enabling sag-mql"
fi

for suite in experiments/suites/plan.yaml experiments/suites/plan-shared-pool.yaml experiments/suites/e2e.yaml; do
  if grep -q 'workload: experiments/workloads/bound_ir_cmp_v3.json' "$suite" \
    && grep -q 'oracle: experiments/workloads/bound_ir_cmp_v3.oracle.json' "$suite"; then
    ok "$suite defaults to bound_ir_cmp_v3"
  else
    bad "$suite must default to bound_ir_cmp_v3 workload/oracle"
  fi
done
if grep -q 'workload: experiments/workloads/nl_ir_v2.json' experiments/suites/parse.yaml; then
  ok "parse.yaml uses nl_ir_v2"
else
  bad "parse.yaml must use nl_ir_v2"
fi
if grep -q 'workload: experiments/workloads/bound_ir_cmp_v3.json' experiments/suites/ablation.yaml \
  && grep -q 'oracle: experiments/workloads/bound_ir_cmp_v3.oracle.json' experiments/suites/ablation.yaml; then
  ok "ablation.yaml uses bound_ir_cmp_v3"
else
  bad "ablation.yaml must use bound_ir_cmp_v3"
fi

python3 - <<'PY' || bad "cmp_v3 / nl_v2 sizes"
import json
from pathlib import Path
wl = json.loads(Path("experiments/workloads/bound_ir_cmp_v3.json").read_text(encoding="utf-8"))
ora = json.loads(Path("experiments/workloads/bound_ir_cmp_v3.oracle.json").read_text(encoding="utf-8"))
nl = json.loads(Path("experiments/workloads/nl_ir_v2.json").read_text(encoding="utf-8"))
ids = [q["query_id"] for q in wl["queries"]]
ans = {r["query_id"] for r in ora["answers"]}
assert len(ids) == len(set(ids)) >= 20, len(ids)
assert set(ids) == ans
assert wl.get("evaluation_role") in ("kart_operating_region", "kart_rewrite_ge10_vs_cbo")
assert len(nl["queries"]) >= 70
print("PASS  cmp_v3 n=%d nl_v2 n=%d" % (len(ids), len(nl["queries"])))
PY

python3 - <<'PY' || bad "holdout v2 integrity"
import hashlib, json
from pathlib import Path
wl = json.loads(Path("experiments/workloads/bound_ir_holdout_v2.json").read_text(encoding="utf-8"))
prov = json.loads(Path("experiments/workloads/bound_ir_holdout_v2.provenance.json").read_text(encoding="utf-8"))
split_meta = json.loads(Path("experiments/workloads/bound_ir_holdout_v2.splits.json").read_text(encoding="utf-8"))
assert prov.get("oracle_status") == "frozen"
assert split_meta.get("oracle_status") == "frozen"
for split, info in split_meta["splits"].items():
    workload_path = Path(info["path"])
    oracle_path = Path(prov["oracle_splits"][split]["oracle_path"])
    workload = json.loads(workload_path.read_text(encoding="utf-8"))
    oracle = json.loads(oracle_path.read_text(encoding="utf-8"))
    assert len(workload["queries"]) == len(oracle["answers"]) == info["n"]
    assert {q["query_id"] for q in workload["queries"]} == {a["query_id"] for a in oracle["answers"]}
    assert hashlib.sha256(oracle_path.read_bytes()).hexdigest() == info["oracle_sha256"]
print("PASS  holdout v2 frozen splits")
PY

if [[ "$FAIL" -gt 0 ]]; then
  echo "=== check-comparative-framework: FAILED ($FAIL) ==="
  exit 1
fi
echo "=== check-comparative-framework: OK (runtime essentials) ==="
