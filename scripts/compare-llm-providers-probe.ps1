# Minimal DeepSeek vs Google Gemini compact-plan prompt probe (Windows / Clash optional).
$ErrorActionPreference = 'Stop'
$envFile = (Resolve-Path (Join-Path $PSScriptRoot '..\.env')).Path
$cfg = @{}
Get-Content $envFile | ForEach-Object {
  if ($_ -match '^\s*#' -or $_ -notmatch '=') { return }
  $k, $v = $_.Split('=', 2)
  $cfg[$k.Trim()] = $v.Trim()
}
$bodyMessages = @(
  @{ role = 'system'; content = 'Pick one whitelist plan_id. Reply with only JSON {"plan_id":"P_..."}.' },
  @{ role = 'user'; content = "prompt_version=cbo_llm_compact_v3`nReply ONLY JSON: {`"plan_id`":`"P_...`"}`ncbo_plan_id=P_TZ`nwhitelist=P_T,P_Z`n" }
)

function Probe([string]$Name, [string]$Base, [string]$Model, [string]$Key) {
  Write-Host ""
  Write-Host "=== $Name model=$Model ==="
  $url = ($Base.TrimEnd('/')) + '/chat/completions'
  $times = New-Object System.Collections.Generic.List[double]
  for ($i = 1; $i -le 3; $i++) {
    $payload = @{ model = $Model; temperature = 0; messages = $bodyMessages } | ConvertTo-Json -Depth 8 -Compress
    $sw = [Diagnostics.Stopwatch]::StartNew()
    try {
      $resp = Invoke-RestMethod -Uri $url -Method Post -ContentType 'application/json' `
        -Headers @{ Authorization = "Bearer $Key" } -Body $payload -TimeoutSec 45
      $sw.Stop()
      $t = [math]::Round($sw.Elapsed.TotalSeconds, 3)
      $times.Add($t)
      $c = [string]$resp.choices[0].message.content
      $c = ($c -replace "`r?`n", ' ')
      if ($c.Length -gt 120) { $c = $c.Substring(0, 120) }
      Write-Host ("  try={0} ok time={1}s content={2}" -f $i, $t, $c)
    } catch {
      $sw.Stop()
      $t = [math]::Round($sw.Elapsed.TotalSeconds, 3)
      $times.Add($t)
      Write-Host ("  try={0} FAIL time={1}s err={2}" -f $i, $t, $_.Exception.Message)
    }
  }
  if ($times.Count -gt 0) {
    $arr = $times.ToArray() | Sort-Object
    $med = $arr[[int]($arr.Length / 2)]
    $avg = [math]::Round(($times | Measure-Object -Average).Average, 3)
    Write-Host ("SUMMARY {0}: min={1}s med={2}s max={3}s mean={4}s" -f $Name, ($times | Measure-Object -Minimum).Minimum, $med, ($times | Measure-Object -Maximum).Maximum, $avg)
  }
}

Write-Host 'Windows direct probe'
Probe 'deepseek' $cfg['LLM_BASE_URL'] $cfg['LLM_MODEL'] $cfg['LLM_API_KEY']
Probe 'google' $cfg['GOOGLE_LLM_BASE_URL'] $cfg['GOOGLE_LLM_MODEL'] $cfg['GOOGLE_API_KEY']
