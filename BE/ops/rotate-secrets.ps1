[CmdletBinding()]
param(
    [ValidateSet('Plan', 'Generate')]
    [string]$Mode = 'Plan',
    [string]$CurrentEnvFile = '.env',
    [string]$OutputEnvFile = '.env.next',
    [switch]$IUnderstandRotation,
    [switch]$Force
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

if (-not (Test-Path $CurrentEnvFile)) { throw "Missing $CurrentEnvFile" }
$content = Get-Content -Raw -LiteralPath $CurrentEnvFile
if ($Mode -eq 'Plan') {
    Write-Host 'Secret rotation plan (the current application has one active key for each item):'
    Write-Host '1. JWT HS256: APP_JWT_SECRET has no dual-key verifier in the current runtime. Rotate in a maintenance window; all active access JWTs become invalid immediately. Keep refresh-token rotation/re-login messaging ready.'
    Write-Host '2. Worker key: APP_WORKER_API_KEY is shared by auth-api and rag-ai. There is no dual-key environment variable. Stop both writers, change the value in one env bundle, validate, then start both together.'
    Write-Host '3. Provider/API keys: revoke the old provider key only after the new value has passed a staging smoke test.'
    Write-Host '4. Rollback: retain the previous sealed env bundle for the approved rollback window; never print it or commit it.'
    Write-Host 'Use -Mode Generate to create .env.next, review it out-of-band, then deploy with ops/deploy.ps1 during the window.'
    return
}
if (-not $IUnderstandRotation) { throw 'Generate only creates a candidate file. Confirm the planned maintenance/rollback window with -IUnderstandRotation.' }
if ((Test-Path $OutputEnvFile) -and -not $Force) { throw "$OutputEnvFile already exists. Choose a new path or pass -Force after verifying it is safe to overwrite." }

$rng = [Security.Cryptography.RandomNumberGenerator]::Create()
try {
    $jwtBytes = New-Object byte[] 32
    $workerBytes = New-Object byte[] 32
    $saltBytes = New-Object byte[] 32
    $rng.GetBytes($jwtBytes); $rng.GetBytes($workerBytes); $rng.GetBytes($saltBytes)
} finally { $rng.Dispose() }
$replacements = @{
    'APP_JWT_SECRET' = [Convert]::ToBase64String($jwtBytes)
    'APP_WORKER_API_KEY' = [Convert]::ToBase64String($workerBytes)
    'APP_WIDGET_CLIENT_KEY_SALT' = [Convert]::ToBase64String($saltBytes)
}
foreach ($key in $replacements.Keys) {
    $line = "^$([regex]::Escape($key))=.*$"
    if ($content -match $line) { $content = [regex]::Replace($content, $line, "$key=$($replacements[$key])", [Text.RegularExpressions.RegexOptions]::Multiline) }
    else { $content += "`r`n$key=$($replacements[$key])`r`n" }
}
Set-Content -LiteralPath $OutputEnvFile -Value $content -Encoding utf8NoBOM
Write-Host "Generated candidate secret bundle at $OutputEnvFile. It is not active until auth-api and rag-ai are restarted together."
