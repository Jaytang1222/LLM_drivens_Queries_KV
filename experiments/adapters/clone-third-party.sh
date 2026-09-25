#!/usr/bin/env bash
# Clone comparative-experiment third_party sources and freeze commits.
# Does NOT git pull when a checkout already exists. Formal runs must use the
# SHAs in experiments/third_party/commits.json.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TP="$ROOT/experiments/third_party"
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
  clone_one llmopt https://github.com/lucifer12346/LLMOpt.git
fi

python3 - <<'PY'
import json, os, subprocess, sys
from pathlib import Path

tp = Path(".")
root = Path("../..").resolve()
commits_path = Path("commits.json")
urls = {
  "din_sql": "https://github.com/MohammadrezaPourreza/Few-shot-NL2SQL-with-prompting",
  "text_to_nosql": "https://github.com/Jinwei-Lu/Text-to-NoSQL",
  "bao": "https://github.com/learnedsystems/BaoForPostgreSQL",
  "llmopt": "https://github.com/lucifer12346/LLMOpt",
}
dirs = {
  "din_sql": "din-sql",
  "text_to_nosql": "text-to-nosql",
  "bao": "bao",
  "llmopt": "llmopt",
}
entries = {
  "din_sql": ["DIN-SQL.py", "DIN-SQL_BIRD.py"],
  "text_to_nosql": ["src/tend/solver/sag/runtime.py"],
  "bao": ["bao_server/main.py"],
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
    rec = {"url": urls[k], "path": f"experiments/third_party/{d}"}
    if not (p / ".git").exists():
        rec["present"] = False
        errors.append(f"missing checkout {d}")
        out[k] = rec
        continue
    sha = subprocess.check_output(["git", "-C", str(p), "rev-parse", "HEAD"], text=True).strip()
    rec["commit"] = sha
    rec["present"] = True
    want = (frozen.get(k) or {}).get("commit")
    if want and want != sha:
        rec["frozen_commit"] = want
        rec["mismatch"] = True
        errors.append(f"{d} HEAD {sha} != frozen {want}")
        if not verify_only:
            try:
                subprocess.check_call(["git", "-C", str(p), "fetch", "--depth", "1", "origin", want])
                subprocess.check_call(["git", "-C", str(p), "checkout", want])
                rec["commit"] = want
                rec["mismatch"] = False
                errors.pop()
                print(f"checked out frozen {k}: {want}")
            except Exception as e:
                rec["checkout_error"] = str(e)
    files = []
    all_ok = True
    for rel in entries[k]:
        fp = p / rel
        files.append({"path": rel, "present": fp.is_file()})
        if not fp.is_file():
            all_ok = False
            errors.append(f"missing entry {d}/{rel}")
    rec["entry_files"] = files
    rec["entries_ok"] = all_ok
    out[k] = rec
    print(f"{k}: {rec.get('commit', 'MISSING')} entries_ok={all_ok}")

# Keep existing frozen SHAs unless this is a first write.
if not frozen:
    commits_path.write_text(json.dumps(out, indent=2) + "\n", encoding="utf-8")
    print("wrote commits.json (first freeze)")
else:
    # Refresh entry_files / present flags but do not move frozen SHAs.
    for k, rec in frozen.items():
        if k in out and out[k].get("commit"):
            rec["entry_files"] = out[k].get("entry_files")
            rec["entries_ok"] = out[k].get("entries_ok")
            rec["present"] = out[k].get("present")
            if out[k].get("mismatch"):
                rec["working_tree_commit"] = out[k]["commit"]
                rec["mismatch"] = True
    commits_path.write_text(json.dumps(frozen, indent=2) + "\n", encoding="utf-8")
    print("updated commits.json metadata; frozen SHAs unchanged")

if errors:
    print("VERIFY_FAIL:")
    for e in errors:
        print(" -", e)
    sys.exit(1)
print("third_party verify OK")
PY
