#!/usr/bin/env bash
set -euo pipefail
echo "=== env proxy ==="
env | grep -iE 'proxy' || echo none
echo "=== listeners ==="
ss -ltn | grep -E ':(7890|7891|1080|10809|8080|3128|8888|8118)\s' || echo no_proxy_ports
echo "=== direct deepseek ==="
set +e
curl -sS -o /tmp/ds_direct.txt -w "direct_http=%{http_code} time=%{time_total}\n" \
  --connect-timeout 3 --max-time 5 https://api.deepseek.com/v1/models
echo "direct_exit=$?"
set -e
echo "=== socks5h models (no auth needed for DNS check) ==="
set +e
curl -sS -o /tmp/ds_socks.txt -w "socks_http=%{http_code} time=%{time_total}\n" \
  --connect-timeout 5 --max-time 8 \
  --proxy socks5h://127.0.0.1:1080 \
  https://api.deepseek.com/ \
  || echo "socks_exit=$?"
ss -ltn | grep 1080 || echo no_1080
