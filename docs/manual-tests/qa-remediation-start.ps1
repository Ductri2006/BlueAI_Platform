param([switch]$Build)
$ErrorActionPreference = 'Stop'
$qaProject = 'blueai-preprod-20261003'
$qaRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$qaRag = (docker inspect "$qaProject-rag-ai-1" | ConvertFrom-Json)[0]
$qaApi = (docker inspect "$qaProject-auth-api-1" | ConvertFrom-Json)[0]
foreach ($qaContainer in @($qaRag, $qaApi)) {
    if ($qaContainer.Config.Labels.'com.docker.compose.project' -ne $qaProject) {
        throw 'Refusing to read credentials or replace a non-QA container.'
    }
}
# Never print or serialize environment values. Reuse only this approved QA
# stack's credentials in process memory, leaving the real app untouched.
$qaProviderEntry = $qaRag.Config.Env | Where-Object { $_.StartsWith('GOOGLE_API_KEY=') }
$qaJwtEntry = $qaApi.Config.Env | Where-Object { $_.StartsWith('APP_JWT_SECRET=') }
if (!$qaProviderEntry -or !$qaJwtEntry) { throw 'Existing QA credentials are unavailable.' }
try {
    $env:QA_ACCEPTANCE_PROVIDER_KEY = $qaProviderEntry.Substring('GOOGLE_API_KEY='.Length)
    $env:QA_ACCEPTANCE_JWT_SECRET = $qaJwtEntry.Substring('APP_JWT_SECRET='.Length)
    $qaArguments = @('compose', '--project-name', $qaProject, '--env-file', (Join-Path $qaRoot 'FE/.env.example'),
        '-f', (Join-Path $PSScriptRoot 'compose.acceptance.yaml'), '-f', (Join-Path $PSScriptRoot 'compose.remediation.yaml'))
    if ($Build) {
        & docker @qaArguments build --pull
        if ($LASTEXITCODE -ne 0) { throw 'QA image build failed.' }
    }
    & docker @qaArguments up --detach --wait --wait-timeout 240
    if ($LASTEXITCODE -ne 0) { throw 'QA services failed to become healthy.' }
    Write-Output 'Remediation QA healthy at http://localhost:23000 (real app unchanged).'
} finally {
    Remove-Item Env:QA_ACCEPTANCE_PROVIDER_KEY -ErrorAction SilentlyContinue
    Remove-Item Env:QA_ACCEPTANCE_JWT_SECRET -ErrorAction SilentlyContinue
    $qaRag = $null; $qaApi = $null; $qaProviderEntry = $null; $qaJwtEntry = $null
}
