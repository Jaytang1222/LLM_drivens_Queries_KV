# Reverse-forward local Clash Verge HTTP proxy to the server.
# Local Clash mixed/HTTP port (default 7897) -> server 127.0.0.1:17890
param(
  [string]$Key = "$env:USERPROFILE\.ssh\id_ed25519_kart_server",
  [string]$Remote = "tyq@10.242.104.108",
  [int]$LocalClashPort = 7897,
  [int]$RemoteProxyPort = 17890
)

Write-Host "Forwarding local Clash :$LocalClashPort -> ${Remote}:127.0.0.1:$RemoteProxyPort"

# Best-effort free stale remote listener.
ssh -i $Key -o IdentitiesOnly=yes -o BatchMode=yes -o ConnectTimeout=8 $Remote `
  "fuser -k ${RemoteProxyPort}/tcp 2>/dev/null || true; ss -ltn | grep -E ':$RemoteProxyPort\s' || echo remote_port_free"

$ssh = Get-Command ssh -ErrorAction Stop
& $ssh.Source `
  -i $Key `
  -o IdentitiesOnly=yes `
  -o BatchMode=yes `
  -o StrictHostKeyChecking=accept-new `
  -o ServerAliveInterval=20 `
  -o ServerAliveCountMax=3 `
  -o ExitOnForwardFailure=yes `
  -N -T `
  -R "127.0.0.1:${RemoteProxyPort}:127.0.0.1:${LocalClashPort}" `
  $Remote
