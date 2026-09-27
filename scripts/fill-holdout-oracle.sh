#!/usr/bin/env bash
# Fill FullScan oracle for holdout splits and freeze SHA256.
# Gated / optional — do NOT run as part of small framework checks.
# Requires WSL HBase READY snapshot.
#
# Usage:
#   ./scripts/fill-holdout-oracle.sh --version v2
#   ./scripts/fill-holdout-oracle.sh --version v1 --allow-leaky
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck disable=SC1091
source "$ROOT/scripts/kart-env.sh"
cd "$ROOT"

VERSION=v2
ALLOW_LEAKY=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --version) VERSION="$2"; shift 2 ;;
    --allow-leaky) ALLOW_LEAKY=1; shift ;;
    *) echo "unknown arg: $1" >&2; exit 2 ;;
  esac
done

PROV="experiments/workloads/bound_ir_holdout_${VERSION}.provenance.json"
if [[ -f "$PROV" ]]; then
  if python3 -c "import json,sys; p=json.load(open(sys.argv[1])); sys.exit(0 if p.get('oracle_status')=='frozen' else 1)" "$PROV"; then
    echo "REFUSE: $PROV already oracle_status=frozen; bump holdout version instead of overwrite." >&2
    exit 3
  fi
fi

kart_ensure_jar

echo "=== fill holdout oracles (FullScan) version=$VERSION ==="
SPLIT_ARGS=(--version "$VERSION")
if [[ "$ALLOW_LEAKY" -eq 1 ]]; then
  SPLIT_ARGS+=(--allow-leaky)
fi
python3 experiments/adapters/split_holdout_workloads.py "${SPLIT_ARGS[@]}"

for split in train val test; do
  WL="experiments/workloads/bound_ir_holdout_${VERSION}_${split}.json"
  OUT="experiments/workloads/bound_ir_holdout_${VERSION}_${split}.oracle.json"
  if [[ -f "$OUT" ]]; then
    echo "REFUSE: $OUT exists; delete only if intentionally regenerating a non-frozen version." >&2
    exit 3
  fi
  echo "--- FullScan oracle: $split ---"
  java -Dkart.root="$ROOT" -jar "$ROOT/target/kart.jar" build-oracle-cache \
    --evaluate-workload \
    --workload-in "$WL" \
    --oracle-out "$OUT" \
    --manifest tdrive_v1_ready
  sha=$(sha256sum "$OUT" | awk '{print $1}')
  echo "oracle sha256=$sha path=$OUT"
done

python3 - "$VERSION" <<'PY'
import hashlib, json, sys
from pathlib import Path
version = sys.argv[1]
root = Path(".")
prov_path = root / f"experiments/workloads/bound_ir_holdout_{version}.provenance.json"
prov = json.loads(prov_path.read_text(encoding="utf-8"))
if prov.get("oracle_status") == "frozen":
    raise SystemExit("refuse: provenance already frozen")
splits = {}
split_updates = {}
meta_path = root / f"experiments/workloads/bound_ir_holdout_{version}.splits.json"
meta = json.loads(meta_path.read_text(encoding="utf-8"))
for split in ("train", "val", "test"):
    p = root / f"experiments/workloads/bound_ir_holdout_{version}_{split}.oracle.json"
    w = root / f"experiments/workloads/bound_ir_holdout_{version}_{split}.json"
    blob = p.read_bytes()
    oracle = json.loads(blob)
    workload = json.loads(w.read_text(encoding="utf-8"))
    query_ids = [q["query_id"] for q in workload["queries"]]
    answer_ids = [a["query_id"] for a in oracle["answers"]]
    if (len(query_ids) != len(set(query_ids)) or len(answer_ids) != len(set(answer_ids))
            or set(query_ids) != set(answer_ids)
            or oracle.get("manifest_id") != workload.get("manifest_id")
            or oracle.get("semantics_version") != workload.get("semantics_version")):
        raise SystemExit(f"oracle/workload mismatch: {split}")
    if meta["splits"][split]["query_ids"] != query_ids:
        raise SystemExit(f"split metadata mismatch: {split}")
    digest = hashlib.sha256(blob).hexdigest()
    splits[split] = {
        "oracle_path": str(p).replace("\\", "/"),
        "sha256": digest,
        "bytes": len(blob),
    }
    workload["oracle_status"] = "frozen"
    workload["oracle_sha256"] = digest
    split_updates[split] = (w, workload)
for split, (w, workload) in split_updates.items():
    content = json.dumps(workload, indent=2, ensure_ascii=False) + "\n"
    w.write_bytes(content.encode("utf-8"))
    meta["splits"][split]["sha256"] = hashlib.sha256(content.encode("utf-8")).hexdigest()
    meta["splits"][split]["oracle_sha256"] = splits[split]["sha256"]
meta["oracle_status"] = "frozen"
meta_path.write_bytes((json.dumps(meta, indent=2) + "\n").encode("utf-8"))
prov["oracle_status"] = "frozen"
prov["oracle_splits"] = splits
prov_path.write_bytes((json.dumps(prov, indent=2) + "\n").encode("utf-8"))
print("updated provenance oracle_status=frozen")
PY

echo "=== done: holdout ${VERSION} oracles frozen ==="
echo "Use --workload experiments/workloads/bound_ir_holdout_${VERSION}_{train,val,test}.json after freeze."
