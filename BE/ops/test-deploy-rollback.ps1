$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot 'deploy-helpers.ps1')

function New-TestComposeConfig {
    param([switch]$BuildContext, [switch]$MutableTag)
    $services = @{}
    foreach ($name in @('auth-api', 'rag-ai', 'frontend')) {
        $digest = ('a' * 64)
        $image = if ($MutableTag) { "registry.example/$name:release-1" } else { "registry.example/$name`@sha256:$digest" }
        $services[$name] = @{ image = $image }
        if ($BuildContext) { $services[$name].build = @{ context = '.' } }
    }
    return (@{ name = 'blueai-auth'; services = $services } | ConvertTo-Json -Depth 8 -Compress)
}

Assert-ImmutableRollbackComposeConfig -RenderedConfig (New-TestComposeConfig)

$rejectedMutable = $false
try { Assert-ImmutableRollbackComposeConfig -RenderedConfig (New-TestComposeConfig -MutableTag) }
catch { $rejectedMutable = $_.Exception.Message -match 'sha256 digest' }
if (-not $rejectedMutable) { throw 'Expected rollback validator to reject mutable image tags.' }

$rejectedBuild = $false
try { Assert-ImmutableRollbackComposeConfig -RenderedConfig (New-TestComposeConfig -BuildContext) }
catch { $rejectedBuild = $_.Exception.Message -match 'build context' }
if (-not $rejectedBuild) { throw 'Expected rollback validator to reject build-based application services.' }

$rejectedMissing = $false
$incomplete = @{ name = 'blueai-auth'; services = @{ 'auth-api' = @{ image = "registry.example/auth-api`@sha256:$('b' * 64)" } } } | ConvertTo-Json -Depth 8 -Compress
try { Assert-ImmutableRollbackComposeConfig -RenderedConfig $incomplete }
catch { $rejectedMissing = $_.Exception.Message -match 'missing application service' }
if (-not $rejectedMissing) { throw 'Expected rollback validator to reject an incomplete application bundle.' }

$wrongProject = New-TestComposeConfig | ConvertFrom-Json
$wrongProject.name = 'blueai-auth-rollback-copy'
$rejectedProject = $false
try { Assert-ImmutableRollbackComposeConfig -RenderedConfig ($wrongProject | ConvertTo-Json -Depth 8 -Compress) }
catch { $rejectedProject = $_.Exception.Message -match "target Compose project 'blueai-auth'" }
if (-not $rejectedProject) { throw 'Expected rollback validator to reject a different Compose project name.' }

Write-Host 'Rollback bundle validation tests PASS (project name and digest-pinned images required; mutable tags, build contexts, and missing services rejected).'

function docker {
    $nativeArgs = @($args)
    $global:BlueAiDeploymentMock.Calls.Add($nativeArgs)
    $global:LASTEXITCODE = 0
    if ($nativeArgs[0] -eq 'compose') {
        if ($nativeArgs -contains 'config') {
            if ($nativeArgs -contains 'json') { return $global:BlueAiDeploymentMock.Config }
            return
        }
        if ($nativeArgs -contains 'up') { return }
        throw 'Unexpected mocked deployment Compose command.'
    }
    if ($nativeArgs[0] -ne 'run' -or $nativeArgs -contains '--publish' -or $nativeArgs -contains '-p') { throw 'Deployment probes must run privately without published ports.' }
    $url = [string]$nativeArgs[-1]
    $mode = $global:BlueAiDeploymentMock.Mode
    switch ($url) {
        'http://auth-api:8080/actuator/health' {
            if ($mode -eq 'NativeFailure') { $global:LASTEXITCODE = 9 }
            '{"status":"UP"}'; '200'
        }
        'http://rag-ai:8000/readiness' {
            switch ($mode) {
                'Rag503' { '{"detail":"No recent worker poll"}'; '503' }
                'LivenessOnly' { '{"status":"healthy"}'; '200' }
                'BadReadinessJson' { '{}'; '200' }
                default { '{"status":"ready","documentWorker":"ready"}'; '200' }
            }
        }
        'http://frontend:80/' {
            '<!doctype html><html><body>fixture</body></html>'
            if ($mode -eq 'Frontend502') { '502' } else { '200' }
        }
        'http://frontend:80/api/v1/auth/me' {
            if ($mode -eq 'SpaInsteadOfProxy') { '<html>fixture SPA fallback</html>'; '200' }
            else { '{"code":"AUTHENTICATION_REQUIRED"}'; '401' }
        }
        'http://frontend:80/actuator/health' { 'not found'; if ($mode -eq 'PrivateActuatorOpen') { '200' } else { '404' } }
        'http://frontend:80/api/v1/internal/documents/claim?limit=0' {
            'not found'; if ($mode -eq 'PrivateWorkerOpen') { '401' } else { '404' }
        }
        default { throw "Unexpected deployment probe (provider or startup/liveness gate not allowed): $url" }
    }
}

$fixture = Join-Path ([IO.Path]::GetTempPath()) "blueai-deploy-fixture-$([guid]::NewGuid().ToString('N')) with spaces"
try {
    New-Item -ItemType Directory -Path $fixture | Out-Null
    foreach ($name in @('compose.yaml', 'compose.production.yaml')) { Set-Content -LiteralPath (Join-Path $fixture $name) -Value '# offline mock only' }
    foreach ($action in @('Deploy', 'Rollback')) {
        foreach ($mode in @('Ready', 'Rag503', 'LivenessOnly', 'BadReadinessJson', 'Frontend502', 'SpaInsteadOfProxy', 'PrivateWorkerOpen', 'PrivateActuatorOpen', 'NativeFailure')) {
            $global:BlueAiDeploymentMock = @{ Calls = [Collections.Generic.List[object]]::new(); Mode = $mode; Config = (New-TestComposeConfig) }
            $failed = $false
            $failureMessage = ''
            try {
                & (Join-Path $PSScriptRoot 'deploy.ps1') -Action $action -NoBuild -AcceptanceTimeoutSeconds 0 -RollbackComposeDir $fixture -IUnderstandRollback -EnvFile (Join-Path $fixture 'no-env-file')
            } catch { $failed = $true; $failureMessage = $_.Exception.Message }
            if ($mode -eq 'Ready') {
                if ($failed) { throw "$action should pass complete mocked acceptance: $failureMessage" }
                $probes = @($global:BlueAiDeploymentMock.Calls | Where-Object { $_[0] -eq 'run' })
                if ($probes.Count -ne 6) { throw "$action must exercise all six post-start probes." }
            } elseif (-not $failed -or $failureMessage -notmatch 'Post-start acceptance failed') {
                throw "$action must fail closed for $mode, even when API health is UP. Error: $failureMessage"
            }
            $starts = @($global:BlueAiDeploymentMock.Calls | Where-Object { $_[0] -eq 'compose' -and $_ -contains 'up' })
            if ($starts.Count -ne 1 -or @($global:BlueAiDeploymentMock.Calls | Where-Object { $_ -contains '--build' }).Count -ne 0) { throw 'Acceptance must be post-start and the regression must never build.' }
            Write-Host "PASS: offline $action acceptance $mode."
        }
    }
} finally {
    Remove-Variable -Name BlueAiDeploymentMock -Scope Global -ErrorAction SilentlyContinue
    if (Test-Path -LiteralPath $fixture) {
        $full = [IO.Path]::GetFullPath($fixture)
        $tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd([IO.Path]::DirectorySeparatorChar)
        if (-not $full.StartsWith("$tempRoot$([IO.Path]::DirectorySeparatorChar)blueai-deploy-fixture-", [StringComparison]::OrdinalIgnoreCase)) { throw 'Refusing cleanup of an unexpected deployment fixture path.' }
        Remove-Item -LiteralPath $full -Recurse -Force
    }
}
