#!/usr/bin/env bash
# Package WSL KART runtime for transfer to server /home/tyq.
set -euo pipefail
OUT="${1:-/home/jaytang/tmp/kart-migrate}"
mkdir -p "$OUT"

echo "[1/4] code (exclude target/results/runs/.git)"
tar -C /home/jaytang/projects -czf "$OUT/llm-kv-code.tgz" \
  --exclude='llm-kv/target' \
  --exclude='llm-kv/.git' \
  --exclude='llm-kv/experiments/results' \
  --exclude='llm-kv/runs' \
  llm-kv

if [[ -f /home/jaytang/projects/llm-kv/.env ]]; then
  cp -f /home/jaytang/projects/llm-kv/.env "$OUT/llm-kv.env"
  echo "[2/4] .env copied aside (not inside code tarball)"
else
  echo "[2/4] WARN: no .env"
fi

echo "[3/4] hbase-2.2.3 install + data (~4GB)"
tar -C /home/jaytang/envs -czf "$OUT/hbase-2.2.3.tgz" hbase-2.2.3

echo "[4/4] zookeeper-3.4.10 install + data (~5GB)"
tar -C /home/jaytang/envs -czf "$OUT/zookeeper-3.4.10.tgz" zookeeper-3.4.10

ls -lh "$OUT"
echo "DONE -> $OUT"
