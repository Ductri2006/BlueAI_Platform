[CmdletBinding()]
param(
    [string]$ComposeNetwork = 'blueai-auth_default',
    [string]$WorkerApiKey = $env:APP_WORKER_API_KEY,
    [string]$WorkerDocumentId = $env:WORKER_DOCUMENT_ID
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

if ($PSVersionTable.PSVersion.Major -lt 7) { throw 'PowerShell 7+ is required.' }
if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { throw 'docker is required.' }
if ([string]::IsNullOrWhiteSpace($WorkerApiKey)) { throw 'Provide the private worker key through APP_WORKER_API_KEY or -WorkerApiKey.' }

function Assert-Equal {
    param([object]$Actual, [object]$Expected, [string]$Label)
    if ($Actual -ne $Expected) { throw "$Label expected $Expected, got $Actual." }
}

function Invoke-PrivateWorkerRequest {
    param(
        [Parameter(Mandatory)] [string]$Path,
        [Parameter(Mandatory)] [string]$Method,
        [switch]$UseConfiguredKey,
        [string]$Body
    )

    $headers = if ($UseConfiguredKey) {
        '-H "X-Worker-Api-Key: $WORKER_API_KEY"'
    } else {
        '-H "X-Worker-Api-Key: wrong-worker-key"'
    }
    $bodyOption = if ($Body) { " -H 'Content-Type: application/json' --data-raw '$Body'" } else { '' }
    $curlScript = "curl -sS -X $Method -H 'Accept: application/json' $headers$bodyOption -w '`n%{http_code}' 'http://auth-api:8080$Path'"
    $output = @(& docker run --rm --network $ComposeNetwork --env WORKER_API_KEY --entrypoint /bin/sh curlimages/curl:8.12.1 -c $curlScript)
    if ($LASTEXITCODE -ne 0 -or $output.Count -lt 1) { throw "Private worker probe failed for $Method $Path." }
    $code = [int]$output[-1]
    $bodyText = if ($output.Count -gt 1) { ($output[0..($output.Count - 2)] -join "`n") } else { '' }
    $json = $null
    try { if ($bodyText) { $json = $bodyText | ConvertFrom-Json } } catch { }
    [pscustomobject]@{ StatusCode = $code; BodyText = $bodyText; Body = $json }
}

$previousWorkerKey = [Environment]::GetEnvironmentVariable('WORKER_API_KEY', 'Process')
try {
    # Docker inherits the value from process memory by variable name only. No
    # secret-bearing temp file or literal key in the command line is created.
    [Environment]::SetEnvironmentVariable('WORKER_API_KEY', $WorkerApiKey, 'Process')
    & docker network inspect $ComposeNetwork *> $null
    if ($LASTEXITCODE -ne 0) { throw "Docker network '$ComposeNetwork' does not exist or is not reachable." }

    $wrong = Invoke-PrivateWorkerRequest -Path '/api/v1/internal/documents/claim?limit=0' -Method 'POST'
    Assert-Equal $wrong.StatusCode 401 'private worker wrong key'
    Assert-Equal $wrong.Body.code 'INVALID_WORKER_API_KEY' 'private worker wrong key code'

    $valid = Invoke-PrivateWorkerRequest -Path '/api/v1/internal/documents/claim?limit=0' -Method 'POST' -UseConfiguredKey
    Assert-Equal $valid.StatusCode 400 'private worker authenticated invalid limit'
    Assert-Equal $valid.Body.code 'INVALID_CLAIM_LIMIT' 'private worker authenticated validation code'

    if ($WorkerDocumentId) {
        $stale = Invoke-PrivateWorkerRequest `
            -Path "/api/v1/internal/documents/$([uri]::EscapeDataString($WorkerDocumentId))/result" `
            -Method 'PATCH' -UseConfiguredKey `
            -Body '{"attemptId":"body-attempt-private-e2e","status":"FAILED","failureReason":"private stale-attempt assertion"}'
        Assert-Equal $stale.StatusCode 409 'private worker mismatched attempt'
        Assert-Equal $stale.Body.code 'STALE_PROCESSING_ATTEMPT' 'private worker mismatched attempt code'
        Write-Host 'PASS: private worker stale-attempt/retry guard returned 409 without changing a document.'
    } else {
        Write-Host 'SKIP: set WORKER_DOCUMENT_ID to a currently claimed staging document for the stale-attempt/retry assertion.'
    }

    Write-Host 'PASS: worker API key checks ran only from the private Docker network; no worker secret crossed public ingress.'
} finally {
    [Environment]::SetEnvironmentVariable('WORKER_API_KEY', $previousWorkerKey, 'Process')
}
