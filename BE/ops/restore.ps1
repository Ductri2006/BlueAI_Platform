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
if ($PSVersionTable.PSVersion.Major -lt 7) { throw 'PowerShell 7+ is required: native stdout redirection must preserve tar.gz bytes.' }

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
    & tar -tzf $Path | Select-Object -First 1 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "$Label is not a readable tar.gz archive." }
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
        if ([int]$tableCount -lt 1) { throw 'Isolated restore contains no blueai_auth tables.' }
        Write-Host "PASS: isolated MySQL restore imported $tableCount tables; both file archives are readable."
    }

    if (-not $Apply) {
        Write-Host "DRY-RUN PASS: checksums, encryption/decryption, SQL structure and tar archives are valid. No running stack was changed."
        return
    }

    Push-Location $beRoot
    Invoke-Compose @('config', '--quiet')
    Write-Warning 'Stopping API/RAG/frontend writers. MySQL stays running only for the controlled import.'
    Invoke-Compose @('stop', 'frontend', 'auth-api', 'rag-ai')
    $quiesced = $true

    Write-Host 'Importing MySQL dump into the production database...'
    $mysqlContainer = (& docker compose @composeArgs ps -q mysql).Trim()
    if (-not $mysqlContainer) { throw 'Could not resolve the running mysql container.' }
    Copy-ToContainer $mysqlContainer $expanded['mysql.sql'] '/tmp/blueai-restore.sql'
    & docker exec $mysqlContainer sh -c 'export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"; exec mysql -uroot < /tmp/blueai-restore.sql'
    if ($LASTEXITCODE -ne 0) { throw 'Live MySQL restore failed.' }

    Write-Host 'Replacing uploaded documents volume...'
    & docker compose @composeArgs run --rm --no-deps --mount "type=bind,source=$($expanded['documents.tar.gz']),target=/tmp/restore-documents.tar.gz,readonly" --entrypoint sh auth-api -c 'set -eu; rm -rf /app/storage/*; tar -xzf /tmp/restore-documents.tar.gz -C /app/storage'
    if ($LASTEXITCODE -ne 0) { throw 'Uploaded documents restore failed.' }

    Write-Host 'Replacing FAISS/RAG index volume...'
    & docker compose @composeArgs run --rm --no-deps --mount "type=bind,source=$($expanded['faiss-index.tar.gz']),target=/tmp/restore-faiss.tar.gz,readonly" --entrypoint sh rag-ai -c 'set -eu; rm -rf /app/.rag-index/*; tar -xzf /tmp/restore-faiss.tar.gz -C /app/.rag-index'
    if ($LASTEXITCODE -ne 0) { throw 'FAISS index restore failed.' }
    Write-Host 'Restore completed. Start services and run the post-restore smoke/security checks before reopening traffic.'
} finally {
    if ($testContainer) { & docker rm -f $testContainer 2>$null | Out-Null }
    if ($Apply -and $quiesced -and -not $KeepQuiesced) {
        try { Invoke-Compose @('start', 'auth-api', 'rag-ai', 'frontend') } catch { Write-Warning "Could not automatically restart services: $($_.Exception.Message)" }
    }
    if (Get-Location | Where-Object { $_.Path -eq $beRoot }) { Pop-Location }
    if ($tempCreated) { Remove-SafeRestoreDirectory $tempDir }
}
