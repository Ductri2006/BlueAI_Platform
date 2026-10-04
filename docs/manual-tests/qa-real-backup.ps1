param([string]$DocumentId = '61b78d0d-ae15-4743-a0e9-a91d6aed2d7b')
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$qaProject = 'blueai-preprod-20261003'
$qaRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$qaTemporary = Join-Path ([IO.Path]::GetTempPath()) ('blueai-qa-real-backup-' + [guid]::NewGuid().ToString('N'))
$qaResult = [ordered]@{scope='Owned disposable QA only; plaintext never for production';startedAt=[DateTime]::UtcNow.ToString('o');result='FAIL'}
foreach ($qaName in @('auth-api','rag-ai','mysql')) {
    $qaInspection = @(docker inspect "$qaProject-$qaName-1" | ConvertFrom-Json)[0]
    if ($qaInspection.Config.Labels.'com.docker.compose.project' -ne $qaProject) { throw 'Refusing non-QA backup.' }
}
$qaRagInspection = @(docker inspect "$qaProject-rag-ai-1" | ConvertFrom-Json)[0]
$qaApiInspection = @(docker inspect "$qaProject-auth-api-1" | ConvertFrom-Json)[0]
try {
    $env:QA_ACCEPTANCE_PROVIDER_KEY = ($qaRagInspection.Config.Env | Where-Object { $_.StartsWith('GOOGLE_API_KEY=') }).Substring('GOOGLE_API_KEY='.Length)
    $env:QA_ACCEPTANCE_JWT_SECRET = ($qaApiInspection.Config.Env | Where-Object { $_.StartsWith('APP_JWT_SECRET=') }).Substring('APP_JWT_SECRET='.Length)
    New-Item -ItemType Directory -Path $qaTemporary | Out-Null
    $qaComposeFiles = @((Join-Path $PSScriptRoot 'compose.acceptance.yaml'), (Join-Path $PSScriptRoot 'compose.remediation.yaml'))
    $qaEnvFile = Join-Path $qaRoot 'FE/.env.example'
    & (Join-Path $qaRoot 'BE/ops/backup.ps1') -BackupRoot $qaTemporary -AllowPlaintextForRecoveryTesting -ComposeFile $qaComposeFiles -EnvFile $qaEnvFile
    $qaSnapshot = @(Get-ChildItem -LiteralPath $qaTemporary -Directory)[0].FullName
    $qaManifest = Get-Content -LiteralPath (Join-Path $qaSnapshot 'manifest.json') -Raw | ConvertFrom-Json
    if ($qaManifest.consistency -ne 'quiesced-api-and-rag') { throw 'Backup writers were not quiesced.' }
    & (Join-Path $qaRoot 'BE/ops/restore.ps1') -BackupDir $qaSnapshot -TestRestore -EnvFile $qaEnvFile
    # Restore only into tmpfs in an isolated unprivileged container. Never
    # use -Apply on the original QA stack, and never mount its live volumes.
    & docker run --rm --network none --tmpfs /app/.rag-index:uid=10001,gid=10001 --tmpfs /restore/docs:uid=10001,gid=10001 `
        --volume "${qaSnapshot}:/backup:ro" --volume "${PSScriptRoot}:/qa:ro" --entrypoint sh blueai-preprod-rag:remediation-20261004 `
        -c 'set -eu; tar -xzf /backup/faiss-index.tar.gz -C /app/.rag-index; tar -xzf /backup/documents.tar.gz -C /restore/docs; python /qa/qa-verify-restored-faiss.py "$1"' sh $DocumentId
    if ($LASTEXITCODE -ne 0) { throw 'Restored real FAISS or uploaded files did not validate.' }
    $qaResult.result = 'PASS'
    $qaResult.snapshotConsistency = $qaManifest.consistency
    $qaResult.artifacts = @($qaManifest.artifacts | Select-Object name,bytes,sha256)
    $qaResult.providerCalls = 0
} catch {
    $qaResult.error = $_.Exception.Message
    throw
} finally {
    $qaResult.finishedAt = [DateTime]::UtcNow.ToString('o')
    [IO.File]::WriteAllText((Join-Path $PSScriptRoot 'qa-real-backup-results-2026-10-04.json'), ($qaResult | ConvertTo-Json -Depth 6), [Text.UTF8Encoding]::new($false))
    Remove-Item Env:QA_ACCEPTANCE_PROVIDER_KEY -ErrorAction SilentlyContinue
    Remove-Item Env:QA_ACCEPTANCE_JWT_SECRET -ErrorAction SilentlyContinue
    $qaRagInspection = $null; $qaApiInspection = $null
    if (Test-Path -LiteralPath $qaTemporary) {
        $qaResolved = (Resolve-Path -LiteralPath $qaTemporary).Path
        $qaTempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd([IO.Path]::DirectorySeparatorChar)
        if (-not $qaResolved.StartsWith("$qaTempRoot$([IO.Path]::DirectorySeparatorChar)blueai-qa-real-backup-", [StringComparison]::OrdinalIgnoreCase)) { throw 'Refusing unexpected backup cleanup path.' }
        Remove-Item -LiteralPath $qaResolved -Recurse -Force
    }
}
