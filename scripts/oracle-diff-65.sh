#!/usr/bin/env bash
# Oracle differential acceptance on formal BoundIR (65): fullscan/rbo/cbo/kart must match oracle.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck disable=SC1091
source "$ROOT/scripts/kart-env.sh"
cd "$ROOT"
kart_ensure_jar

RUN_ID="${1:-oracle-diff-65-$(date +%Y%m%d%H%M%S)}"
CACHE="${CACHE:-warm}"
TRIALS="${TRIALS:-1}"

echo "=== Oracle diff 65: run_id=$RUN_ID cache=$CACHE trials=$TRIALS ==="
set +e
./scripts/bench-e2e.sh \
  --run-id "$RUN_ID" \
  --arm fullscan,rbo,cbo,kart \
  --cache "$CACHE" \
  --trials "$TRIALS" \
  --workload experiments/workloads/bound_ir_v1.json \
  --oracle experiments/workloads/bound_ir_v1.oracle.json
bench_rc=$?
set -e

RESULT="$ROOT/experiments/results/$RUN_ID"
python3 - "$RESULT" "$TRIALS" <<'PY'
import json, sys
from collections import defaultdict
from pathlib import Path
result = Path(sys.argv[1])
trials = int(sys.argv[2])
workload = json.loads(Path("experiments/workloads/bound_ir_v1.json").read_text(encoding="utf-8"))
expected_qids = {q["query_id"] for q in workload["queries"]}
expected_arms = {"fullscan", "rbo", "cbo", "kart"}
if len(expected_qids) != 65 or trials < 1:
    print("ORACLE_DIFF_FAIL invalid workload or trials")
    sys.exit(2)
rows = []
p = result / "e2e.jsonl"
if not p.is_file():
    print("MISSING", p)
    sys.exit(2)
for line in p.read_text(encoding="utf-8").splitlines():
    if line.strip():
        rows.append(json.loads(line))
by = defaultdict(list)
for r in rows:
    by[(r.get("query_id"), r.get("arm"))].append(r)
arms = sorted({r.get("arm") for r in rows})
qids = sorted({r.get("query_id") for r in rows})
print(f"queries={len(qids)} arms={arms} rows={len(rows)}")
if len(rows) == 0:
    print("ORACLE_DIFF_FAIL empty e2e.jsonl")
    sys.exit(2)
# Require every frozen query × every arm × every trial exactly once.
expected = len(expected_qids) * len(expected_arms) * trials
if set(qids) != expected_qids or set(arms) != expected_arms or len(rows) != expected:
    print(f"ORACLE_DIFF_FAIL wrong coverage rows={len(rows)} expected={expected} "
          f"missing_queries={sorted(expected_qids - set(qids))} "
          f"extra_queries={sorted(set(qids) - expected_qids)}")
    sys.exit(2)
for q in expected_qids:
    for a in expected_arms:
        seen = sorted(r.get("trial") for r in by[(q, a)])
        if seen != list(range(1, trials + 1)):
            print(f"ORACLE_DIFF_FAIL incomplete trials query={q} arm={a} seen={seen}")
            sys.exit(2)
fail = []
for q in qids:
    for a in arms:
        rs = by[(q, a)]
        ok = all(bool(r.get("ok_oracle")) for r in rs)
        if not ok:
            err = rs[0].get("error") if rs else "?"
            fail.append((q, a, err))
if fail:
    print("ORACLE_DIFF_FAIL", len(fail))
    for q, a, e in fail[:40]:
        print(f"  {q}\t{a}\t{e}")
    if len(fail) > 40:
        print(f"  ... +{len(fail)-40} more")
    sys.exit(1)
print("ORACLE_DIFF_PASS all arms match oracle on", len(qids), "queries")
PY
diff_rc=$?
if [[ $diff_rc -ne 0 ]]; then
  exit "$diff_rc"
fi
if [[ $bench_rc -ne 0 ]]; then
  echo "ORACLE_DIFF_FAIL bench-e2e exited $bench_rc" >&2
  exit "$bench_rc"
fi
exit 0
