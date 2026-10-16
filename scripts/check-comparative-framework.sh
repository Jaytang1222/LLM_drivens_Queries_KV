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
need_file experiments/workloads/nl_ir_v3.json
need_file experiments/workloads/nl_ir_v4.json
need_file experiments/workloads/bound_ir_e1_v4.json
need_file experiments/workloads/bound_ir_e1_v4.oracle.json
need_file experiments/workloads/bound_ir_poi_e1.json
need_file experiments/workloads/bound_ir_poi_e1.oracle.json
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

# Formal E2/E3/ablation/param canonical = simple_v2 / complex_v2. v1 / cmp_v3 = regression only.
for suite in experiments/suites/plan.yaml experiments/suites/e2e.yaml experiments/suites/plan-shared-pool.yaml experiments/suites/ablation.yaml; do
  if grep -q 'workload: experiments/workloads/bound_ir_complex_v2.json' "$suite" \
    && grep -q 'oracle: experiments/workloads/bound_ir_complex_v2.oracle.json' "$suite"; then
    ok "$suite defaults to bound_ir_complex_v2"
  else
    bad "$suite must default to bound_ir_complex_v2 workload/oracle"
  fi
done
for suite in experiments/suites/plan-simple.yaml experiments/suites/e2e-simple.yaml experiments/suites/ablation-simple.yaml; do
  if [[ -f "$suite" ]] && grep -q 'workload: experiments/workloads/bound_ir_simple_v2.json' "$suite" \
    && grep -q 'oracle: experiments/workloads/bound_ir_simple_v2.oracle.json' "$suite"; then
    ok "$suite defaults to bound_ir_simple_v2"
  else
    bad "$suite must default to bound_ir_simple_v2 workload/oracle"
  fi
done

# Param OAT formal suites (simple / complex v2)
if [[ -f experiments/suites/param.yaml ]] \
  && grep -q 'workload: experiments/workloads/bound_ir_complex_v2.json' experiments/suites/param.yaml \
  && grep -q 'oracle: experiments/workloads/bound_ir_complex_v2.oracle.json' experiments/suites/param.yaml \
  && grep -q 'grid_mode: oat' experiments/suites/param.yaml \
  && grep -q 'beam_width:' experiments/suites/param.yaml \
  && grep -q 'max_candidates:' experiments/suites/param.yaml \
  && grep -q 'max_llm_calls:' experiments/suites/param.yaml \
  && grep -q 'base_arm: kart' experiments/suites/param.yaml; then
  ok "param.yaml OAT complex_v2 + three-axis grid"
else
  bad "param.yaml must be OAT complex_v2 with kart + beam/candidates/llm grid"
fi
if [[ -f experiments/suites/param-simple.yaml ]] \
  && grep -q 'workload: experiments/workloads/bound_ir_simple_v2.json' experiments/suites/param-simple.yaml \
  && grep -q 'oracle: experiments/workloads/bound_ir_simple_v2.oracle.json' experiments/suites/param-simple.yaml \
  && grep -q 'grid_mode: oat' experiments/suites/param-simple.yaml \
  && grep -q 'base_arm: kart' experiments/suites/param-simple.yaml; then
  ok "param-simple.yaml OAT simple_v2"
else
  bad "param-simple.yaml must be OAT simple_v2 with kart"
fi

if grep -q 'workload: experiments/workloads/nl_ir_v4.json' experiments/suites/parse.yaml; then
  ok "parse.yaml uses nl_ir_v4"
else
  bad "parse.yaml must use nl_ir_v4"
fi
if [[ -f experiments/workloads/bound_ir_e2e_v2.provenance.json ]]; then
  ok "bound_ir_e2e_v2.provenance.json present"
else
  bad "missing bound_ir_e2e_v2.provenance.json"
fi

python3 - <<'PY' || bad "cmp_v3 / nl_v4 sizes"
import json
from pathlib import Path
wl = json.loads(Path("experiments/workloads/bound_ir_cmp_v3.json").read_text(encoding="utf-8"))
ora = json.loads(Path("experiments/workloads/bound_ir_cmp_v3.oracle.json").read_text(encoding="utf-8"))
nl = json.loads(Path("experiments/workloads/nl_ir_v4.json").read_text(encoding="utf-8"))
nl3 = json.loads(Path("experiments/workloads/nl_ir_v3.json").read_text(encoding="utf-8"))
poi = json.loads(Path("experiments/workloads/bound_ir_poi_e1.json").read_text(encoding="utf-8"))
poi_ora = json.loads(Path("experiments/workloads/bound_ir_poi_e1.oracle.json").read_text(encoding="utf-8"))
ids = [q["query_id"] for q in wl["queries"]]
ans = {r["query_id"] for r in ora["answers"]}
assert len(ids) == len(set(ids)) >= 20, len(ids)
assert set(ids) == ans
assert wl.get("evaluation_role") in ("kart_operating_region", "kart_rewrite_ge10_vs_cbo")
assert len(nl["queries"]) == 40
assert len(nl3["queries"]) >= 140
poi_ids = {q["query_id"] for q in poi["queries"]}
poi_ans = {r["query_id"] for r in poi_ora["answers"]}
assert poi_ids == poi_ans and len(poi_ids) >= 32
print("PASS  cmp_v3 n=%d nl_v3 n=%d poi_e1 n=%d" % (len(ids), len(nl["queries"]), len(poi_ids)))
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
