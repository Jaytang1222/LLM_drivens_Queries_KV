$ports = @(7890, 7897, 7891, 10809, 7892)
foreach ($port in $ports) {
  try {
    $resp = Invoke-WebRequest -Uri "http://127.0.0.1:$port" -UseBasicParsing -TimeoutSec 2
    Write-Host "port $port status=$($resp.StatusCode)"
  } catch {
    Write-Host "port $port fail=$($_.Exception.Message)"
  }
}
