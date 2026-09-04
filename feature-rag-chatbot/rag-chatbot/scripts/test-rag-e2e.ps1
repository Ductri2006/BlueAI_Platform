param(
    [string]$BaseUrl = "http://127.0.0.1:8080",
    [string]$AdminEmail = "admin@blueai.local",
    [string]$AdminPassword = "BlueAiAdmin123!",
    [string]$CustomerEmail = "rag.customer@blueai.local",
    [string]$CustomerPassword = "RagCustomer123!",
    [string]$SamplePath = "",
    [int]$TimeoutSeconds = 90
)

$ErrorActionPreference = "Stop"
$projectDir = Split-Path -Parent $PSScriptRoot
if (-not $SamplePath) {
    $SamplePath = Join-Path $projectDir "samples\blueai-rag-e2e-sao-bang-47.md"
}
$sample = Get-Item -LiteralPath $SamplePath

$login = Invoke-RestMethod -Uri "$BaseUrl/api/v1/auth/login" -Method Post -ContentType "application/json" -Body (@{
    email = $AdminEmail
    password = $AdminPassword
} | ConvertTo-Json)
$adminHeaders = @{ Authorization = "Bearer $($login.accessToken)" }

$metrics = Invoke-RestMethod -Uri "$BaseUrl/api/v1/system/metrics" -Method Get -Headers $adminHeaders
if (-not $metrics.aiRuntimeReady) {
    $runtimeStatus = if ($metrics.aiRuntimeStatus) { $metrics.aiRuntimeStatus } else { "UNKNOWN" }
    throw "AI runtime is not ready ($runtimeStatus). Start the Python RAG service with GEMINI_API_KEY before running this E2E test."
}

$managedUsers = @(Invoke-RestMethod -Uri "$BaseUrl/api/v1/admin/users" -Method Get -Headers $adminHeaders)
$customer = $managedUsers | Where-Object { $_.email -eq $CustomerEmail } | Select-Object -First 1
if (-not $customer) {
    $customer = Invoke-RestMethod -Uri "$BaseUrl/api/v1/admin/users" -Method Post -Headers $adminHeaders -ContentType "application/json" -Body (@{
        email = $CustomerEmail
        password = $CustomerPassword
        fullName = "RAG Test Customer"
        role = "USER"
    } | ConvertTo-Json)
    Write-Host "Provisioned USER account $CustomerEmail"
} elseif ($customer.status -eq "DISABLED") {
    $customer = Invoke-RestMethod -Uri "$BaseUrl/api/v1/admin/users/$($customer.id)/status" -Method Patch -Headers $adminHeaders -ContentType "application/json" -Body (@{
        status = "ACTIVE"
    } | ConvertTo-Json)
}

$customerLogin = Invoke-RestMethod -Uri "$BaseUrl/api/v1/auth/login" -Method Post -ContentType "application/json" -Body (@{
    email = $CustomerEmail
    password = $CustomerPassword
} | ConvertTo-Json)
$customerHeaders = @{ Authorization = "Bearer $($customerLogin.accessToken)" }

$uploaded = Invoke-RestMethod -Uri "$BaseUrl/api/v1/documents" -Method Post -Headers $adminHeaders -Form @{ file = $sample }
Write-Host "Uploaded $($uploaded.originalFilename) as $($uploaded.id)"

$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
do {
    Start-Sleep -Seconds 2
    $document = Invoke-RestMethod -Uri "$BaseUrl/api/v1/documents/$($uploaded.id)" -Method Get -Headers $adminHeaders
    Write-Host "Document state: $($document.status)"
    if ($document.status -eq "FAILED") {
        throw "Document ingestion failed: $($document.failureReason)"
    }
} until ($document.status -eq "READY" -or (Get-Date) -ge $deadline)

if ($document.status -ne "READY") {
    throw "Document did not become READY within $TimeoutSeconds seconds."
}

$conversation = Invoke-RestMethod -Uri "$BaseUrl/api/v1/conversations" -Method Post -Headers $customerHeaders -ContentType "application/json" -Body (@{
    title = "RAG E2E SAO-BANG-47"
} | ConvertTo-Json)
$question = "Tôi chưa quét QR, chưa dùng dữ liệu và mua gói SAO-BANG-47 cách đây 12 giờ. Tôi có đủ điều kiện hoàn tiền không, tiền về khi nào?"
$answer = Invoke-RestMethod -Uri "$BaseUrl/api/v1/conversations/$($conversation.id)/ask" -Method Post -Headers $customerHeaders -ContentType "application/json" -Body (@{
    question = $question
} | ConvertTo-Json)

$content = [string]$answer.assistantMessage.content
$requiredFacts = @("24 giờ", "3 ngày làm việc", "phương thức thanh toán ban đầu")
foreach ($fact in $requiredFacts) {
    if ($content -notlike "*$fact*") {
        throw "Answer is missing required grounded fact: $fact`n$content"
    }
}

$source = @($answer.sources) | Where-Object {
    $_.documentId -eq $uploaded.id -and
    $_.filename -eq $sample.Name -and
    $_.sourceType -eq "ENTERPRISE_DOCUMENT" -and
    $_.approvalStatus -eq "READY"
} | Select-Object -First 1
if (-not $source) {
    throw "Answer did not include the uploaded enterprise document as a verified source."
}

[pscustomobject]@{
    passed = $true
    customer = $CustomerEmail
    question = $question
    answer = $content
    documentId = $source.documentId
    filename = $source.filename
    chunkId = $source.chunkId
    relevance = $source.score
} | ConvertTo-Json -Depth 5
