#!/usr/bin/env bash
# Chat-mode handbook runner: easy_query_example + hard_query_example vs expected IDs.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck disable=SC1091
source "$ROOT/scripts/load-llm-env.sh"
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-8-openjdk-amd64}"
export PATH="$JAVA_HOME/bin:$PATH"
JAR="$ROOT/target/kart.jar"
OUT="$ROOT/runs/chat-handbook"
mkdir -p "$OUT"
LOG="$OUT/run.log"
: > "$LOG"

ensure_hbase() {
  if ss -lntp 2>/dev/null | grep -q ':16020' \
     && java -Dkart.root="$ROOT" -jar "$JAR" doctor > /tmp/hb-doc.log 2>&1 \
     && grep -q 'doctor: OK' /tmp/hb-doc.log; then
    return 0
  fi
  echo "HBase not healthy — restarting" | tee -a "$LOG"
  bash "$ROOT/scripts/kart.sh" down || true
  sleep 3
  bash "$ROOT/scripts/kart.sh" up
}

run_nl() {
  local name="$1"
  local utterance="$2"
  local answers="${3:-}"
  local tmo="${4:-240}"
  echo "===== $name =====" | tee -a "$LOG"
  echo "utterance=$utterance" | tee -a "$LOG"
  local cmd=(java -Dkart.root="$ROOT" -jar "$JAR" query-nl
    --runs "$OUT/runs-$name" --manifest tdrive_v1_ready --dataset tdrive_v1)
  if [[ -n "$answers" ]]; then
    cmd+=(--answers "$answers")
  fi
  cmd+=("$utterance")
  set +e
  timeout "$tmo" "${cmd[@]}" > "$OUT/$name.out" 2>&1
  local code=$?
  set -e
  if [[ $code -eq 124 ]]; then
    echo "TIMEOUT $name" | tee -a "$LOG"
    echo 124 > "$OUT/$name.exit"
  else
    echo "$code" > "$OUT/$name.exit"
  fi
  echo "exit=$code name=$name" | tee -a "$LOG"
  strings "$OUT/$name.out" | grep -E 'UNSUPPORTED|I need|predicate=|region_name=|trajectory_ids=|status=|Confirm|BIND_ERROR|FAILED|similarity=' | tail -25 | tee -a "$LOG" || true
}

printf 'y\n' > "$OUT/y.txt"
printf 'DTW\ny\n' > "$OUT/dtw_y.txt"
printf 'FRECHET\ny\n' > "$OUT/frechet_y.txt"
printf 'I do not know a registered region\nn\n' > "$OUT/r5.txt"
# H3: point LLM at registered box covering h_s_small
printf 'tdrive_topk_box\ny\n' > "$OUT/topk_box_y.txt"

ensure_hbase

echo "=== probe ===" | tee -a "$LOG"
java -Dkart.root="$ROOT" -jar "$JAR" probe-llm > "$OUT/probe.out" 2>&1
grep PROBE "$OUT/probe.out" | tee -a "$LOG"

# -------- EASY --------
run_nl e_v1_full "Find trajectories of taxi 8857 between 2008-02-03T21:20:00+08:00 and 2008-02-03T21:30:00+08:00" "$OUT/y.txt" 240
ensure_hbase
run_nl e_v1_short "List all trajectories belonging to taxi 8857" "$OUT/y.txt" 240
ensure_hbase
run_nl e_v2 "Show trajectories of taxi 8857 that intersect tdrive_smoke_anchor between 2008-02-03T20:50:00+08:00 and 2008-02-03T21:50:00+08:00" "$OUT/y.txt" 240
ensure_hbase
run_nl e_v3 "List trajectory ids intersecting tdrive_topk_box between 2008-02-03T21:20:00+08:00 and 2008-02-03T21:30:00+08:00" "$OUT/y.txt" 240
ensure_hbase
run_nl e_v4 "Among trajectories intersecting tdrive_topk_box between 2008-02-03T20:50:00+08:00 and 2008-02-03T21:20:00+08:00, find 3 most similar to 8857-8857_14 using DTW" "$OUT/y.txt" 300
ensure_hbase
run_nl e_v5 "Top-3 DTW neighbors of 8857-8857_14 intersecting tdrive_smoke_anchor between 2008-02-03T21:20:00+08:00 and 2008-02-03T21:50:00+08:00" "$OUT/y.txt" 300
ensure_hbase
run_nl e_v6 "Find k=3 trajectories most similar to 8857-8857_14 intersecting tdrive_topk_box between 2008-02-03T20:50:00+08:00 and 2008-02-03T21:20:00+08:00" "$OUT/dtw_y.txt" 300

run_nl e_r1 "COUNT how many trajectories intersect beijing_core between 2008-02-03T21:20:00+08:00 and 2008-02-03T21:30:00+08:00" "" 60
run_nl e_r2 "Find trajectories whose continuous path segment crosses the ring road (not just GPS samples)" "" 60
run_nl e_r3 "Top-3 trajectories most similar to 8857-8857_14 using EDIT_DISTANCE" "" 60
run_nl e_r4 "Use continuous Fréchet distance to find neighbors of 8857-8857_14" "" 60
run_nl e_r5 "Trajectories through 火星广场 yesterday" "$OUT/r5.txt" 180

# -------- HARD (chat-runnable with registered regions / explicit times) --------
ensure_hbase
run_nl h_h1 "List all trajectories belonging to taxi 8857" "$OUT/y.txt" 240
ensure_hbase
run_nl h_h2 "Find trajectories of taxi 8857 between 2008-02-02T13:30:00+08:00 and 2008-02-03T13:30:00+08:00" "$OUT/y.txt" 300
ensure_hbase
run_nl h_h3 "Which trajectories of taxi 8857 intersect tdrive_topk_box" "$OUT/y.txt" 300
ensure_hbase
run_nl h_h8 "Among trajectories intersecting tdrive_topk_wide between 2008-02-03T20:50:00+08:00 and 2008-02-03T21:30:00+08:00, find 5 most similar to 8857-8857_14 using DTW" "$OUT/y.txt" 360
ensure_hbase
run_nl h_h9 "Among trajectories intersecting tdrive_topk_wide between 2008-02-03T20:50:00+08:00 and 2008-02-03T21:30:00+08:00, find 5 most similar to 8857-8857_14 using FRECHET" "$OUT/y.txt" 360
ensure_hbase
run_nl h_h11 "Find k=5 neighbors of 8857-8857_14 intersecting tdrive_topk_wide between 2008-02-03T20:50:00+08:00 and 2008-02-03T21:30:00+08:00" "$OUT/dtw_y.txt" 360

run_nl h_r1 "Find pairs of taxis that were within 50m of each other in beijing_core tonight" "" 90
run_nl h_r3 "Which trajectories continuously crossed the 2nd Ring as a polyline, not just GPS samples inside a box" "" 60
run_nl h_r4 "Top-50 trajectories most similar to 8857-8857_14 using LCSS" "" 60
run_nl h_r5 "Dump all trajectories through 火星广场 yesterday" "$OUT/r5.txt" 180

python3 - "$OUT" "$ROOT" <<'PY'
import json, pathlib, re, sys, hashlib
root = pathlib.Path(sys.argv[1])
proj = pathlib.Path(sys.argv[2])
ev = json.load(open(proj / "docs/hard_query_example/evidence.json"))
by = {c["query_id"]: c for c in ev["cases"]}

def text(name):
    p = root / f"{name}.out"
    if not p.exists():
        return ""
    return p.read_bytes().decode("utf-8", "replace").replace("\x00", "")

def exit_code(name):
    p = root / f"{name}.exit"
    return int(p.read_text().strip()) if p.exists() else -1

def parse_ids(t):
    m = re.search(r"trajectory_ids=\[([^\]]*)\]", t)
    if not m:
        return None
    return [x.strip() for x in m.group(1).split(",") if x.strip()]

def contains_8857_14(ids):
    return ids is not None and "8857-8857_14" in ids

rows = []

def check(name, kind, expect=None, pred=None, note=""):
    t = text(name)
    c = exit_code(name)
    ids = parse_ids(t)
    ok = False
    detail = ""
    if kind == "ids_exact":
        ok = c == 0 and ids == expect
        detail = f"got={ids} expect={expect}"
    elif kind == "ids_contains":
        ok = c == 0 and ids is not None and all(x in ids for x in expect)
        detail = f"n={len(ids) if ids else None} contains={expect}"
    elif kind == "ids_n":
        ok = c == 0 and ids is not None and len(ids) == expect
        detail = f"n={len(ids) if ids else None} expect_n={expect}"
    elif kind == "unsupported":
        ok = c == 3 and "UNSUPPORTED_QUERY" in t
        detail = "unsupported"
    elif kind == "clarify_no_huge":
        clar = "I need a bit more information" in t or "NEED_CLARIFICATION" in t
        n = len(ids) if ids else 0
        ok = clar and n < 1000
        detail = f"clar={clar} n={n}"
    elif kind == "clarify_metric":
        clar = "I need a bit more information" in t and ("metric" in t.lower() or "DTW" in t)
        ok = clar and c == 0 and ids == expect
        detail = f"clar={clar} got={ids}"
    elif kind == "custom":
        ok = pred(t, c, ids)
        detail = note
    rows.append({"name": name, "ok": ok, "exit": c, "ids": ids, "detail": detail, "note": note})
    print(("PASS" if ok else "FAIL"), name, "-", note or detail)

# Easy expected
H1 = by["h_eq_1"]["ids"]
H2 = by["h_t_large"]["ids"]
H3 = by["h_s_small"]["ids"]
V4 = ["2406-2406_9", "4920-4920_24"]
V5 = ["5340-5340_15", "2376-2376_10", "289-289_12"]
H8 = by["topk_st_2"]["ids"]
H9 = by["topk_st_2_frechet"]["ids"]

check("e_v1_full", "ids_exact", ["8857-8857_14"], note="easy V1 unique")
check("e_v1_short", "ids_exact", H1, note="easy V1 short = H1 24")
check("e_v2", "ids_exact", ["8857-8857_14"], note="easy V2 anchor")
check("e_v3", "ids_contains", ["8857-8857_14"], note="easy V3 contains anchor tid")
check("e_v4", "ids_exact", V4, note="easy V4 topk_st_1 order")
check("e_v5", "ids_exact", V5, note="easy V5 topk_st_3 order")
check("e_v6", "clarify_metric", V4, note="easy V6 clarify then V4")
check("e_r1", "unsupported", note="easy R1 COUNT")
check("e_r2", "unsupported", note="easy R2 continuous")
check("e_r3", "unsupported", note="easy R3 EDIT_DISTANCE")
check("e_r4", "unsupported", note="easy R4 continuous Frechet")
check("e_r5", "clarify_no_huge", note="easy R5 unknown place")

check("h_h1", "ids_exact", H1, note="hard H1 24")
check("h_h2", "ids_exact", H2, note="hard H2 7")
check("h_h3", "ids_exact", H3, note="hard H3 13 via tdrive_topk_box")
check("h_h8", "ids_exact", H8, note="hard H8 DTW k=5")
check("h_h9", "ids_exact", H9, note="hard H9 FRECHET k=5")
check("h_h11", "clarify_metric", H8, note="hard H11 clarify DTW -> H8")
check("h_r1", "unsupported", note="hard R1 pairs (may FAIL if LLM not early-rejected)")
check("h_r3", "unsupported", note="hard R3 continuous polyline")
check("h_r4", "unsupported", note="hard R4 LCSS")
check("h_r5", "clarify_no_huge", note="hard R5 mars dump")

# soft note for h_r1 if not early-rejected — still report
all_ok = all(r["ok"] for r in rows)
print("HANDBOOK_CHAT", "PASS" if all_ok else "FAIL")
(root / "summary.json").write_text(json.dumps({"pass": all_ok, "cases": rows}, indent=2, ensure_ascii=False), encoding="utf-8")
raise SystemExit(0 if all_ok else 1)
PY
