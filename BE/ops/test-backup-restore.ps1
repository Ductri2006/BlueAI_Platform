[CmdletBinding()]
param([switch]$RunDockerRestore)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$global:LASTEXITCODE = 0
. (Join-Path $PSScriptRoot 'deploy-helpers.ps1')
Assert-ArchivePowerShellVersion
if (-not (Get-Command tar -ErrorAction SilentlyContinue)) { throw 'tar is required for the fixture archive test.' }

$fixture = Join-Path ([IO.Path]::GetTempPath()) "blueai-restore-fixture-$([guid]::NewGuid().ToString('N')) with spaces"
$docs = Join-Path $fixture 'docs-source'
$faiss = Join-Path $fixture 'faiss-source'
$cleanup = $false

# Deliberately shadows Docker in this process: even -Apply below can only call
# this deterministic fake, never an engine or a production Compose project.
function docker {
    $nativeArgs = @($args)
    $phase = 'Other'
    if ($nativeArgs[0] -eq 'compose') {
        if ($nativeArgs -contains 'config') { $phase = 'Config' }
        elseif ($nativeArgs -contains 'stop') { $phase = 'Stop' }
        elseif ($nativeArgs -contains 'ps') { $phase = 'ResolveMysql' }
        elseif ($nativeArgs -contains 'start') { $phase = 'Start' }
        elseif ($nativeArgs -contains 'run') {
            $phase = if ($nativeArgs -contains 'auth-api') { 'Documents' } else { 'Index' }
            $volumeIndex = [array]::IndexOf($nativeArgs, '--volume')
            if ($volumeIndex -lt 0 -or $nativeArgs -contains '--mount') { throw 'Archive restore must use supported --volume, not --mount.' }
            $bind = [string]$nativeArgs[$volumeIndex + 1]
            $target = if ($phase -eq 'Documents') { '/tmp/restore-documents.tar.gz' } else { '/tmp/restore-faiss.tar.gz' }
            $parts = [regex]::Match($bind, '^(.*):(/tmp/restore-(?:documents|faiss)\.tar\.gz):ro$')
            if (-not $parts.Success -or $parts.Groups[2].Value -ne $target -or -not (Test-Path -LiteralPath $parts.Groups[1].Value)) { throw 'Bind specification was not parsed as an existing source and read-only archive target.' }
            if ($parts.Groups[1].Value -notmatch 'with spaces') { throw 'Regression fixture must exercise a bind source containing spaces.' }
            if ($nativeArgs[-2] -ne '-c' -or $nativeArgs[-1] -notmatch 'set -eu; tar -tzf .*; find .*; tar -xzf ') { throw 'Archive restore shell command must validate before replacement and fail closed.' }
        }
    } elseif ($nativeArgs[0] -eq 'cp') { $phase = 'CopySql' }
    elseif ($nativeArgs[0] -eq 'exec') {
        if ($nativeArgs -contains 'rm') { $phase = 'CleanupSql' }
        elseif (($nativeArgs -join ' ') -match 'SELECT COUNT') { $phase = 'Validation' }
        else { $phase = 'Sql' }
    }
    if ($phase -eq 'Other') { throw 'Unexpected Docker call in offline restore regression.' }
    $global:BlueAiRestoreMock.Calls.Add([pscustomobject]@{ Phase = $phase; Arguments = $nativeArgs })
    $global:LASTEXITCODE = if ($phase -eq $global:BlueAiRestoreMock.FailPhase) { 9 } else { 0 }
    if ($phase -eq 'ResolveMysql') { return 'fixture-mysql' }
    if ($phase -eq 'Validation') { return $(if ($global:BlueAiRestoreMock.FailPhase -eq 'EmptyDatabase') { '0' } else { '1' }) }
}

try {
    New-Item -ItemType Directory -Force -Path $docs, $faiss | Out-Null
    $cleanup = $true
    Set-Content -LiteralPath (Join-Path $docs 'document.txt') -Value 'fixture document' -Encoding utf8NoBOM -NoNewline
    Set-Content -LiteralPath (Join-Path $faiss 'index.faiss') -Value 'fixture index' -Encoding utf8NoBOM -NoNewline
    if ([Convert]::ToBase64String([IO.File]::ReadAllBytes((Join-Path $docs 'document.txt'))) -cne [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes('fixture document'))) { throw 'Document fixture bytes must be UTF-8 without BOM or newline.' }
    if ([Convert]::ToBase64String([IO.File]::ReadAllBytes((Join-Path $faiss 'index.faiss'))) -cne [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes('fixture index'))) { throw 'Index fixture bytes must be UTF-8 without BOM or newline.' }
    Set-Content -LiteralPath (Join-Path $fixture 'mysql.sql') -Value @'
CREATE DATABASE IF NOT EXISTS `blueai_auth`;
USE `blueai_auth`;
CREATE TABLE `restore_probe` (`id` bigint NOT NULL);
INSERT INTO `restore_probe` VALUES (42);
'@ -Encoding utf8NoBOM
    & tar -czf (Join-Path $fixture 'documents.tar.gz') -C $docs .
    if ($LASTEXITCODE -ne 0) { throw 'Could not create document fixture archive.' }
    & tar -czf (Join-Path $fixture 'faiss-index.tar.gz') -C $faiss .
    if ($LASTEXITCODE -ne 0) { throw 'Could not create FAISS fixture archive.' }

    $artifactNames = @('mysql.sql', 'documents.tar.gz', 'faiss-index.tar.gz')
    $artifacts = foreach ($name in $artifactNames) {
        $path = Join-Path $fixture $name
        $hash = Get-FileHash -Algorithm SHA256 -LiteralPath $path
        [ordered]@{ name = $name; file = $name; sha256 = $hash.Hash.ToLowerInvariant(); bytes = (Get-Item $path).Length }
    }
    [ordered]@{
        format = 'blueai-backup-v1'
        snapshotId = 'fixture'
        createdAtUtc = [DateTime]::UtcNow.ToString('o')
        consistency = 'quiesced-api-and-rag'
        encryption = 'plaintext-local-recovery-test-only'
        includes = @('mysql:blueai_auth', 'uploaded-documents:/app/storage', 'faiss-index:/app/.rag-index')
        artifacts = $artifacts
    } | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $fixture 'manifest.json') -Encoding utf8NoBOM

    $restore = Join-Path $PSScriptRoot 'restore.ps1'
    $noEnv = Join-Path $fixture 'not-an-env-file'
    & pwsh -NoProfile -File $restore -BackupDir $fixture -DryRun -EnvFile $noEnv
    if ($LASTEXITCODE -ne 0) { throw 'restore.ps1 dry-run fixture failed.' }
    Write-Host 'PASS: encrypted/plaintext-independent checksum, SQL and archive dry-run checks.'

    foreach ($version in @('7.0', '7.3.99')) {
        $rejected = $false
        try { Assert-ArchivePowerShellVersion -Version $version } catch { $rejected = $_.Exception.Message -match '7.4' }
        if (-not $rejected) { throw "Expected PowerShell $version to be rejected before archive redirection." }
    }
    Assert-ArchivePowerShellVersion -Version '7.4'
    foreach ($scriptName in @('backup.ps1', 'restore.ps1')) {
        $source = Get-Content -Raw -LiteralPath (Join-Path $PSScriptRoot $scriptName)
        $guardPosition = $source.IndexOf('Assert-ArchivePowerShellVersion')
        if ($guardPosition -lt 0 -or $guardPosition -gt $source.IndexOf('function ')) { throw "$scriptName must enforce the archive runtime before any native work." }
    }

    # Independent native process, not the Docker mock: include every byte value
    # (including invalid UTF-8) so text-mode redirection cannot pass by accident.
    $nativePayload = [Convert]::ToBase64String([byte[]](0..255))
    $nativeRoundTrip = Join-Path $fixture 'native-redirection.bin'
    & pwsh -NoProfile -NonInteractive -Command "[Console]::OpenStandardOutput().Write([Convert]::FromBase64String('$nativePayload'))" > $nativeRoundTrip
    if ($LASTEXITCODE -ne 0 -or [Convert]::ToBase64String([IO.File]::ReadAllBytes($nativeRoundTrip)) -cne $nativePayload) { throw 'Native binary stdout redirection did not preserve all 256 byte values.' }
    Write-Host 'PASS: independent native-process binary stdout roundtrip preserved all 256 byte values.'

    $corrupt = Join-Path $fixture 'corrupt.tar.gz'
    $bytes = [IO.File]::ReadAllBytes((Join-Path $fixture 'documents.tar.gz'))
    $bytes[$bytes.Length - 8] = $bytes[$bytes.Length - 8] -bxor 1
    [IO.File]::WriteAllBytes($corrupt, $bytes)
    $rejectedArchive = $false
    try { Assert-TarGzipArchive $corrupt 'Corrupt fixture' } catch { $rejectedArchive = $true }
    if (-not $rejectedArchive) { throw 'Expected a corrupt gzip trailer to be rejected.' }
    Write-Host 'PASS: PowerShell 7.4 floor and full-stream corrupt gzip rejection.'

    $previousTmp = [Environment]::GetEnvironmentVariable('TMP', 'Process')
    $previousTemp = [Environment]::GetEnvironmentVariable('TEMP', 'Process')
    $previousTmpDir = [Environment]::GetEnvironmentVariable('TMPDIR', 'Process')
    try {
        [Environment]::SetEnvironmentVariable('TMP', $fixture, 'Process')
        [Environment]::SetEnvironmentVariable('TEMP', $fixture, 'Process')
        [Environment]::SetEnvironmentVariable('TMPDIR', $fixture, 'Process')
        foreach ($failure in @('Stop', 'Sql', 'Documents', 'Index', 'Validation', 'EmptyDatabase', 'None', 'KeepQuiesced')) {
            $global:BlueAiRestoreMock = @{ Calls = [Collections.Generic.List[object]]::new(); FailPhase = $failure }
            $warnings = [Collections.Generic.List[string]]::new()
            $failed = $false
            $failureMessage = ''
            try {
                & $restore -BackupDir $fixture -Apply -IUnderstandDataLoss -KeepQuiesced:($failure -eq 'KeepQuiesced') -EnvFile $noEnv 3>&1 | ForEach-Object {
                    if ($_ -is [Management.Automation.WarningRecord]) { $warnings.Add($_.Message) }
                }
            } catch { $failed = $true; $failureMessage = $_.Exception.Message }
            $starts = @($global:BlueAiRestoreMock.Calls | Where-Object Phase -eq 'Start')
            if ($failure -in @('None', 'KeepQuiesced')) {
                if ($failed) { throw "Successful mocked restore unexpectedly failed: $failure ($failureMessage)" }
                $expectedStarts = if ($failure -eq 'None') { 1 } else { 0 }
                if ($starts.Count -ne $expectedStarts) { throw 'Successful restore must honor -KeepQuiesced.' }
                $phases = @($global:BlueAiRestoreMock.Calls | ForEach-Object { $_.Phase })
                foreach ($phase in @('Sql', 'Documents', 'Index', 'Validation')) {
                    if ($phases -notcontains $phase) { throw "Missing successful restore phase: $phase" }
                    if ($starts.Count -gt 0 -and [array]::IndexOf($phases, $phase) -gt [array]::IndexOf($phases, 'Start')) { throw 'Services started before restore validation.' }
                }
            } elseif (-not $failed -or $starts.Count -ne 0 -or ($warnings -join ' ') -notmatch 'manual recovery') {
                throw "Failure at $failure must throw, warn manual recovery, and never restart any traffic service. Error: $failureMessage; phases: $(@($global:BlueAiRestoreMock.Calls | ForEach-Object { $_.Phase }) -join ','); warnings: $($warnings -join ' ')"
            }
            if ($failure -ne 'Stop' -and @($global:BlueAiRestoreMock.Calls | Where-Object Phase -eq 'CleanupSql').Count -ne 1) { throw 'SQL scratch must be cleaned on both success and partial failure.' }
            Write-Host "PASS: mocked restore $failure; start calls = $($starts.Count)."
        }
        if (@(Get-ChildItem -LiteralPath $fixture -Directory -Filter 'blueai-restore-*').Count -ne 0) { throw 'Restore staging leaked after offline tests.' }
    } finally {
        [Environment]::SetEnvironmentVariable('TMP', $previousTmp, 'Process')
        [Environment]::SetEnvironmentVariable('TEMP', $previousTemp, 'Process')
        [Environment]::SetEnvironmentVariable('TMPDIR', $previousTmpDir, 'Process')
        Remove-Variable -Name BlueAiRestoreMock -Scope Global -ErrorAction SilentlyContinue
    }

    if ($RunDockerRestore) {
        # Opt-in only. These files, credentials and named volumes are generated
        # fixtures, never the real deployment bundle or environment.
        $dockerExecutable = (Get-Command docker -CommandType Application -ErrorAction Stop | Select-Object -First 1).Source
        $composeProject = "blueai-restore-fixture-$([guid]::NewGuid().ToString('N'))"
        $fixtureCompose = Join-Path $fixture 'compose.fixture.yaml'
        $fixtureEnv = Join-Path $fixture 'fixture.env'
        Set-Content -LiteralPath $fixtureEnv -Value '# No real credentials; disposable fixture only.' -Encoding utf8NoBOM
        @"
name: $composeProject
services:
  mysql:
    image: mysql:8.4
    environment:
      MYSQL_ALLOW_EMPTY_PASSWORD: "yes"
      MYSQL_DATABASE: blueai_auth
    volumes:
      - mysql-data:/var/lib/mysql
  auth-api:
    image: alpine:3.21
    command: ["sh", "-c", "mkdir -p /app/storage; sleep 3600"]
    volumes:
      - documents:/app/storage
  rag-ai:
    image: alpine:3.21
    command: ["sh", "-c", "mkdir -p /app/.rag-index; sleep 3600"]
    volumes:
      - faiss:/app/.rag-index
  frontend:
    image: alpine:3.21
    command: ["sleep", "3600"]
volumes:
  mysql-data:
  documents:
  faiss:
"@ | Set-Content -LiteralPath $fixtureCompose -Encoding utf8NoBOM
        function Invoke-FixtureCompose {
            param([string[]]$Arguments)
            & $dockerExecutable compose --project-name $composeProject --project-directory $fixture -f $fixtureCompose --env-file $fixtureEnv @Arguments
            if ($LASTEXITCODE -ne 0) { throw "Isolated fixture Compose failed: $($Arguments -join ' ')" }
        }
        $fixtureStartAttempted = $false
        try {
            $config = (Invoke-FixtureCompose @('config', '--format', 'json') | Out-String | ConvertFrom-Json)
            if ($config.name -ne $composeProject) { throw 'Refusing a fixture with an unexpected Compose project.' }
            foreach ($service in $config.services.PSObject.Properties.Value) {
                if ($null -ne $service.PSObject.Properties['ports']) { throw 'Disposable restore fixture must publish no host ports.' }
            }
            $fixtureStartAttempted = $true
            Invoke-FixtureCompose @('up', '-d')
            $ready = $false
            for ($attempt = 0; $attempt -lt 60; $attempt++) {
                & $dockerExecutable compose --project-name $composeProject -f $fixtureCompose --env-file $fixtureEnv exec -T mysql mysqladmin ping -uroot --silent 2>$null | Out-Null
                if ($LASTEXITCODE -eq 0) { $ready = $true; break }
                Start-Sleep -Seconds 2
            }
            if (-not $ready) { throw 'Disposable fixture MySQL did not become ready.' }
            Invoke-FixtureCompose @('exec', '-T', 'auth-api', 'sh', '-c', 'set -eu; echo stale > /app/storage/stale.txt; echo stale > /app/storage/.old')
            Invoke-FixtureCompose @('exec', '-T', 'rag-ai', 'sh', '-c', 'set -eu; echo stale > /app/.rag-index/stale.txt; echo stale > /app/.rag-index/.old')
            & pwsh -NoProfile -File $restore -BackupDir $fixture -Apply -IUnderstandDataLoss -ComposeFile $fixtureCompose -EnvFile $fixtureEnv
            if ($LASTEXITCODE -ne 0) { throw 'restore.ps1 disposable full-stack restore failed.' }
            Invoke-FixtureCompose @('exec', '-T', 'auth-api', 'sh', '-c', 'set -eu; test "$(cat /app/storage/document.txt)" = "fixture document"; test ! -e /app/storage/stale.txt; test ! -e /app/storage/.old')
            Invoke-FixtureCompose @('exec', '-T', 'rag-ai', 'sh', '-c', 'set -eu; test "$(cat /app/.rag-index/index.faiss)" = "fixture index"; test ! -e /app/.rag-index/stale.txt; test ! -e /app/.rag-index/.old')
            $restoredId = (Invoke-FixtureCompose @('exec', '-T', 'mysql', 'mysql', '-N', '-B', '-uroot', '-e', 'SELECT id FROM blueai_auth.restore_probe') | Out-String).Trim()
            if ($restoredId -ne '42') { throw 'SQL fixture row was not restored.' }
            Invoke-FixtureCompose @('exec', '-T', 'mysql', 'sh', '-c', 'test ! -e /tmp/blueai-restore.sql')
            Write-Host 'PASS: disposable SQL row, document and FAISS contents restored; stale/hidden files replaced and SQL scratch removed.'
        } finally {
            if ($fixtureStartAttempted) {
                if ($composeProject -notmatch '^blueai-restore-fixture-[a-f0-9]{32}$') { throw 'Refusing cleanup of a non-fixture Compose project.' }
                & $dockerExecutable compose --project-name $composeProject --project-directory $fixture -f $fixtureCompose --env-file $fixtureEnv down --volumes --remove-orphans
                if ($LASTEXITCODE -ne 0) {
                    $cleanup = $false
                    throw "Fixture cleanup failed for $composeProject. Retained $fixture for manual cleanup; never use the production bundle to clean this fixture."
                }
            }
        }
    } else {
        Write-Host 'SKIP: opt-in disposable SQL+documents+FAISS volume restore (-RunDockerRestore; no host ports or real credentials).'
    }
} finally {
    if ($cleanup) {
        $full = [IO.Path]::GetFullPath($fixture)
        $tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd([IO.Path]::DirectorySeparatorChar)
        if (-not $full.StartsWith("$tempRoot$([IO.Path]::DirectorySeparatorChar)blueai-restore-fixture-", [StringComparison]::OrdinalIgnoreCase)) {
            throw "Refusing to delete unexpected fixture path: $full"
        }
        Remove-Item -LiteralPath $full -Recurse -Force
    }
}
