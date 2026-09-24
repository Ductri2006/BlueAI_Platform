[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { throw 'docker is required.' }

$suffix = [guid]::NewGuid().ToString('N').Substring(0, 12)
$network = "blueai-alert-test-$suffix"
$receiver = "blueai-alert-receiver-$suffix"
$alertmanager = "blueai-alertmanager-$suffix"
$receiptDir = Join-Path ([IO.Path]::GetTempPath()) "blueai-alert-receipt-$suffix"
$webhookSecret = Join-Path ([IO.Path]::GetTempPath()) "blueai-alertmanager-webhook-$suffix"
$envFixture = Join-Path ([IO.Path]::GetTempPath()) "blueai-alertmanager-compose-$suffix.env"
$stub = (Resolve-Path (Join-Path $PSScriptRoot 'alert-receiver-stub.mjs')).Path
$productionExample = (Resolve-Path (Join-Path $PSScriptRoot '..\.env.production.example')).Path
$composeRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
New-Item -ItemType Directory -Force -Path $receiptDir | Out-Null
$cleanup = $true
try {
    $testWebhookUrl = 'http://receiver:8080/alerts'
    Set-Content -LiteralPath $webhookSecret -Value $testWebhookUrl -NoNewline -Encoding ascii
    $envText = [IO.File]::ReadAllText($productionExample)
    $envText = [regex]::Replace($envText, '(?m)^ALERTMANAGER_WEBHOOK_URL=.*$', "ALERTMANAGER_WEBHOOK_URL=$testWebhookUrl")
    Set-Content -LiteralPath $envFixture -Value $envText -Encoding utf8NoBOM

    # Verify the .env-style file resolves production settings without printing
    # rendered configuration (which contains SMTP/webhook secrets).
    $renderedCompose = & docker compose --project-directory $composeRoot --env-file $envFixture `
      -f (Join-Path $composeRoot 'compose.yaml') -f (Join-Path $composeRoot 'compose.production.yaml') config --quiet
    if ($LASTEXITCODE -ne 0) { throw 'Production Compose could not resolve required settings from an env file.' }
    $renderedCompose = & docker compose --project-directory $composeRoot --env-file $envFixture `
      -f (Join-Path $composeRoot 'compose.yaml') -f (Join-Path $composeRoot 'compose.production.yaml') config --format json
    if ($LASTEXITCODE -ne 0) { throw 'Production Compose could not render the env-file configuration for assertions.' }
    $composeConfig = ($renderedCompose -join "`n") | ConvertFrom-Json
    $apiEnvironment = $composeConfig.services.'auth-api'.environment
    foreach ($requiredSetting in @('APP_RESET_ENABLED', 'APP_RESET_FRONTEND_URL', 'APP_RESET_MAIL_FROM', 'SMTP_HOST', 'APP_RETENTION_ENABLED', 'APP_RETENTION_CONVERSATION', 'APP_DOCUMENT_RETENTION', 'APP_AUTH_RETENTION_SCHEDULED_ENABLED', 'APP_AUTH_RETENTION_RETENTION_DAYS')) {
        if ($null -eq $apiEnvironment.$requiredSetting) { throw "Production auth-api config is missing required forwarded setting '$requiredSetting'." }
    }
    if ($apiEnvironment.APP_RESET_ENABLED -ne 'true' -or $apiEnvironment.APP_RETENTION_ENABLED -ne 'false' -or $apiEnvironment.APP_AUTH_RETENTION_SCHEDULED_ENABLED -ne 'false') {
        throw 'Production Compose did not preserve the required password-reset or fail-safe retention defaults.'
    }

    & docker network create $network | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Could not create alertmanager test network.' }
    $alertConfig = (Resolve-Path (Join-Path $PSScriptRoot 'alertmanager\alertmanager.yml')).Path
    & docker run --rm --entrypoint /bin/amtool `
      -v "${alertConfig}:/etc/alertmanager/alertmanager.yml:ro" `
      -v "${webhookSecret}:/run/secrets/alertmanager_webhook_url:ro" `
      prom/alertmanager:v0.28.1 check-config /etc/alertmanager/alertmanager.yml
    if ($LASTEXITCODE -ne 0) { throw 'Alertmanager configuration or mounted webhook secret failed validation.' }

    $prometheusRules = (Resolve-Path (Join-Path $PSScriptRoot 'prometheus\alerts.yml')).Path
    & docker run --rm --entrypoint promtool -v "${prometheusRules}:/etc/prometheus/alerts.yml:ro" `
      prom/prometheus:v3.2.1 check rules /etc/prometheus/alerts.yml
    if ($LASTEXITCODE -ne 0) { throw 'Prometheus alert rules failed validation.' }

    & docker run -d --rm --name $receiver --network $network --network-alias receiver `
      -e RECEIPT_FILE=/data/received.json -v "${stub}:/stub/alert-receiver-stub.mjs:ro" -v "${receiptDir}:/data" `
      node:22-alpine node /stub/alert-receiver-stub.mjs | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Could not start webhook receiver stub.' }
    & docker run -d --rm --name $alertmanager --network $network --network-alias alertmanager `
      -v "${alertConfig}:/etc/alertmanager/alertmanager.yml:ro" `
      -v "${webhookSecret}:/run/secrets/alertmanager_webhook_url:ro" `
      prom/alertmanager:v0.28.1 --config.file=/etc/alertmanager/alertmanager.yml --web.listen-address=0.0.0.0:9093 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Could not start Alertmanager test container.' }

    $ready = $false
    for ($i = 0; $i -lt 30; $i++) {
        & docker run --rm --network $network curlimages/curl:8.12.1 -fsS http://alertmanager:9093/-/ready 2>$null | Out-Null
        if ($LASTEXITCODE -eq 0) { $ready = $true; break }
        Start-Sleep -Seconds 1
    }
    if (-not $ready) { throw 'Alertmanager test container did not become ready.' }
    $alertStart = [DateTime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ssZ')
    $alertEnd = [DateTime]::UtcNow.AddMinutes(10).ToString('yyyy-MM-ddTHH:mm:ssZ')
    $alert = '[{"labels":{"alertname":"BlueAiWebhookDeliveryTest","severity":"critical"},"annotations":{"summary":"delivery test"},"startsAt":"' + $alertStart + '","endsAt":"' + $alertEnd + '"}]'
    & docker run --rm --network $network curlimages/curl:8.12.1 -fsS -X POST -H 'Content-Type: application/json' --data $alert http://alertmanager:9093/api/v2/alerts | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Could not inject test alert into Alertmanager.' }
    for ($i = 0; $i -lt 60; $i++) {
        if (Test-Path (Join-Path $receiptDir 'received.json')) {
            $receipt = Get-Content -Raw (Join-Path $receiptDir 'received.json') | ConvertFrom-Json
            if ($receipt.alerts.Count -ge 1 -and $receipt.alerts[0].labels.alertname -eq 'BlueAiWebhookDeliveryTest') {
                Write-Host 'PASS: Alertmanager delivered a test alert to the receiver stub.'
                return
            }
        }
        Start-Sleep -Seconds 1
    }
    throw 'Receiver stub did not observe the Alertmanager test alert within 30 seconds.'
} finally {
    if ($cleanup) {
        & docker rm -f $alertmanager $receiver 2>$null | Out-Null
        & docker network rm $network 2>$null | Out-Null
        if (Test-Path $receiptDir) { Remove-Item -LiteralPath $receiptDir -Recurse -Force }
        if (Test-Path $webhookSecret) { Remove-Item -LiteralPath $webhookSecret -Force }
        if (Test-Path $envFixture) { Remove-Item -LiteralPath $envFixture -Force }
    }
}
