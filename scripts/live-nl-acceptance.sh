#!/usr/bin/env bash
# Live LLM acceptance: probe-llm + ≥5 query-nl utterances (no --mock).
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck disable=SC1091
source "$ROOT/scripts/load-llm-env.sh"

export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-8-openjdk-amd64}"
export PATH="$JAVA_HOME/bin:$PATH"
JAR="$ROOT/target/kart.jar"
if [[ ! -f "$JAR" ]]; then
  echo "missing $JAR" >&2
  exit 1
fi

OUT="$ROOT/runs/llm-live"
mkdir -p "$OUT"
LOG="$OUT/acceptance.log"
: > "$LOG"

run_nl() {
  local name="$1"
  local utterance="$2"
  shift 2
  local extra=("$@")
  echo "===== $name =====" | tee -a "$LOG"
  echo "utterance=$utterance" | tee -a "$LOG"
  set +e
  java -Dkart.root="$ROOT" -jar "$JAR" query-nl \
    --runs "$OUT/runs-$name" \
    "${extra[@]}" \
    "$utterance" 2>&1 | tee -a "$OUT/$name.out" | tee -a "$LOG" | tail -40
  local code=${PIPESTATUS[0]}
  set -e
  echo "exit=$code name=$name" | tee -a "$LOG"
  echo "$code" > "$OUT/$name.exit"
  return 0
}

echo "=== probe-llm (Java client) ===" | tee -a "$LOG"
set +e
java -Dkart.root="$ROOT" -jar "$JAR" probe-llm 2>&1 | tee "$OUT/probe-llm.out" | tee -a "$LOG"
PROBE_EXIT=${PIPESTATUS[0]}
set -e
echo "probe_exit=$PROBE_EXIT" | tee -a "$LOG"

# Answers: clarification lines then final confirm "y"
cat > "$OUT/answers-confirm-y.txt" <<'EOF'
y
EOF
cat > "$OUT/answers-missing-ref.txt" <<'EOF'
R
y
EOF
cat > "$OUT/answers-missing-region.txt" <<'EOF'
fixture_box
y
EOF

# 1) Complete Top-K (fixture) → confirm y
run_nl "u1_complete_topk" \
  "Find the 2 trajectories most similar to R inside fixture_box between 2008-02-02T08:00:00+08:00 and 2008-02-02T08:10:00+08:00" \
  --answers "$OUT/answers-confirm-y.txt"

# 2) Missing reference id → must clarify (not invent), then confirm
run_nl "u2_missing_ref" \
  "Find top-2 most similar trajectories inside fixture_box between 2008-02-02T08:00:00+08:00 and 2008-02-02T08:10:00+08:00" \
  --answers "$OUT/answers-missing-ref.txt"

# 3) Missing region → clarify, then confirm
run_nl "u3_missing_region" \
  "Find trajectories that intersect the area between 2008-02-02T08:00:00+08:00 and 2008-02-02T08:10:00+08:00" \
  --answers "$OUT/answers-missing-region.txt"

# 4) Range-style (trajectory ids) with full slots → confirm y
run_nl "u4_range_ids" \
  "List trajectory ids that intersect fixture_box between 2008-02-02T08:00:00+08:00 and 2008-02-02T08:10:00+08:00" \
  --answers "$OUT/answers-confirm-y.txt"

# 5) Unsupported COUNT → must not fabricate results
run_nl "u5_count_unsupported" \
  "Please COUNT how many trajectories intersect fixture_box on 2008-02-02 between 08:00 and 08:10 +08"

python3 - <<PY | tee -a "$LOG"
import json, pathlib, os
root = pathlib.Path("$OUT")
rows = []
names = ["u1_complete_topk","u2_missing_ref","u3_missing_region","u4_range_ids","u5_count_unsupported"]
for name in names:
    exit_p = root / f"{name}.exit"
    out_p = root / f"{name}.out"
    code = int(exit_p.read_text().strip()) if exit_p.exists() else -1
    text = out_p.read_text(encoding="utf-8", errors="replace") if out_p.exists() else ""
    clarified = ("I need a bit more information" in text) or ("NEED_CLARIFICATION" in text)
    unsupported = code == 3 or "UNSUPPORTED" in text
    ok_exec = code == 0 and "status=OK" in text
    if name == "u5_count_unsupported":
        okish = unsupported and code == 3
    elif name in ("u2_missing_ref", "u3_missing_region"):
        okish = ok_exec and clarified
    else:
        okish = ok_exec
    rows.append({"name": name, "exit": code, "okish": okish, "clarified": clarified, "unsupported": unsupported})
print("LIVE_SUMMARY", json.dumps(rows, ensure_ascii=False))
probe = (root / "probe-llm.out").read_text(encoding="utf-8", errors="replace")
print("PROBE_OK", "PROBE_LLM_OK" in probe)
all_ok = ("PROBE_LLM_OK" in probe) and all(r["okish"] for r in rows)
print("LIVE_ACCEPTANCE", "PASS" if all_ok else "FAIL")
(root / "summary.json").write_text(json.dumps({"rows": rows, "pass": all_ok}, indent=2), encoding="utf-8")
raise SystemExit(0 if all_ok else 1)
PY
