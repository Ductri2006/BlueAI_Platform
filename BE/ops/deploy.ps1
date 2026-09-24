[CmdletBinding()]
param(
    [ValidateSet('Validate', 'Deploy', 'Rollback')]
    [string]$Action = 'Validate',
    [string]$ComposeNetwork = 'blueai-auth_default',
    [string]$RollbackComposeDir,
    [switch]$NoBuild,
    [switch]$IUnderstandRollback,
    [string[]]$ComposeFile = @('compose.yaml', 'compose.production.yaml'),
    [string]$EnvFile = '.env'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot 'deploy-helpers.ps1')
$beRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Push-Location $beRoot
$composeArgs = @()
foreach ($file in $ComposeFile) { $composeArgs += @('-f', $file) }
if (Test-Path $EnvFile) { $composeArgs += @('--env-file', $EnvFile) }

function Invoke-Compose {
    param([string[]]$Arguments)
    & docker compose @composeArgs @Arguments
    if ($LASTEXITCODE -ne 0) { throw "docker compose failed: $($Arguments -join ' ')" }
}

function Wait-ForHealthy {
    param([int]$TimeoutSeconds = 180)
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        try {
            # Production intentionally publishes no auth-api port. Probe from
            # the Compose network with a disposable curl client instead of
            # going through FE/nginx (which blocks /actuator/**).
            $response = & docker run --rm --network $ComposeNetwork curlimages/curl:8.12.1 --fail --silent --show-error --max-time 5 http://auth-api:8080/actuator/health | ConvertFrom-Json
            if ($response.status -eq 'UP') {
                Write-Host 'Health gate PASS: auth-api internal actuator health returned UP.'
                return
            }
        } catch { }
        Start-Sleep -Seconds 5
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Health gate failed: auth-api internal actuator health did not return status UP within $TimeoutSeconds seconds."
}

try {
    if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { throw 'docker is required.' }
    if ($Action -eq 'Validate') {
        Invoke-Compose @('config', '--quiet')
        Write-Host 'Compose validation PASS. No services changed.'
        return
    }
    if ($Action -eq 'Deploy') {
        Invoke-Compose @('config', '--quiet')
        $upArgs = @('up', '-d', '--remove-orphans')
        if (-not $NoBuild) { $upArgs += '--build' }
        Invoke-Compose $upArgs
        Wait-ForHealthy
        Write-Host 'Deploy PASS. Run ops/security-e2e.mjs and the staging load test before promoting traffic.'
        return
    }
    if (-not $RollbackComposeDir) { throw '-RollbackComposeDir must point to the previously approved immutable compose bundle.' }
    if (-not $IUnderstandRollback) { throw 'Rollback changes the running deployment. Re-run with -IUnderstandRollback.' }
    $rollbackPath = (Resolve-Path $RollbackComposeDir).Path
    $rollbackFiles = @(
        (Join-Path $rollbackPath 'compose.yaml'),
        (Join-Path $rollbackPath 'compose.production.yaml')
    )
    foreach ($file in $rollbackFiles) { if (-not (Test-Path $file)) { throw "Rollback bundle is missing $file" } }
    $rollbackArgs = @('-f', $rollbackFiles[0], '-f', $rollbackFiles[1])
    if (Test-Path (Join-Path $rollbackPath '.env')) { $rollbackArgs += @('--env-file', (Join-Path $rollbackPath '.env')) }
    $renderedRollbackConfig = & docker compose @rollbackArgs config --format json
    if ($LASTEXITCODE -ne 0) { throw 'Rollback compose bundle failed validation.' }
    Assert-ImmutableRollbackComposeConfig -RenderedConfig ($renderedRollbackConfig -join "`n")
    & docker compose @rollbackArgs up -d --remove-orphans
    if ($LASTEXITCODE -ne 0) { throw 'Rollback start failed.' }
    Wait-ForHealthy
    Write-Host 'Rollback PASS. Previous approved compose bundle is healthy.'
} finally {
    Pop-Location
}
