[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)] [string]$BackupDir,
    [string]$AgeIdentity,
    [switch]$DryRun,
    [switch]$TestRestore,
    [switch]$Apply,
    [switch]$IUnderstandDataLoss,
    [switch]$KeepQuiesced,
    [string[]]$ComposeFile = @('compose.yaml', 'compose.production.yaml'),
    [string]$EnvFile = '.env'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$global:LASTEXITCODE = 0
. (Join-Path $PSScriptRoot 'deploy-helpers.ps1')
Assert-ArchivePowerShellVersion

if ($Apply -and $DryRun) { throw 'Choose -Apply or -DryRun, not both.' }
if ($Apply -and -not $IUnderstandDataLoss) {
    throw 'Live restore is destructive. Re-run with -Apply -IUnderstandDataLoss after validating the backup with -TestRestore.'
}

$beRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$backupPath = (Resolve-Path $BackupDir).Path
$manifestPath = Join-Path $backupPath 'manifest.json'
if (-not (Test-Path $manifestPath)) { throw "Missing manifest.json in $backupPath" }
$manifest = Get-Content -Raw -LiteralPath $manifestPath | ConvertFrom-Json
if ($manifest.format -ne 'blueai-backup-v1') { throw 'Unsupported backup format.' }
if ($manifest.consistency -ne 'quiesced-api-and-rag') { Write-Warning 'This backup was made without quiescing writers; restore is not guaranteed point-in-time consistent.' }

$tempDir = Join-Path ([IO.Path]::GetTempPath()) "blueai-restore-$([guid]::NewGuid().ToString('N'))"
$tempCreated = $false
$composeArgs = @()
foreach ($file in $ComposeFile) { $composeArgs += @('-f', $file) }
if (Test-Path $EnvFile) { $composeArgs += @('--env-file', $EnvFile) }
$quiesced = $false
$restoreSucceeded = $false
$locationPushed = $false
$mysqlContainer = $null
$sqlCopyAttempted = $false
$testContainer = $null

function Invoke-Compose {
    param([string[]]$Arguments)
    & docker compose @composeArgs @Arguments
    if ($LASTEXITCODE -ne 0) { throw "docker compose failed: $($Arguments -join ' ')" }
}

function Expand-BackupArtifact {
    param([object]$Artifact)
    $source = Join-Path $backupPath $Artifact.file
    if (-not (Test-Path $source)) { throw "Missing backup artifact: $($Artifact.file)" }
    $actual = (Get-FileHash -Algorithm SHA256 -LiteralPath $source).Hash.ToLowerInvariant()
    if ($actual -ne $Artifact.sha256.ToLowerInvariant()) { throw "SHA256 mismatch for $($Artifact.file)" }
    $destination = Join-Path $tempDir $Artifact.name
    if ($manifest.encryption -eq 'age') {
        if (-not $AgeIdentity) { throw '-AgeIdentity is required for an age-encrypted backup.' }
        if (-not (Get-Command age -ErrorAction SilentlyContinue)) { throw 'age is required to decrypt this backup.' }
        & age -d -i $AgeIdentity -o $destination $source
        if ($LASTEXITCODE -ne 0) { throw "age decryption failed for $($Artifact.file)" }
    } else {
        if (-not $manifest.encryption.StartsWith('plaintext-')) { throw 'Unknown backup encryption marker.' }
        Copy-Item -LiteralPath $source -Destination $destination
    }
    return $destination
}

function Assert-TarArchive {
    param([string]$Path, [string]$Label)
    Assert-TarGzipArchive $Path $Label
}

function Copy-ToContainer {
    param([string]$Container, [string]$LocalPath, [string]$RemotePath)
    & docker cp $LocalPath "${Container}:$RemotePath"
    if ($LASTEXITCODE -ne 0) { throw "docker cp failed for $LocalPath" }
}

function Remove-SafeRestoreDirectory {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) { return }
    $full = [IO.Path]::GetFullPath($Path).TrimEnd([IO.Path]::DirectorySeparatorChar)
    $tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd([IO.Path]::DirectorySeparatorChar)
    if (-not $full.StartsWith("$tempRoot$([IO.Path]::DirectorySeparatorChar)blueai-restore-", [StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to recursively delete a restore temp path outside the generated prefix: $full"
    }
    Remove-Item -LiteralPath $full -Recurse -Force
}

try {
    New-Item -ItemType Directory -Force -Path $tempDir | Out-Null
    $tempCreated = $true
    $expanded = @{}
    foreach ($artifact in $manifest.artifacts) { $expanded[$artifact.name] = Expand-BackupArtifact $artifact }
    Assert-TarArchive $expanded['documents.tar.gz'] 'Uploaded documents archive'
    Assert-TarArchive $expanded['faiss-index.tar.gz'] 'FAISS index archive'
    if ((Get-Item $expanded['mysql.sql']).Length -lt 32) { throw 'MySQL dump is unexpectedly empty.' }
    if (-not ((Select-String -LiteralPath $expanded['mysql.sql'] -Pattern 'CREATE DATABASE|CREATE TABLE' -Quiet))) { throw 'MySQL dump does not contain schema statements.' }

    if ($TestRestore) {
        if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { throw 'docker is required for -TestRestore.' }
        $testContainer = "blueai-restore-test-$([guid]::NewGuid().ToString('N').Substring(0, 12))"
        Write-Host 'Starting an isolated disposable MySQL for restore verification...'
        # This container is disposable and has no host port. Empty-password
        # root is intentional here so the test does not handle a real secret;
        # the live restore path uses the production container credential.
        & docker run -d --rm --name $testContainer -e MYSQL_ALLOW_EMPTY_PASSWORD=yes mysql:8.4 | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'Could not start isolated MySQL restore test container.' }
        $ready = $false
        for ($i = 0; $i -lt 60; $i++) {
            & docker exec $testContainer mysqladmin ping -uroot --silent 2>$null
            if ($LASTEXITCODE -eq 0) { $ready = $true; break }
            Start-Sleep -Seconds 2
        }
        if (-not $ready) { throw 'Isolated MySQL did not become ready within 120 seconds.' }
        Copy-ToContainer $testContainer $expanded['mysql.sql'] '/tmp/blueai-restore.sql'
        & docker exec $testContainer sh -c 'exec mysql -uroot < /tmp/blueai-restore.sql'
        if ($LASTEXITCODE -ne 0) { throw 'MySQL dump could not be imported into the isolated restore database.' }
        $tableCount = (& docker exec $testContainer mysql -N -B -uroot -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'blueai_auth'").Trim()
        if ($LASTEXITCODE -ne 0 -or $tableCount -notmatch '^\d+$' -or [int]$tableCount -lt 1) { throw 'Isolated restore table validation failed.' }
        Write-Host "PASS: isolated MySQL restore imported $tableCount tables; both file archives are readable."
    }

    if (-not $Apply) {
        Write-Host "DRY-RUN PASS: checksums, encryption/decryption, SQL structure and tar archives are valid. No running stack was changed."
        return
    }

    Push-Location $beRoot
    $locationPushed = $true
    Invoke-Compose @('config', '--quiet')
    Write-Warning 'Stopping API/RAG/frontend writers. MySQL stays running only for the controlled import.'
    # Mark before stop: Compose can partially stop services and then fail.
    $quiesced = $true
    Invoke-Compose @('stop', 'frontend', 'auth-api', 'rag-ai')

    Write-Host 'Importing MySQL dump into the production database...'
    $mysqlContainer = (& docker compose @composeArgs ps -q mysql).Trim()
    if ($LASTEXITCODE -ne 0 -or -not $mysqlContainer) { throw 'Could not resolve the running mysql container.' }
    $sqlCopyAttempted = $true
    Copy-ToContainer $mysqlContainer $expanded['mysql.sql'] '/tmp/blueai-restore.sql'
    & docker exec $mysqlContainer sh -c 'export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"; exec mysql -uroot < /tmp/blueai-restore.sql'
    if ($LASTEXITCODE -ne 0) { throw 'Live MySQL restore failed.' }

    Write-Host 'Replacing uploaded documents volume...'
    $documentsBind = '{0}:/tmp/restore-documents.tar.gz:ro' -f $expanded['documents.tar.gz']
    & docker compose @composeArgs run --rm --no-deps -T --volume $documentsBind --entrypoint sh auth-api -c 'set -eu; tar -tzf /tmp/restore-documents.tar.gz >/dev/null; find /app/storage -mindepth 1 -maxdepth 1 -exec rm -rf -- {} +; tar -xzf /tmp/restore-documents.tar.gz -C /app/storage'
    if ($LASTEXITCODE -ne 0) { throw 'Uploaded documents restore failed.' }

    Write-Host 'Replacing FAISS/RAG index volume...'
    $faissBind = '{0}:/tmp/restore-faiss.tar.gz:ro' -f $expanded['faiss-index.tar.gz']
    & docker compose @composeArgs run --rm --no-deps -T --volume $faissBind --entrypoint sh rag-ai -c 'set -eu; tar -tzf /tmp/restore-faiss.tar.gz >/dev/null; find /app/.rag-index -mindepth 1 -maxdepth 1 -exec rm -rf -- {} +; tar -xzf /tmp/restore-faiss.tar.gz -C /app/.rag-index'
    if ($LASTEXITCODE -ne 0) { throw 'FAISS index restore failed.' }

    $tableCount = (& docker exec $mysqlContainer sh -c 'export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"; exec mysql -N -B -uroot -e "$1"' sh "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'blueai_auth'").Trim()
    if ($LASTEXITCODE -ne 0 -or $tableCount -notmatch '^\d+$' -or [int]$tableCount -lt 1) { throw 'Live restore table validation failed.' }
    $restoreSucceeded = $true
    if ($restoreSucceeded -and -not $KeepQuiesced) {
        Invoke-Compose @('start', 'auth-api', 'rag-ai', 'frontend')
        Write-Host 'Restore completed and services started. Keep external traffic closed until post-restore smoke/security acceptance passes.'
    } else {
        Write-Host 'Restore completed and validated; API/RAG/frontend remain stopped (-KeepQuiesced).'
    }
} finally {
    if ($testContainer) { & docker rm -f $testContainer 2>$null | Out-Null }
    if ($sqlCopyAttempted -and $mysqlContainer) {
        try {
            & docker exec $mysqlContainer rm -f /tmp/blueai-restore.sql
            if ($LASTEXITCODE -ne 0) { throw 'SQL scratch cleanup failed.' }
        } catch { Write-Warning 'Remove /tmp/blueai-restore.sql from the MySQL container during manual recovery; scratch cleanup failed.' }
    }
    if ($Apply -and $quiesced -and -not $restoreSucceeded) {
        Write-Warning 'RESTORE FAILED: API/RAG/frontend will NOT be restarted. Data may be partially replaced. Keep traffic closed and perform manual recovery from a verified snapshot; do not simply start services.'
    }
    if ($locationPushed) { Pop-Location }
    if ($tempCreated) { Remove-SafeRestoreDirectory $tempDir }
}
