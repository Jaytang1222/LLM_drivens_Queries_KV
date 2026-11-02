#!/usr/bin/env bash
# Clone comparative-experiment upstream sources and freeze commits.
# Does NOT git pull when a checkout already exists. Formal runs must use the
# SHAs in experiments/adapters/upstream/commits.json.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TP="$ROOT/experiments/adapters/upstream"
mkdir -p "$TP"
cd "$TP"

VERIFY_ONLY=0
if [[ "${1:-}" == "--verify" ]]; then
  VERIFY_ONLY=1
fi
export VERIFY_ONLY

clone_one() {
  local name="$1" url="$2"
  if [[ -d "$name/.git" ]]; then
    echo "exists (frozen, no pull): $name"
  else
    echo "cloning $name ..."
    git clone --depth 1 "$url" "$name"
  fi
}

if [[ "$VERIFY_ONLY" -eq 0 ]]; then
  clone_one din-sql https://github.com/MohammadrezaPourreza/Few-shot-NL2SQL-with-prompting.git
  clone_one text-to-nosql https://github.com/Jinwei-Lu/Text-to-NoSQL.git
  clone_one bao https://github.com/learnedsystems/BaoForPostgreSQL.git
  clone_one paramtree https://github.com/yplusone/ParamTree.git
  clone_one llmopt https://github.com/lucifer12346/LLMOpt.git
fi

python3 - <<'PY'
import json, os, subprocess, sys
from pathlib import Path

tp = Path(".")
commits_path = Path("commits.json")
urls = {
  "din_sql": "https://github.com/MohammadrezaPourreza/Few-shot-NL2SQL-with-prompting",
  "text_to_nosql": "https://github.com/Jinwei-Lu/Text-to-NoSQL",
  "bao": "https://github.com/learnedsystems/BaoForPostgreSQL",
  "paramtree": "https://github.com/yplusone/ParamTree",
  "llmopt": "https://github.com/lucifer12346/LLMOpt",
}
dirs = {
  "din_sql": "din-sql",
  "text_to_nosql": "text-to-nosql",
  "bao": "bao",
  "paramtree": "paramtree",
  "llmopt": "llmopt",
}
entries = {
  "din_sql": ["DIN-SQL.py", "DIN-SQL_BIRD.py"],
  "text_to_nosql": ["src/tend/solver/sag/runtime.py"],
  "bao": ["bao_server/main.py"],
  "paramtree": [
    "model/query_inference.py",
    "model/paramtree.py",
  ],
  "llmopt": ["README.md"],
}

frozen = {}
if commits_path.is_file():
    frozen = json.loads(commits_path.read_text(encoding="utf-8"))

verify_only = os.environ.get("VERIFY_ONLY", "0") == "1"
errors = []
out = {}
for k, d in dirs.items():
    p = tp / d
    rec = {"url": urls[k], "path": f"experiments/adapters/upstream/{d}"}
    if not (p / ".git").exists():
        rec["present"] = False
        errors.append(f"missing checkout {d}")
        out[k] = rec
        continue
    sha = subprocess.check_output(
        ["git", "-C", str(p), "rev-parse", "HEAD"], text=True
    ).strip()
    rec["present"] = True
    rec["commit"] = sha
    if k in frozen and frozen[k].get("commit") and frozen[k]["commit"] != sha:
        errors.append(
            f"{d}: HEAD {sha[:12]} != frozen {frozen[k]['commit'][:12]}"
        )
    for rel in entries[k]:
        if not (p / rel).is_file():
            errors.append(f"missing entry {d}/{rel}")
    # preserve extra notes from prior freeze
    if k in frozen:
        for ek in ("note",):
            if ek in frozen[k]:
                rec[ek] = frozen[k][ek]
    out[k] = rec

# keep non-cloned notes (e.g. tman_spatial) from prior file
if isinstance(frozen, dict):
    for k, v in frozen.items():
        if k not in out:
            out[k] = v

commits_path.write_text(json.dumps(out, indent=2) + "\n", encoding="utf-8")
if errors:
    print("FAIL:")
    for e in errors:
        print(" ", e)
    sys.exit(1)
print("upstream verify OK")
PY
