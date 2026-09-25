#!/usr/bin/env bash
# Run chat-easy cases one-by-one with timeouts to avoid hung HBase scans blocking forever.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck disable=SC1091
source "$ROOT/scripts/load-llm-env.sh"
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-8-openjdk-amd64}"
export PATH="$JAVA_HOME/bin:$PATH"
JAR="$ROOT/target/kart.jar"
OUT="$ROOT/runs/chat-easy"
mkdir -p "$OUT"
LOG="$OUT/acceptance.log"
: > "$LOG"

run_nl() {
  local name="$1"
  local utterance="$2"
  local answers="${3:-}"
  local tmo="${4:-180}"
  echo "===== $name =====" | tee -a "$LOG"
  echo "utterance=$utterance" | tee -a "$LOG"
  local cmd=(java -Dkart.root="$ROOT" -jar "$JAR" query-nl --runs "$OUT/runs-$name" --manifest tdrive_v1_ready --dataset tdrive_v1)
  if [[ -n "$answers" ]]; then
    cmd+=(--answers "$answers")
  fi
  cmd+=("$utterance")
  set +e
  timeout "$tmo" "${cmd[@]}" > "$OUT/$name.out" 2>&1
  local code=$?
  set -e
  if [[ $code -eq 124 ]]; then
    echo "TIMEOUT $name after ${tmo}s" | tee -a "$LOG"
    echo 124 > "$OUT/$name.exit"
  else
    echo "$code" > "$OUT/$name.exit"
  fi
  echo "exit=$code name=$name" | tee -a "$LOG"
  strings "$OUT/$name.out" | grep -E 'UNSUPPORTED|I need|predicate=|trajectory_ids=|status=|Confirm|BIND_ERROR|FAILED' | tail -20 | tee -a "$LOG" || true
}

echo "=== probe ===" | tee -a "$LOG"
java -Dkart.root="$ROOT" -jar "$JAR" probe-llm > "$OUT/probe-llm.out" 2>&1
grep -E 'PROBE' "$OUT/probe-llm.out" | tee -a "$LOG"

printf 'y\n' > "$OUT/answers-y.txt"
printf 'DTW\ny\n' > "$OUT/answers-metric-dtw-y.txt"
printf 'I do not know\ntdrive_smoke_anchor\nn\n' > "$OUT/answers-r5.txt"

ensure_hbase() {
  if ss -lntp 2>/dev/null | grep -q ':16020'; then
    return 0
  fi
  echo "HBase RS :16020 not listening — restarting stack" | tee -a "$LOG"
  bash "$ROOT/scripts/kart.sh" down || true
  sleep 3
  bash "$ROOT/scripts/kart.sh" up
}

# Early rejects (no HBase exec) — short timeout
run_nl r1_count "COUNT how many trajectories intersect beijing_core between 2008-02-03T21:20:00+08:00 and 2008-02-03T21:30:00+08:00" "" 60
run_nl r2_continuous "Find trajectories whose continuous path segment crosses the ring road (not just GPS samples)" "" 60
run_nl r3_edit "Top-3 trajectories most similar to 8857-8857_14 using EDIT_DISTANCE" "" 90
run_nl r4_cfrechet "Use continuous Fréchet distance to find neighbors of 8857-8857_14" "" 60
run_nl r5_mars "Trajectories through 火星广场 yesterday" "$OUT/answers-r5.txt" 180

# Executing cases — ensure RS is up (LLM-long connects can leave stale RS)
ensure_hbase
run_nl v1_full "Find trajectories of taxi 8857 between 2008-02-03T21:20:00+08:00 and 2008-02-03T21:30:00+08:00" "$OUT/answers-y.txt" 240
ensure_hbase
run_nl v2_anchor "Show trajectories of taxi 8857 that intersect tdrive_smoke_anchor between 2008-02-03T20:50:00+08:00 and 2008-02-03T21:50:00+08:00" "$OUT/answers-y.txt" 240
ensure_hbase
run_nl v6_clarify_metric "Find k=3 trajectories most similar to 8857-8857_14 intersecting tdrive_topk_box between 2008-02-03T20:50:00+08:00 and 2008-02-03T21:20:00+08:00" "$OUT/answers-metric-dtw-y.txt" 300

python3 - "$OUT" <<'PY'
import json, pathlib, re, sys
root = pathlib.Path(sys.argv[1])

def text(name):
    p = root / f"{name}.out"
    if not p.exists():
        return ""
    # strip NULs from StatusLog binary noise
    return p.read_bytes().decode("utf-8", "replace").replace("\x00", "")

def exit_code(name):
    p = root / f"{name}.exit"
    return int(p.read_text().strip()) if p.exists() else -1

def has_ids(t, expected):
    m = re.search(r"trajectory_ids=\[([^\]]*)\]", t)
    if not m:
        return False
    got = [x.strip() for x in m.group(1).split(",") if x.strip()]
    return got == expected

def count_ids(t):
    m = re.search(r"trajectory_ids=\[([^\]]*)\]", t)
    if not m:
        return 0
    return len([x for x in m.group(1).split(",") if x.strip()])

cases = []
t,c = text("v1_full"), exit_code("v1_full")
cases.append(("v1_full", c==0 and "predicate=vehicle_id EQ 8857" in t and has_ids(t,["8857-8857_14"]), "unique id + predicate"))
t,c = text("v2_anchor"), exit_code("v2_anchor")
cases.append(("v2_anchor", c==0 and has_ids(t,["8857-8857_14"]) and "tdrive_smoke_anchor" in t, "anchor unique"))
t,c = text("v6_clarify_metric"), exit_code("v6_clarify_metric")
clar = "I need a bit more information" in t
cases.append(("v6_clarify_metric", clar and c==0 and has_ids(t,["2406-2406_9","4920-4920_24"]), "clarify metric + topk"))
t,c = text("r1_count"), exit_code("r1_count")
cases.append(("r1_count", c==3 and "UNSUPPORTED_QUERY" in t and "COUNT" in t, "COUNT early"))
t,c = text("r2_continuous"), exit_code("r2_continuous")
cases.append(("r2_continuous", c==3 and "UNSUPPORTED_QUERY" in t and "continuous" in t.lower(), "continuous early"))
t,c = text("r3_edit"), exit_code("r3_edit")
cases.append(("r3_edit", (c==3 or "UNSUPPORTED" in t) and "EDIT_DISTANCE" in t, "EDIT_DISTANCE"))
t,c = text("r4_cfrechet"), exit_code("r4_cfrechet")
cases.append(("r4_cfrechet", c==3 and "UNSUPPORTED_QUERY" in t, "cFrechet early"))
t,c = text("r5_mars"), exit_code("r5_mars")
cases.append(("r5_mars", ("I need a bit more information" in t or "NEED_CLARIFICATION" in t) and count_ids(t)<1000, "no huge dump"))

probe = text("probe-llm")
probe_ok = "PROBE_LLM_OK" in probe or "PROBE_OK" in probe
all_ok = probe_ok and all(o for _,o,_ in cases)
print("PROBE_OK", probe_ok)
for n,o,note in cases:
    print(("PASS" if o else "FAIL"), n, "-", note, "exit", exit_code(n))
print("CHAT_EASY_ACCEPTANCE", "PASS" if all_ok else "FAIL")
(root/"summary.json").write_text(json.dumps({"pass":all_ok,"cases":[{"name":n,"ok":o,"note":note} for n,o,note in cases]}, indent=2))
raise SystemExit(0 if all_ok else 1)
PY
