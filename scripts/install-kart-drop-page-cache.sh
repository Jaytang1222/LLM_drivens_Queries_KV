#!/usr/bin/env bash
# Install a root-owned page-cache drop helper + NOPASSWD sudoers rule.
# Run once in WSL (will prompt for your password). Do not paste the password into chat.
set -euo pipefail

HELPER="/usr/local/sbin/kart-drop-page-cache"
SUDOERS="/etc/sudoers.d/kart-drop-caches"
USER_NAME="$(id -un)"

echo "Installing helper at $HELPER for user $USER_NAME"

sudo tee "$HELPER" >/dev/null <<'EOF'
#!/bin/sh
# KART formal cold protocol: drop OS page cache.
sync
echo 3 > /proc/sys/vm/drop_caches
EOF
sudo chmod 755 "$HELPER"

# Quote the path only — no shell -c with semicolons (those break sudoers matching).
sudo tee "$SUDOERS" >/dev/null <<EOF
# KART formal cold: allow passwordless page-cache drop for ablation/comparative benches.
$USER_NAME ALL=(root) NOPASSWD: $HELPER
EOF
sudo chmod 440 "$SUDOERS"
sudo visudo -cf "$SUDOERS"

echo "Self-test (must print EXIT:0 with no password prompt):"
sudo -n "$HELPER"
echo "EXIT:$?"
echo "OK. Export KART_DROP_PAGE_CACHE=1 before formal cold runs."
