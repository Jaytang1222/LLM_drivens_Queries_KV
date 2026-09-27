#!/usr/bin/env bash
# Local comparative-framework gate check (no HBase, no large bench).
# Exit 0 only when required suite/holdout/freeze files and integrity pass.
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
need_file experiments/suites/conditional_llm_freeze.json
need_file experiments/workloads/bound_ir_holdout_v2.json
need_file experiments/workloads/bound_ir_holdout_v2.provenance.json
need_file experiments/workloads/bound_ir_holdout_v2_train.json
need_file experiments/workloads/bound_ir_holdout_v2_val.json
need_file experiments/workloads/bound_ir_holdout_v2_test.json
need_file experiments/adapters/holdout_integrity.py
need_file experiments/adapters/generate_holdout_workload_v2.py
need_file experiments/adapters/build_advantage_workload.py
need_file experiments/adapters/generate_advantage_hard_v2.py
need_file experiments/workloads/bound_ir_advantage_v1.json
need_file experiments/workloads/bound_ir_advantage_v1.oracle.json
need_file experiments/workloads/bound_ir_advantage_v1.provenance.json
need_file experiments/workloads/bound_ir_advantage_hard_v2.json
need_file experiments/workloads/bound_ir_advantage_hard_v2.oracle.json
need_file experiments/workloads/bound_ir_advantage_v2.json
need_file experiments/workloads/bound_ir_advantage_v2.oracle.json
need_file experiments/workloads/bound_ir_advantage_v2.provenance.json

if grep -q 'sag-mql-nofeedback' experiments/suites/parse.yaml \
  && ! grep -E '^\s*-\s*sag-mql\s*$' experiments/suites/parse.yaml >/dev/null; then
  ok "parse.yaml defaults to sag-mql-nofeedback (no bare sag-mql arm)"
else
  bad "parse.yaml must default sag-mql-nofeedback without enabling sag-mql"
fi

if grep -q 'pool-cbo' experiments/suites/plan-shared-pool.yaml \
  && grep -q 'pool-bao' experiments/suites/plan-shared-pool.yaml \
  && grep -q 'pool-conditional-llm' experiments/suites/plan-shared-pool.yaml \
  && grep -q 'pool-llm' experiments/suites/plan-shared-pool.yaml; then
  ok "plan-shared-pool.yaml has pool-cbo/pool-bao/pool-conditional-llm/pool-llm"
else
  bad "plan-shared-pool.yaml missing shared-pool arms"
fi

if grep -q 'kart-conditional-llm' experiments/suites/plan.yaml; then
  ok "plan.yaml includes kart-conditional-llm"
else
  bad "plan.yaml missing kart-conditional-llm"
fi

for suite in experiments/suites/plan.yaml experiments/suites/plan-shared-pool.yaml experiments/suites/e2e.yaml; do
  if grep -q 'workload: experiments/workloads/bound_ir_advantage_v2.json' "$suite" \
    && grep -q 'oracle: experiments/workloads/bound_ir_advantage_v2.oracle.json' "$suite"; then
    ok "$suite defaults to bound_ir_advantage_v2"
  else
    bad "$suite must default to bound_ir_advantage_v2 workload/oracle"
  fi
done
if grep -q 'workload: experiments/workloads/nl_ir_v1.json' experiments/suites/parse.yaml; then
  ok "parse.yaml still uses nl_ir_v1 (not switched)"
else
  bad "parse.yaml must remain nl_ir_v1"
fi
if grep -q 'bound_ir_holdout_v2_train.json' experiments/suites/plan-shared-pool-freeze-measure.yaml; then
  ok "freeze-measure still uses holdout v2 train"
else
  bad "plan-shared-pool-freeze-measure.yaml must stay on holdout v2 train"
fi

python3 - <<'PY' || bad "holdout v2 integrity"
import hashlib, json, sys
from pathlib import Path
sys.path.insert(0, "experiments/adapters")
from holdout_integrity import assert_holdout_integrity
wl = json.loads(Path("experiments/workloads/bound_ir_holdout_v2.json").read_text(encoding="utf-8"))
assert_holdout_integrity(wl["queries"], wl["catalog"])
prov = json.loads(Path("experiments/workloads/bound_ir_holdout_v2.provenance.json").read_text(encoding="utf-8"))
assert prov.get("oracle_status") == "frozen", prov.get("oracle_status")
split_meta = json.loads(Path("experiments/workloads/bound_ir_holdout_v2.splits.json").read_text(encoding="utf-8"))
assert split_meta.get("oracle_status") == "frozen"
for split, info in split_meta["splits"].items():
    workload_path = Path(info["path"])
    oracle_path = Path(prov["oracle_splits"][split]["oracle_path"])
    workload = json.loads(workload_path.read_text(encoding="utf-8"))
    oracle = json.loads(oracle_path.read_text(encoding="utf-8"))
    oracle_sha = hashlib.sha256(oracle_path.read_bytes()).hexdigest()
    assert len(workload["queries"]) == len(oracle["answers"]) == info["n"]
    assert {q["query_id"] for q in workload["queries"]} == {a["query_id"] for a in oracle["answers"]}
    assert workload.get("oracle_status") == "frozen"
    assert oracle_sha == workload["oracle_sha256"] == info["oracle_sha256"] == prov["oracle_splits"][split]["sha256"]
    assert hashlib.sha256(workload_path.read_bytes()).hexdigest() == info["sha256"]
assert wl.get("evaluation_role") == "diagnostic_generalization_stress"
print("PASS  holdout v2 integrity + frozen Oracle hashes")
PY

python3 - <<'PY' || bad "conditional freeze schema"
import json
from pathlib import Path
f = json.loads(Path("experiments/suites/conditional_llm_freeze.json").read_text(encoding="utf-8"))
assert f.get("schema") == "kart.conditional_llm_freeze/v1"
assert "rel_gap" in f and "max_llm" in f and "status" in f
assert f["status"] in ("unfrozen_defaults", "frozen_for_test")
print("PASS  conditional_llm_freeze.json schema")
PY

python3 - <<'PY' || bad "holdout v2 catalog audit"
import json
from pathlib import Path
from collections import Counter
wl = json.loads(Path("experiments/workloads/bound_ir_holdout_v2.json").read_text(encoding="utf-8"))
cat = wl["catalog"]
layers = Counter(m.get("layer") for m in cat.values())
splits = Counter(m.get("split") for m in cat.values())
print("PASS  catalog layers=", dict(layers), "splits=", dict(splits))
# Intent layers present for conditional-LLM hypothesis (names only; not measured n_safe).
need = {"multi_index", "multi_index_uncertain", "easy_single_index"}
missing = need - set(layers)
if missing:
    raise SystemExit("missing intended layers: " + str(missing))
print("PASS  intended hypothesis layers present (names only)")
PY

python3 - <<'PY' || bad "advantage v1 workload role/oracle"
import json
from pathlib import Path

wl = json.loads(Path("experiments/workloads/bound_ir_advantage_v1.json").read_text(encoding="utf-8"))
ora = json.loads(Path("experiments/workloads/bound_ir_advantage_v1.oracle.json").read_text(encoding="utf-8"))
prov = json.loads(Path("experiments/workloads/bound_ir_advantage_v1.provenance.json").read_text(encoding="utf-8"))
ids = [q["query_id"] for q in wl["queries"]]
ans = {r["query_id"] for r in ora["answers"]}
assert wl.get("evaluation_role") == "kart_operating_region", wl.get("evaluation_role")
assert prov.get("evaluation_role") == "kart_operating_region"
assert len(ids) == len(set(ids)) == 40, len(ids)
assert set(ids) == ans, "oracle ids != workload ids"
fam = {m.get("family") for m in wl["catalog"].values()}
assert not (fam & {"T", "Z", "H"}), fam
assert not any(str(i).startswith("topk_s_") for i in ids)
assert not any((m.get("selectivity") in ("empty", "boundary")) or m.get("layer") == "empty_boundary" for m in wl["catalog"].values())
assert not any(m.get("source_workload") == "bound_ir_holdout_v2" and m.get("split") == "test"
               for m in wl["catalog"].values()), "locked v2 test leaked into advantage slice"
layers = {m.get("layer") for m in wl["catalog"].values()}
assert {"multi_index", "multi_index_uncertain", "topk_metric"} <= layers
print("PASS  advantage_v1 n=40 role=kart_operating_region oracle-complete")
PY

python3 - <<'PY' || bad "advantage v2 workload role/oracle"
import json
from pathlib import Path
from collections import Counter

wl = json.loads(Path("experiments/workloads/bound_ir_advantage_v2.json").read_text(encoding="utf-8"))
ora = json.loads(Path("experiments/workloads/bound_ir_advantage_v2.oracle.json").read_text(encoding="utf-8"))
prov = json.loads(Path("experiments/workloads/bound_ir_advantage_v2.provenance.json").read_text(encoding="utf-8"))
hard = json.loads(Path("experiments/workloads/bound_ir_advantage_hard_v2.json").read_text(encoding="utf-8"))
ids = [q["query_id"] for q in wl["queries"]]
ans = {r["query_id"] for r in ora["answers"]}
assert wl.get("evaluation_role") == "kart_operating_region", wl.get("evaluation_role")
assert prov.get("evaluation_role") == "kart_operating_region"
assert prov.get("oracle_status") == "frozen"
assert len(ids) == len(set(ids)) == 58, len(ids)
assert set(ids) == ans, "oracle ids != workload ids"
assert len(hard["queries"]) == 28
assert not any(m.get("source_workload") == "bound_ir_holdout_v2" and m.get("split") == "test"
               for m in wl["catalog"].values()), "locked v2 test leaked into advantage_v2"
fps = []
import hashlib
for q in wl["queries"]:
    body = {k: v for k, v in q.items() if k != "query_id"}
    fps.append(hashlib.sha256(json.dumps(body, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode()).hexdigest())
assert len(fps) == len(set(fps)), "exact duplicate BoundIR fingerprints remain"
fam = {m.get("family") for m in wl["catalog"].values()}
assert not (fam & {"T", "Z", "H"}), fam
assert not any(str(i).startswith("topk_s_") for i in ids)
need_layers = {"tzh_rbo_trap", "tz_hard_intersect", "th_zh_uncertain", "topk_st_metric"}
hard_layers = {m.get("layer") for m in hard["catalog"].values()}
assert need_layers <= hard_layers, hard_layers
empty = [r["query_id"] for r in ora["answers"] if not (r.get("trajectory_ids") or r.get("ids") or [])]
assert not empty, empty
assert prov.get("n_raw_queries") == 68
assert prov.get("n_deduplicated") == 10
print("PASS  advantage_v2 n=58 role=kart_operating_region deduplicated hard_layers=", dict(Counter(m.get("layer") for m in hard["catalog"].values())))
PY

if [[ "$FAIL" -gt 0 ]]; then
  echo "=== check-comparative-framework: FAILED ($FAIL) ==="
  exit 1
fi
echo "=== check-comparative-framework: OK (no HBase / no large bench) ==="
echo "Still pending gated steps: oracle-diff-65 on clean commit; full E1/E2/E3 runs."
