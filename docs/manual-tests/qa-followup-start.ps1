[CmdletBinding()]
param()
$ErrorActionPreference='Stop'
$qaProject='blueai-preprod-20261003'
$qaRoot=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$qaApi=(docker inspect "$qaProject-auth-api-1" | ConvertFrom-Json)[0]
$qaRag=(docker inspect "$qaProject-rag-ai-1" | ConvertFrom-Json)[0]
foreach ($qaContainer in @($qaApi,$qaRag)) {
  if ($qaContainer.Config.Labels.'com.docker.compose.project' -ne $qaProject) { throw 'Only owned QA containers may be replaced.' }
}
try {
  $qaProvider=$qaRag.Config.Env | Where-Object { $_.StartsWith('GOOGLE_API_KEY=') }
  $qaJwt=$qaApi.Config.Env | Where-Object { $_.StartsWith('APP_JWT_SECRET=') }
  if (-not $qaProvider -or -not $qaJwt) { throw 'QA credentials unavailable.' }
  $env:QA_ACCEPTANCE_PROVIDER_KEY=$qaProvider.Substring('GOOGLE_API_KEY='.Length)
  $env:QA_ACCEPTANCE_JWT_SECRET=$qaJwt.Substring('APP_JWT_SECRET='.Length)
  & docker compose --project-name $qaProject --env-file (Join-Path $qaRoot 'FE/.env.example') `
    -f (Join-Path $PSScriptRoot 'compose.acceptance.yaml') -f (Join-Path $PSScriptRoot 'compose.remediation.yaml') `
    -f (Join-Path $PSScriptRoot 'compose.followup.yaml') up --detach --wait --wait-timeout 240
  if ($LASTEXITCODE -ne 0) { throw 'Follow-up QA failed health gates.' }
  Write-Output 'Updated isolated QA healthy at http://localhost:23000; main application untouched.'
} finally {
  Remove-Item Env:QA_ACCEPTANCE_PROVIDER_KEY,Env:QA_ACCEPTANCE_JWT_SECRET -ErrorAction SilentlyContinue
  $qaProvider=$null; $qaJwt=$null; $qaApi=$null; $qaRag=$null
}
