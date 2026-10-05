[CmdletBinding()]
param(
    [string]$ComposeNetwork = 'blueai-auth_monitoring',
    [string]$ExternalBaseUrl
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { throw 'docker is required.' }
Write-Host 'Checking private internal actuator health...'
$healthProbe = @(& docker run --rm --network $ComposeNetwork curlimages/curl:8.12.1 -sS -w "`n%{http_code}" http://auth-api:8080/actuator/health)
if ($LASTEXITCODE -ne 0 -or $healthProbe.Count -lt 2) { throw 'Internal health probe failed to execute.' }
$healthCode = [int]$healthProbe[-1]
$healthBody = ($healthProbe[0..($healthProbe.Count - 2)] -join "`n") | ConvertFrom-Json
if ($healthCode -ne 200 -or $healthBody.status -ne 'UP') { throw "Internal /actuator/health expected 200/UP; got $healthCode/$($healthBody.status)." }
Write-Host 'PASS: internal /actuator/health returned 200/UP.'

Write-Host 'Checking private internal Prometheus scrape...'
$probe = @(& docker run --rm --network $ComposeNetwork curlimages/curl:8.12.1 -sS -w "`n%{http_code}" http://auth-api:8080/actuator/prometheus)
if ($LASTEXITCODE -ne 0 -or $probe.Count -lt 1) { throw 'Internal metrics probe failed to execute.' }
$statusCode = [int]$probe[-1]
$body = ($probe[0..($probe.Count - 2)] -join "`n")
if ($statusCode -ne 200) { throw "Internal /actuator/prometheus expected 200 after auth-agent private permit; got $statusCode." }
if (-not ($body.Contains('# HELP') -or $body.Contains('# TYPE'))) { throw 'Internal metrics response is not Prometheus exposition text.' }
Write-Host 'PASS: internal /actuator/prometheus returned 200 and Prometheus exposition markers.'

if ($ExternalBaseUrl) {
    $publicPath = "$($ExternalBaseUrl.TrimEnd('/'))/actuator/prometheus"
    $public = Invoke-WebRequest -Uri $publicPath -UseBasicParsing -SkipHttpErrorCheck -TimeoutSec 15
    if ($public.StatusCode -ne 404) { throw "Public metrics endpoint must be hidden by FE/WAF and return 404; got $($public.StatusCode)." }
    Write-Host 'PASS: public /actuator/prometheus returned 404.'
} else {
    Write-Host 'SKIP: pass -ExternalBaseUrl to verify the public FE/WAF 404 boundary.'
}
