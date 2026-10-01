#!/usr/bin/env bash
# Server-only: independently compute and freeze FullScan Oracle for a NEW version.
# Never overwrites an existing Oracle. Example prefix: bound_ir_cbo_opportunity_verify_v2.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck disable=SC1091
source "$ROOT/scripts/kart-env.sh"
cd "$ROOT"

PREFIX=""
MANIFEST="tdrive_v1_ready"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --prefix) PREFIX="$2"; shift 2 ;;
    --manifest) MANIFEST="$2"; shift 2 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done
if [[ ! "$PREFIX" =~ ^bound_ir_cbo_opportunity_(candidates|verify)_v[0-9]+$ ]]; then
  echo "--prefix must be a versioned bound_ir_cbo_opportunity_candidates_vN or verify_vN basename" >&2
  exit 2
fi
WL="experiments/workloads/${PREFIX}.json"
OUT="experiments/workloads/${PREFIX}.oracle.json"
PROV="experiments/workloads/${PREFIX}.provenance.json"
if [[ ! -f "$WL" || ! -f "$PROV" || -e "$OUT" ]]; then
  echo "require new workload + provenance and absent oracle; refusing overwrite" >&2
  exit 3
fi

kart_ensure_jar
java -Dkart.root="$ROOT" -jar "$ROOT/target/kart.jar" build-oracle-cache \
  --evaluate-workload --workload-in "$WL" --oracle-out "$OUT" --manifest "$MANIFEST"

python3 - "$WL" "$OUT" "$PROV" "$MANIFEST" <<'PY'
import hashlib, json, sys
from pathlib import Path
wl, out, prov_path = map(Path, sys.argv[1:4])
manifest = sys.argv[4]
workload = json.loads(wl.read_text(encoding="utf-8"))
oracle = json.loads(out.read_text(encoding="utf-8"))
qids = [q.get("query_id") for q in workload.get("queries", [])]
aids = [a.get("query_id") for a in oracle.get("answers", [])]
if (not qids or len(qids) != len(set(qids)) or len(aids) != len(set(aids))
        or set(qids) != set(aids) or workload.get("manifest_id") != manifest):
    raise SystemExit("Oracle/workload ID or manifest mismatch; output not frozen")
if oracle.get("manifest_id") not in (None, manifest):
    raise SystemExit("Oracle manifest mismatch; output not frozen")
oracle["oracle_status"] = "frozen"
oracle["source"] = "fresh_fullscan_build_oracle_cache"
out.write_text(json.dumps(oracle, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
prov = json.loads(prov_path.read_text(encoding="utf-8"))
prov["oracle_status"] = "frozen"
prov["oracle_source"] = "fresh_fullscan_build_oracle_cache"
prov.setdefault("sha256", {})["workload"] = hashlib.sha256(wl.read_bytes()).hexdigest()
prov["sha256"]["oracle"] = hashlib.sha256(out.read_bytes()).hexdigest()
prov_path.write_text(json.dumps(prov, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
print(f"frozen {len(qids)} FullScan answers: {out}")
PY
