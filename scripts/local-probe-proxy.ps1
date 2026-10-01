# Probe local Windows for outbound proxy listeners
$ports = 7890,7891,10809,1080,8080,8888,8118,3128,20171,20172
Write-Host "=== listening ports of interest ==="
netstat -an | Select-String "LISTENING" | Select-String ($ports -join "|")
Write-Host "=== proxy-related env ==="
Get-ChildItem Env: | Where-Object { $_.Name -match "proxy|PROXY" } | ForEach-Object { "$($_.Name)=$($_.Value)" }
Write-Host "=== proxy-like processes ==="
Get-Process -ErrorAction SilentlyContinue | Where-Object {
  $_.ProcessName -match "clash|v2ray|xray|sing-box|mihomo|Surge|proxifier|QRServer"
} | ForEach-Object { "$($_.ProcessName) pid=$($_.Id)" }
Write-Host "DONE"
