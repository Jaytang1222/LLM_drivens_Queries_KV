# Keep reverse SOCKS tunnel alive so startserver02 egresses via this Windows host.
# Usage: powershell -File scripts/ssh-llm-egress-tunnel.ps1
param(
  [string]$Key = "$env:USERPROFILE\.ssh\id_ed25519_kart_server",
  [string]$Remote = "tyq@10.242.104.108",
  [int]$Port = 1080
)

Write-Host "Starting reverse SOCKS on ${Remote}:127.0.0.1:${Port}"
$ssh = Get-Command ssh -ErrorAction Stop
& $ssh.Source `
  -i $Key `
  -o IdentitiesOnly=yes `
  -o BatchMode=yes `
  -o StrictHostKeyChecking=accept-new `
  -o ServerAliveInterval=30 `
  -o ServerAliveCountMax=3 `
  -o ExitOnForwardFailure=yes `
  -N -T `
  -R "127.0.0.1:${Port}" `
  $Remote
