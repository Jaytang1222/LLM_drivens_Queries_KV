#!/usr/bin/env bash
# Push compact_v4 arm, patch server jar under /home/tyq, run plan+e2e Ollama census.
set -euo pipefail
KEY="${HOME}/.ssh/id_ed25519_kart_server"
HOST=tyq@10.242.104.108
ROOT=/mnt/f/Projects/LLM_KV

python3 - <<'PY'
from pathlib import Path
root = Path("/mnt/f/Projects/LLM_KV")
for rel in (
  "src/main/java/kart/bench/CboLlmProposalArm.java",
  "scripts/run-server-llm-overhead-local-ollama.sh",
  "scripts/run-server-llm-overhead-e2e-local-ollama.sh",
  "scripts/server-patch-cbo-llm-arm.sh",
):
  p = root / rel
  if p.exists():
    p.write_bytes(p.read_bytes().replace(b"\r\n", b"\n").replace(b"\r", b"\n"))
PY

# Ensure patch helper exists
cat >"$ROOT/scripts/server-patch-cbo-llm-arm.sh" <<'PATCH'
#!/usr/bin/env bash
set -euo pipefail
source ~/.kart_server_env
cd "$KART_DST"
case "$KART_DST" in
  /home/tyq/*) ;;
  *) echo "FAIL: KART_DST=$KART_DST"; exit 1 ;;
esac
JAR="$KART_DST/target/kart.jar"
SRC=src/main/java/kart/bench/CboLlmProposalArm.java
[[ -f "$JAR" ]] || { echo "FAIL: missing $JAR"; exit 1; }
[[ -f "$SRC" ]] || { echo "FAIL: missing $SRC"; exit 1; }
WORKDIR=$(mktemp -d /home/tyq/tmp_patch_XXXXXX)
trap 'rm -rf "$WORKDIR"' EXIT
echo "=== javac patch CboLlmProposalArm into jar ==="
javac -source 1.8 -target 1.8 -cp "$JAR" -d "$WORKDIR" "$SRC"
find "$WORKDIR" -name 'CboLlmProposalArm*.class' | while read -r f; do
  rel="${f#$WORKDIR/}"
  echo "jar uf $rel"
  ( cd "$WORKDIR" && jar uf "$JAR" "$rel" )
done
# Sanity: class string contains v5
tmpdir=$(mktemp -d /home/tyq/tmp_jarcheck_XXXXXX)
trap 'rm -rf "$WORKDIR" "$tmpdir"' EXIT
( cd "$tmpdir" && jar xf "$JAR" kart/bench/CboLlmProposalArm.class )
strings "$tmpdir/kart/bench/CboLlmProposalArm.class" | grep -F 'cbo_llm_compact_v5' \
  || { echo "FAIL: jar missing compact_v5 string"; exit 1; }
echo "PATCH_OK jar=$JAR"
ls -lh "$JAR"
PATCH
python3 - <<'PY'
from pathlib import Path
p = Path("/mnt/f/Projects/LLM_KV/scripts/server-patch-cbo-llm-arm.sh")
p.write_bytes(p.read_bytes().replace(b"\r\n", b"\n").replace(b"\r", b"\n"))
PY

scp -i "$KEY" -o IdentitiesOnly=yes -o BatchMode=yes \
  "$ROOT/src/main/java/kart/bench/CboLlmProposalArm.java" \
  "$ROOT/scripts/server-patch-cbo-llm-arm.sh" \
  "$ROOT/scripts/run-server-llm-overhead-local-ollama.sh" \
  "$ROOT/scripts/run-server-llm-overhead-e2e-local-ollama.sh" \
  "$HOST:/home/tyq/"

ssh -i "$KEY" -o IdentitiesOnly=yes -o BatchMode=yes "$HOST" 'bash -s' <<'REMOTE'
set -euo pipefail
source ~/.kart_server_env
cd "$KART_DST"
case "$KART_DST" in
  /home/tyq/*) ;;
  *) echo "FAIL: KART_DST=$KART_DST"; exit 1 ;;
esac

python3 - <<'PY'
from pathlib import Path
pairs = [
  ("/home/tyq/CboLlmProposalArm.java", "src/main/java/kart/bench/CboLlmProposalArm.java"),
  ("/home/tyq/server-patch-cbo-llm-arm.sh", "scripts/server-patch-cbo-llm-arm.sh"),
  ("/home/tyq/run-server-llm-overhead-local-ollama.sh", "scripts/run-server-llm-overhead-local-ollama.sh"),
  ("/home/tyq/run-server-llm-overhead-e2e-local-ollama.sh", "scripts/run-server-llm-overhead-e2e-local-ollama.sh"),
]
for src, dst in pairs:
  raw = Path(src).read_bytes().replace(b"\r\n", b"\n").replace(b"\r", b"\n")
  Path(dst).write_bytes(raw)
  print("wrote", dst, "bytes", len(raw))
PY
chmod +x scripts/server-patch-cbo-llm-arm.sh \
  scripts/run-server-llm-overhead-local-ollama.sh \
  scripts/run-server-llm-overhead-e2e-local-ollama.sh

bash scripts/server-patch-cbo-llm-arm.sh

echo "======== PLAN census (Ollama) ========"
bash scripts/run-server-llm-overhead-local-ollama.sh

echo "======== E2E census (Ollama) ========"
bash scripts/run-server-llm-overhead-e2e-local-ollama.sh
REMOTE
