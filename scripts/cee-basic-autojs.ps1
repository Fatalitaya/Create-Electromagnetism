param(
    [Parameter(Mandatory=$true)][string]$Script,
    [ValidateSet('observe','setup','connect','open','close','discharge','charge','start','start-three-phase','stop','input-low','input-high','input-normal','load-light','load-heavy','load-normal','wiper-low','wiper-high','meter-reset')][string]$Action = 'observe',
    [ValidateSet('components','loads')][string]$Fixture = 'components'
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
if (-not [System.IO.Path]::IsPathRooted($Script) -and -not (Test-Path -LiteralPath $Script)) {
    $Script = Join-Path $PSScriptRoot $Script
}
$outputDirectory = Join-Path $projectRoot 'build'
New-Item -ItemType Directory -Force -Path $outputDirectory | Out-Null
$code = [string](Get-Content -Raw -Encoding UTF8 -LiteralPath $Script)
$code = 'var ceeSceneAction = ' + ($Action | ConvertTo-Json -Compress) + ";`n" +
    'var ceeSceneFixture = ' + ($Fixture | ConvertTo-Json -Compress) + ";`n" + $code
$request = @{jsonrpc='2.0';id=1;method='tools/call';params=@{name='eval_js';arguments=@{side='server';code=$code;timeout_ms=20000}}}
$response = Invoke-RestMethod -Uri 'http://127.0.0.1:25585/mcp' -Method Post -ContentType 'application/json' -Body ($request | ConvertTo-Json -Depth 12) -TimeoutSec 30
@{time=[DateTime]::UtcNow.ToString('o');script=$Script;action=$Action;fixture=$Fixture;code=$code;response=$response} | ConvertTo-Json -Depth 20 -Compress | Add-Content -Encoding UTF8 -LiteralPath (Join-Path $outputDirectory 'cee-basic-transcript.jsonl')
$response | ConvertTo-Json -Depth 20
if ($response.error -or $response.result.isError) { throw 'AutoJS execution failed; see transcript.' }
