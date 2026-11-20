param(
    [Parameter(Mandatory=$true)][string]$Script,
    [ValidateSet('layout','benchmark','observe','setup','connect','open','close','discharge','charge','start','start-three-phase','stop','input-low','input-high','input-normal','load-light','load-heavy','load-normal','wiper-low','wiper-high','meter-reset','signal-low','signal-high','core-down','core-up','core-unity','transformer-unity','transformer-rated','verify-machines')][string]$Action = 'observe',
    [ValidateSet('components','loads','bench')][string]$Fixture = 'components',
    [ValidateCount(3,3)][int[]]$Origin,
    [ValidateRange(1,65535)][int]$Port = 25585,
    [string]$TranscriptPath
)
$ErrorActionPreference = 'Stop'
if (-not [System.IO.Path]::IsPathRooted($Script) -and -not (Test-Path -LiteralPath $Script)) {
    $Script = Join-Path $PSScriptRoot $Script
}
$code = [string](Get-Content -Raw -Encoding UTF8 -LiteralPath $Script)
if ($PSBoundParameters.ContainsKey('Origin')) {
    $code = 'var ceeSceneOrigin = ' + (ConvertTo-Json -InputObject $Origin -Compress) + ";`n" + $code
}
$code = 'var ceeSceneAction = ' + ($Action | ConvertTo-Json -Compress) + ";`n" +
    'var ceeSceneFixture = ' + ($Fixture | ConvertTo-Json -Compress) + ";`n" + $code
$request = @{jsonrpc='2.0';id=1;method='tools/call';params=@{name='eval_js';arguments=@{side='server';code=$code;timeout_ms=20000}}}
$response = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/mcp" -Method Post -ContentType 'application/json' -Body ($request | ConvertTo-Json -Depth 12) -TimeoutSec 30
if ($TranscriptPath) {
    @{time=[DateTime]::UtcNow.ToString('o');script=$Script;action=$Action;fixture=$Fixture;code=$code;response=$response} | ConvertTo-Json -Depth 20 -Compress | Add-Content -Encoding UTF8 -LiteralPath $TranscriptPath
}
$response | ConvertTo-Json -Depth 20
if ($response.error -or $response.result.isError) { throw 'AutoJS execution failed.' }
if ($Action -eq 'benchmark') {
    $evaluation = $response.result.content[0].text | ConvertFrom-Json
    if (-not $evaluation.ok -or -not ($evaluation.value | ConvertFrom-Json).passed) {
        throw 'Creative plant benchmark failed.'
    }
}
