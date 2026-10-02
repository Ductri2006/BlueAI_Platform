[CmdletBinding()]
param(
    [string]$BackupRoot = (Join-Path $PSScriptRoot 'backups'),
    [string]$AgeRecipient,
    [switch]$AllowPlaintextForRecoveryTesting,
    [switch]$SkipQuiesce,
    [switch]$KeepQuiesced,
    [string[]]$ComposeFile = @('compose.yaml', 'compose.production.yaml'),
    [string]$EnvFile = '.env'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$global:LASTEXITCODE = 0
. (Join-Path $PSScriptRoot 'deploy-helpers.ps1')
Assert-ArchivePowerShellVersion

if (-not $AgeRecipient -and -not $AllowPlaintextForRecoveryTesting) {
    throw 'Production backup requires -AgeRecipient (age public key). Use -AllowPlaintextForRecoveryTesting only for a disposable local recovery test.'
}

$beRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Push-Location $beRoot
$quiesced = $false
$snapshotId = [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ')
$backupRootPath = [IO.Path]::GetFullPath($BackupRoot)
New-Item -ItemType Directory -Force -Path $backupRootPath | Out-Null
$finalDir = Join-Path $backupRootPath $snapshotId
$stagingDir = Join-Path ([IO.Path]::GetTempPath()) "blueai-backup-$snapshotId-$([guid]::NewGuid().ToString('N'))"
$stagingCreated = $false
$composeArgs = @()
foreach ($file in $ComposeFile) { $composeArgs += @('-f', $file) }
if (Test-Path $EnvFile) { $composeArgs += @('--env-file', $EnvFile) }

function Invoke-Compose {
    param([string[]]$Arguments)
    & docker compose @composeArgs @Arguments
    if ($LASTEXITCODE -ne 0) { throw "docker compose failed: $($Arguments -join ' ')" }
}

function Protect-Artifact {
    param([string]$Source, [string]$Destination)
    if ($AgeRecipient) {
        & age -r $AgeRecipient -o $Destination $Source
        if ($LASTEXITCODE -ne 0) { throw "age encryption failed for $Source" }
    } else {
        Copy-Item -LiteralPath $Source -Destination $Destination
    }
}

function Remove-SafeStagingDirectory {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) { return }
    $full = [IO.Path]::GetFullPath($Path).TrimEnd([IO.Path]::DirectorySeparatorChar)
    $tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd([IO.Path]::DirectorySeparatorChar)
    if (-not $full.StartsWith("$tempRoot$([IO.Path]::DirectorySeparatorChar)blueai-backup-", [StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to recursively delete a staging path outside the generated temp prefix: $full"
    }
    Remove-Item -LiteralPath $full -Recurse -Force
}

try {
    if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { throw 'docker is required.' }
    if ($AgeRecipient -and -not (Get-Command age -ErrorAction SilentlyContinue)) { throw 'age is required when -AgeRecipient is used.' }
    New-Item -ItemType Directory -Force -Path $stagingDir | Out-Null
    $stagingCreated = $true
    New-Item -ItemType Directory -Force -Path $finalDir | Out-Null

    # Refuse an incomplete stack before taking a snapshot. Compose config also
    # validates the production secret gates without printing their values.
    Invoke-Compose @('config', '--quiet')
    if (-not $SkipQuiesce) {
        Write-Host 'Quiescing API and RAG writers before the snapshot...'
        Invoke-Compose @('stop', 'auth-api', 'rag-ai')
        $quiesced = $true
    } else {
        Write-Warning 'SkipQuiesce was requested: document/database/index timestamps are best-effort, not point-in-time consistent.'
    }

    $mysqlSql = Join-Path $stagingDir 'mysql.sql'
    $documentsTar = Join-Path $stagingDir 'documents.tar.gz'
    $faissTar = Join-Path $stagingDir 'faiss-index.tar.gz'

    Write-Host 'Dumping MySQL with a consistent transaction...'
    & docker compose @composeArgs exec -T mysql sh -c 'export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"; exec mysqldump -uroot --single-transaction --routines --triggers --events --hex-blob --set-gtid-purged=OFF --databases blueai_auth' > $mysqlSql
    if ($LASTEXITCODE -ne 0 -or (Get-Item $mysqlSql).Length -lt 128) { throw 'MySQL dump failed or is unexpectedly empty.' }

    Write-Host 'Archiving uploaded documents...'
    & docker compose @composeArgs run --rm --no-deps -T --entrypoint sh auth-api -c 'tar -C /app/storage -czf - .' > $documentsTar
    if ($LASTEXITCODE -ne 0 -or (Get-Item $documentsTar).Length -lt 64) { throw 'Document volume archive failed or is unexpectedly empty.' }

    Write-Host 'Archiving the FAISS/RAG index...'
    & docker compose @composeArgs run --rm --no-deps -T --entrypoint sh rag-ai -c 'tar -C /app/.rag-index -czf - .' > $faissTar
    if ($LASTEXITCODE -ne 0 -or (Get-Item $faissTar).Length -lt 64) { throw 'FAISS index archive failed or is unexpectedly empty.' }

    Assert-TarGzipArchive $documentsTar 'Uploaded documents archive'
    Assert-TarGzipArchive $faissTar 'FAISS index archive'

    $suffix = if ($AgeRecipient) { '.age' } else { '' }
    $artifacts = @(
        @{ name = 'mysql.sql'; source = $mysqlSql; output = "mysql.sql$suffix" },
        @{ name = 'documents.tar.gz'; source = $documentsTar; output = "documents.tar.gz$suffix" },
        @{ name = 'faiss-index.tar.gz'; source = $faissTar; output = "faiss-index.tar.gz$suffix" }
    )
    $manifestArtifacts = @()
    foreach ($artifact in $artifacts) {
        $destination = Join-Path $finalDir $artifact.output
        Protect-Artifact -Source $artifact.source -Destination $destination
        $hash = Get-FileHash -Algorithm SHA256 -LiteralPath $destination
        $manifestArtifacts += [ordered]@{
            name = $artifact.name
            file = $artifact.output
            sha256 = $hash.Hash.ToLowerInvariant()
            bytes = (Get-Item $destination).Length
        }
    }

    $manifest = [ordered]@{
        format = 'blueai-backup-v1'
        snapshotId = $snapshotId
        createdAtUtc = [DateTime]::UtcNow.ToString('o')
        consistency = if ($SkipQuiesce) { 'best-effort-writers-running' } else { 'quiesced-api-and-rag' }
        encryption = if ($AgeRecipient) { 'age' } else { 'plaintext-local-recovery-test-only' }
        includes = @('mysql:blueai_auth', 'uploaded-documents:/app/storage', 'faiss-index:/app/.rag-index')
        excludes = @('redis:ephemeral-widget-context-and-rate-limits')
        artifacts = $manifestArtifacts
    }
    $manifest | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $finalDir 'manifest.json') -Encoding utf8NoBOM
    $manifestHash = Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $finalDir 'manifest.json')
    Write-Host "Backup completed: $finalDir"
    Write-Host "Manifest SHA256: $($manifestHash.Hash.ToLowerInvariant())"
} finally {
    if ($quiesced -and -not $KeepQuiesced) {
        try { Invoke-Compose @('start', 'auth-api', 'rag-ai') } catch { Write-Warning "Could not automatically restart writers: $($_.Exception.Message)" }
    }
    if ($stagingCreated) { Remove-SafeStagingDirectory $stagingDir }
    Pop-Location
}
