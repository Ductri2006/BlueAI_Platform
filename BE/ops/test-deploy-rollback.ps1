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
