[CmdletBinding()]
param([switch]$RunDockerRestore)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$global:LASTEXITCODE = 0
if ($PSVersionTable.PSVersion.Major -lt 7) { throw 'PowerShell 7+ is required.' }
if (-not (Get-Command tar -ErrorAction SilentlyContinue)) { throw 'tar is required for the fixture archive test.' }

$fixture = Join-Path ([IO.Path]::GetTempPath()) "blueai-restore-fixture-$([guid]::NewGuid().ToString('N'))"
$docs = Join-Path $fixture 'docs-source'
$faiss = Join-Path $fixture 'faiss-source'
$cleanup = $false
try {
    New-Item -ItemType Directory -Force -Path $docs, $faiss | Out-Null
    $cleanup = $true
    Set-Content -LiteralPath (Join-Path $docs 'document.txt') -Value 'fixture document' -Encoding utf8NoBOM
    Set-Content -LiteralPath (Join-Path $faiss 'index.faiss') -Value 'fixture index' -Encoding utf8NoBOM
    Set-Content -LiteralPath (Join-Path $fixture 'mysql.sql') -Value @'
CREATE DATABASE IF NOT EXISTS `blueai_auth`;
USE `blueai_auth`;
CREATE TABLE `restore_probe` (`id` bigint NOT NULL);
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
    & pwsh -NoProfile -File $restore -BackupDir $fixture -DryRun
    if ($LASTEXITCODE -ne 0) { throw 'restore.ps1 dry-run fixture failed.' }
    Write-Host 'PASS: encrypted/plaintext-independent checksum, SQL and archive dry-run checks.'

    if ($RunDockerRestore) {
        & pwsh -NoProfile -File $restore -BackupDir $fixture -TestRestore
        if ($LASTEXITCODE -ne 0) { throw 'restore.ps1 isolated MySQL test failed.' }
        Write-Host 'PASS: isolated MySQL import test.'
    } else {
        Write-Host 'SKIP: isolated MySQL import (use -RunDockerRestore; it starts a disposable mysql:8.4 container).'
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
