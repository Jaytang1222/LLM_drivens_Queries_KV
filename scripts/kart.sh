#!/usr/bin/env bash
# Unified KART operator entry — run inside WSL only.
# Keepers: kart.sh, kart-env.sh, sync-wsl-workspace.sh, run-tdrive-smoke.sh, publish-cost-calib-pack.sh
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck disable=SC1091
source "$ROOT/scripts/kart-env.sh"
cd "$ROOT"

kart_start_stack() {
  echo "=== KART start-stack ==="
  echo "ZK_HOME=$ZK_HOME"
  echo "HBASE_HOME=$HBASE_HOME"
  if [[ ! -x "$ZK_HOME/bin/zkServer.sh" ]]; then
    echo "ERROR: zkServer.sh not found at $ZK_HOME/bin" >&2
    return 1
  fi
  if [[ ! -x "$HBASE_HOME/bin/start-hbase.sh" ]]; then
    echo "ERROR: start-hbase.sh not found at $HBASE_HOME/bin" >&2
    return 1
  fi
  zk_status() { "$ZK_HOME/bin/zkServer.sh" status 2>/dev/null | head -5 || true; }
  echo "-- ZooKeeper --"
  if echo "$(zk_status)" | grep -qi "Mode:"; then
    echo "ZooKeeper already running"
  else
    "$ZK_HOME/bin/zkServer.sh" start
  fi
  echo "-- HBase --"
  "$HBASE_HOME/bin/start-hbase.sh" || true
  echo "-- wait for doctor (max ~90s) --"
  kart_ensure_jar
  local ok=0 i
  for i in $(seq 1 18); do
    if java -Dkart.root="$KART_ROOT" -jar "$KART_JAR" doctor 2>/dev/null | tee /tmp/kart-doctor-boot.log | grep -q "doctor: OK"; then
      ok=1
      break
    fi
    echo "  retry $i/18…"
    sleep 5
  done
  if [[ "$ok" -eq 1 ]]; then
    echo "=== start-stack: OK ==="
    grep -E "tables=|HBase|ZooKeeper|doctor" /tmp/kart-doctor-boot.log | tail -15 || true
    local MANIFEST="$KART_ROOT/catalog/tdrive_v1_ready.manifest.json"
    if [[ -f "$MANIFEST" ]]; then
      echo "tdrive_v1_ready present — ready for chat / query-nl / query-ir / smoke"
    else
      echo "WARN: missing $MANIFEST" >&2
      echo "  run: ./scripts/kart.sh run build-snapshot --data datasets/tdrive" >&2
    fi
    return 0
  fi
  echo "=== start-stack: doctor not OK yet ===" >&2
  echo "Check ZK/HBase logs; retry: ./scripts/kart.sh doctor" >&2
  tail -30 /tmp/kart-doctor-boot.log 2>/dev/null || true
  return 1
}

kart_stop_stack() {
  echo "=== KART stop-stack ==="
  if [[ -x "$HBASE_HOME/bin/stop-hbase.sh" ]]; then
    "$HBASE_HOME/bin/stop-hbase.sh" || true
  else
    echo "WARN: stop-hbase.sh missing"
  fi
  if [[ -x "$ZK_HOME/bin/zkServer.sh" ]]; then
    "$ZK_HOME/bin/zkServer.sh" stop || true
  else
    echo "WARN: zkServer.sh missing"
  fi
  echo "=== stop-stack: done ==="
}

kart_probe_llm() {
  kart_load_llm_env
  local BASE="${LLM_BASE_URL%/}" MODEL="$LLM_MODEL" KEY="$LLM_API_KEY"
  probe_one() {
    local url="$1" with_json="$2" body tmp code
    if [[ "$with_json" == "1" ]]; then
      body=$(cat <<EOF
{"model":"$MODEL","temperature":0,"messages":[{"role":"user","content":"Return a JSON object with keys ok (boolean true) and model_echo (string repeating the model id you are)."}],"response_format":{"type":"json_object"}}
EOF
)
    else
      body=$(cat <<EOF
{"model":"$MODEL","temperature":0,"messages":[{"role":"user","content":"Reply with exactly: {\"ok\":true}"}]}
EOF
)
    fi
    tmp=$(mktemp)
    code=$(curl -sS -o "$tmp" -w "%{http_code}" \
      -X POST "$url" \
      -H "Authorization: Bearer $KEY" \
      -H "Content-Type: application/json" \
      --connect-timeout 20 \
      --max-time 90 \
      -d "$body" || echo "000")
    echo "PROBE url=$url json_mode=$with_json http=$code"
    python3 - "$tmp" <<'PY'
import json,sys
p=sys.argv[1]
raw=open(p,encoding='utf-8',errors='replace').read()
print('body_prefix=', raw[:800].replace('\n',' '))
try:
  j=json.loads(raw)
  c=j.get('choices') or []
  if c:
    msg=(c[0].get('message') or {}).get('content')
    print('assistant_content=', (msg or '')[:500])
  if 'error' in j:
    print('error=', j['error'])
except Exception as e:
  print('parse_err=', e)
PY
    rm -f "$tmp"
    [[ "$code" == "200" ]]
  }
  echo "=== OI-1 probe model=$MODEL ==="
  local CANDIDATES=("$BASE/v1/chat/completions" "$BASE/chat/completions")
  if [[ "$BASE" == */v1 ]]; then
    CANDIDATES=("$BASE/chat/completions" "${BASE%/v1}/chat/completions")
  fi
  local ok_url="" json_ok="unknown" plain_ok="unknown" url
  for url in "${CANDIDATES[@]}"; do
    echo "--- try $url with json_object ---"
    if probe_one "$url" 1; then
      ok_url="$url"; json_ok="yes"; break
    fi
    echo "json_object failed; try plain ---"
    if probe_one "$url" 0; then
      ok_url="$url"; json_ok="no"; plain_ok="yes"; break
    fi
  done
  if [[ -z "$ok_url" ]]; then
    echo "PROBE_FAILED"
    return 1
  fi
  local JAVA_BASE="${ok_url%/chat/completions}"
  echo "PROBE_OK endpoint=$ok_url java_base=$JAVA_BASE json_object=$json_ok plain=$plain_ok"
  mkdir -p "$ROOT/runs/llm-probe"
  cat > "$ROOT/runs/llm-probe/last.json" <<EOF
{"endpoint":"$ok_url","java_base":"$JAVA_BASE","model":"$MODEL","json_object":"$json_ok","provider":"PinAI"}
EOF
  echo "wrote runs/llm-probe/last.json"
}

kart_wsl_test() {
  local REPO="${KART_MAVEN_REPO}" SRC="${KART_SRC}" DST="${KART_DST}"
  bash "$ROOT/scripts/sync-wsl-workspace.sh"
  bash "$DST/scripts/sync-wsl-workspace.sh" --check
  cd "$DST"
  echo "Using maven.repo.local=$REPO (cwd=$DST)"
  mvn -Dmaven.repo.local="$REPO" test -DtrimStackTrace=false 2>&1 | tee /tmp/kart-test.log
  mvn -Dmaven.repo.local="$REPO" -q package -DskipTests
  mkdir -p "$SRC/target"
  cp -f target/kart.jar "$SRC/target/kart.jar"
  echo "OK jar -> $SRC/target/kart.jar"
  grep -E 'Tests run:|BUILD SUCCESS|BUILD FAILURE' /tmp/kart-test.log | tail -20
}

kart_demo_verify() {
  local LOG=/tmp/kart-demo-verify.log
  : > "$LOG"
  local pass=0 fail=0 skip=0
  ok()   { echo "PASS  $*" | tee -a "$LOG"; pass=$((pass+1)); }
  bad()  { echo "FAIL  $*" | tee -a "$LOG"; fail=$((fail+1)); }
  skip() { echo "SKIP  $*" | tee -a "$LOG"; skip=$((skip+1)); }
  echo "======== KART demo-verify (Live LLM → HBase T-Drive) ========" | tee -a "$LOG"
  echo "root=$ROOT time=$(date)" | tee -a "$LOG"
  kart_ensure_jar
  if [[ -f "$KART_JAR" ]]; then ok "jar present"; else bad "jar missing"; fi
  local HBASE_OK=0
  if java -Dkart.root="$ROOT" -jar "$KART_JAR" doctor > /tmp/kart-dv-doctor.log 2>&1; then
    if grep -q "doctor: OK" /tmp/kart-dv-doctor.log; then
      ok "doctor"; HBASE_OK=1
    else
      bad "doctor (missing OK — run ./scripts/kart.sh up)"
    fi
  else
    bad "doctor (run ./scripts/kart.sh up)"
  fi
  if [[ "$HBASE_OK" -eq 1 ]]; then
    if [[ -f "$ROOT/catalog/tdrive_v1_ready.manifest.json" ]]; then
      ok "catalog tdrive_v1_ready"
    else
      bad "catalog missing tdrive_v1_ready — run build-snapshot"
      HBASE_OK=0
    fi
  fi
  if [[ "$HBASE_OK" -eq 1 ]]; then
    if bash "$ROOT/scripts/run-tdrive-smoke.sh" > /tmp/kart-dv-smoke.out 2>&1; then
      if grep -q 'passed=24' /tmp/kart-dv-smoke.out && grep -q 'failed=0' /tmp/kart-dv-smoke.out; then
        ok "tdrive smoke 24/24"
      else
        bad "smoke summary missing 24/24"
      fi
    else
      bad "smoke script failed"
    fi
  else
    skip "tdrive smoke"
  fi
  kart_load_llm_env || true
  if [[ -n "${LLM_API_KEY:-}" && "$HBASE_OK" -eq 1 ]]; then
    if kart_probe_llm > /tmp/kart-dv-probe.out 2>&1; then
      if grep -q 'PROBE_OK\|PROBE_LLM_OK' /tmp/kart-dv-probe.out; then
        ok "live LLM probe"
      else
        bad "live probe"
      fi
    else
      bad "live probe failed"
    fi
    if kart_chat_easy > /tmp/kart-dv-chat-easy.out 2>&1; then
      if grep -q 'CHAT_EASY_ACCEPTANCE PASS' /tmp/kart-dv-chat-easy.out; then
        ok "chat-easy-acceptance"
      else
        bad "chat-easy-acceptance"
      fi
    else
      if grep -q 'CHAT_EASY_ACCEPTANCE PASS' /tmp/kart-dv-chat-easy.out; then
        ok "chat-easy-acceptance"
      else
        bad "chat-easy-acceptance"
      fi
    fi
  else
    skip "live LLM"
  fi
  echo "" | tee -a "$LOG"
  echo "SUMMARY pass=$pass fail=$fail skip=$skip log=$LOG" | tee -a "$LOG"
  if [[ "$fail" -gt 0 ]]; then
    echo "DEMO_VERIFY_FAIL"
    return 1
  fi
  echo "DEMO_VERIFY_PASS"
  return 0
}

kart_demo_failures() {
  echo "=== demo-failures: unit evidence (CoordinatorFailureTest + dialog COUNT reject) ==="
  mvn -Dmaven.repo.local="${KART_MAVEN_REPO}" -q -Dtest=CoordinatorFailureTest,QueryNlAcceptanceTest test
  echo ""
  echo "Expected status mapping (from tests / dialog):"
  echo "  RESOURCE_EXHAUSTED   — max_candidate_chunks=1 | soft_memory_bytes tiny"
  echo "  DATA_INTEGRITY_ERROR — missing raw chunk on FETCH/BATCH_GET"
  echo "  UNSUPPORTED_QUERY    — COUNT / aggregation rejected in Dialog/DraftIrParser"
  echo "  parse/LLM failure    — INVALID_IR paths in QueryNlAcceptanceTest (ScriptedLlmClient)"
  if [[ "${KART_LIVE:-0}" == "1" ]]; then
    echo ""
    echo "=== demo-failures: live UNSUPPORTED (COUNT) via chat one-shot if available ==="
    echo "COUNT how many trajectories intersect beijing_core" | kart_java chat --once 2>&1 | tail -20 || true
  fi
  echo ""
  echo "demo-failures OK"
}

kart_hbase_evidence() {
  local OUT_DIR="$ROOT/experiments/results"
  mkdir -p "$OUT_DIR"
  local OUT="$OUT_DIR/hbase_version_evidence.txt"
  local MANIFEST="${KART_EXPERIMENT_MANIFEST}"
  {
    echo "=== KART HBase version evidence ==="
    echo "utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "git_commit=$(cd "$ROOT" && git rev-parse HEAD 2>/dev/null || echo n/a)"
    echo "KART_ROOT=$ROOT"
    echo "KART_DST=$KART_DST"
    echo "HBASE_HOME=${HBASE_HOME:-}"
    echo "experiment_manifest=$MANIFEST"
    echo
    echo "--- doctor ---"
    kart_java doctor || true
    echo
    echo "--- HBASE_CLASSPATH hint (TMan-spatial may pin cluster status to 2.1.2) ---"
    if [[ -n "${HBASE_HOME:-}" && -d "$HBASE_HOME" ]]; then
      ls -1 "$HBASE_HOME"/lib 2>/dev/null | grep -iE 'tman|hbase-client|hbase-common' | head -40 || true
    fi
    echo
    echo "--- experiment-scoped tables (prefix from manifest naming) ---"
    echo "Only tables belonging to manifest=$MANIFEST are in experiment scope."
    echo "Other tables on the cluster must be ignored for latency/correctness claims."
  } | tee "$OUT"
  if [[ "$ROOT" == "$KART_DST" && -d "$KART_SRC" ]]; then
    mkdir -p "$KART_SRC/experiments/results"
    cp -f "$OUT" "$KART_SRC/experiments/results/"
  fi
  echo "wrote $OUT"
}

kart_truststore() {
  local SYS_CACERTS="$JAVA_HOME/jre/lib/security/cacerts"
  if [[ ! -f "$SYS_CACERTS" ]]; then
    SYS_CACERTS="$JAVA_HOME/lib/security/cacerts"
  fi
  local OUT_DIR="${HOME}/.kart" OUT_STORE="$OUT_DIR/truststore"
  local ALIAS="trustasia-dv-tls-rsa-ca-2025"
  mkdir -p "$OUT_DIR"
  if [[ ! -f "$OUT_STORE" ]]; then
    cp "$SYS_CACERTS" "$OUT_STORE"
  fi
  if keytool -list -keystore "$OUT_STORE" -storepass changeit -alias "$ALIAS" >/dev/null 2>&1; then
    echo "CA already installed in $OUT_STORE ($ALIAS)"
  else
    local TMP
    TMP=$(mktemp -d)
    trap 'rm -rf "$TMP"' RETURN
    echo | openssl s_client -showcerts -connect api.deepseek.com:443 -servername api.deepseek.com 2>/dev/null \
      | awk '/BEGIN CERTIFICATE/{i++} {print > "'"$TMP"'/cert"i".pem"}'
    local INTER="$TMP/cert2.pem"
    [[ -s "$INTER" ]] || INTER="$TMP/cert1.pem"
    keytool -importcert -noprompt -alias "$ALIAS" -file "$INTER" \
      -keystore "$OUT_STORE" -storepass changeit
    echo "installed $ALIAS into $OUT_STORE"
  fi
  echo "export JAVA_TOOL_OPTIONS=\"-Djavax.net.ssl.trustStore=$OUT_STORE -Djavax.net.ssl.trustStorePassword=changeit\${JAVA_TOOL_OPTIONS:+ \$JAVA_TOOL_OPTIONS}\""
}

kart_chat_easy() {
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
}

kart_chat_handbook() {
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
}

cmd="${1:-help}"
shift || true

case "$cmd" in
  up|start) kart_start_stack ;;
  down|stop) kart_stop_stack ;;
  chat|repl)
    kart_load_llm_env
    export KART_STATUS="${KART_STATUS:-true}"
    kart_java chat --manifest "${KART_EXPERIMENT_MANIFEST:-tdrive_v1_ready}" --dataset tdrive_v1 "$@"
    ;;
  check|demo-verify) kart_demo_verify "$@" ;;
  doctor) kart_java doctor "$@" ;;
  probe) kart_probe_llm "$@" ;;
  smoke) bash "$ROOT/scripts/run-tdrive-smoke.sh" "$@" ;;
  sync) bash "$ROOT/scripts/sync-wsl-workspace.sh" "$@" ;;
  sync-check) bash "$ROOT/scripts/sync-wsl-workspace.sh" --check "$@" ;;
  fit-cost-pack) bash "$ROOT/scripts/publish-cost-calib-pack.sh" "$@" ;;
  gen-cost-ir) bash "$ROOT/scripts/publish-cost-calib-pack.sh" --gen-ir "$@" ;;
  hbase-evidence) kart_hbase_evidence "$@" ;;
  demo-failures) kart_demo_failures "$@" ;;
  test) kart_wsl_test "$@" ;;
  rebuild) kart_rebuild_jar "$@" ;;
  chat-easy)
    kart_load_llm_env
    kart_chat_easy "$@"
    ;;
  chat-handbook)
    kart_load_llm_env
    kart_chat_handbook "$@"
    ;;
  truststore) kart_truststore "$@" ;;
  run)
    kart_load_llm_env
    kart_java "$@"
    ;;
  help|-h|--help)
    cat <<'EOF'
KART — run all of this in WSL (not PowerShell).

Daily:
  ./scripts/kart.sh up
  ./scripts/kart.sh run build-snapshot --data datasets/tdrive   # if catalog missing
  ./scripts/kart.sh chat          # Live LLM → HBase
  ./scripts/kart.sh smoke         # T-Drive vs Oracle 24/24
  ./scripts/kart.sh down

Also: doctor | probe | check | rebuild | test | run <cli-args>
      sync | sync-check | fit-cost-pack | gen-cost-ir | chat-easy | chat-handbook
      demo-failures | hbase-evidence | truststore
Bench: scripts/bench-parse.sh | bench-plan.sh | bench-e2e.sh | bench-all.sh
      (internal) bench-compare.sh | bench-ablation.sh | bench-param.sh
Keepers (5): kart.sh kart-env.sh sync-wsl-workspace.sh run-tdrive-smoke.sh publish-cost-calib-pack.sh
See docs/how-to-run.md / experiments/README.md
EOF
    ;;
  *)
    echo "Unknown: $cmd — try ./scripts/kart.sh help" >&2
    exit 2
    ;;
esac
