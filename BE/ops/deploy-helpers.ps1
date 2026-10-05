function Assert-ArchivePowerShellVersion {
    param([version]$Version = $PSVersionTable.PSVersion.ToString())
    if ($Version -lt [version]'7.4') {
        throw 'PowerShell 7.4+ is required: native stdout redirection must preserve tar.gz bytes.'
    }
}

function Assert-TarGzipArchive {
    param([Parameter(Mandatory)][string]$Path, [string]$Label = 'Archive')
    if (-not (Get-Command tar -ErrorAction SilentlyContinue)) { throw 'tar is required to validate backup archives.' }
    # Drain the whole listing, not just its first member. Also drain gzip so a
    # damaged trailer cannot be hidden by tar stopping at its end-of-archive.
    & tar -tzf $Path | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "$Label is not a readable tar.gz archive." }
    $inputStream = [IO.File]::OpenRead($Path)
    $gzip = $null
    try {
        $gzip = [IO.Compression.GZipStream]::new($inputStream, [IO.Compression.CompressionMode]::Decompress)
        $gzip.CopyTo([IO.Stream]::Null)
    } catch {
        throw "$Label has an invalid gzip stream."
    } finally {
        if ($null -ne $gzip) { $gzip.Dispose() }
        $inputStream.Dispose()
    }
}

function Invoke-DeploymentProbe {
    param([string]$ComposeNetwork, [string]$Url)
    # No credentials, chat/completion calls, or public backend ports required.
    $output = @(& docker run --rm --network $ComposeNetwork curlimages/curl:8.12.1 --silent --show-error --max-time 5 --write-out '\n%{http_code}' $Url)
    if ($LASTEXITCODE -ne 0 -or $output.Count -lt 1 -or [string]$output[-1] -notmatch '^[1-5][0-9]{2}$') {
        throw "Deployment probe transport failed: $Url"
    }
    $bodyText = if ($output.Count -gt 1) { $output[0..($output.Count - 2)] -join "`n" } else { '' }
    $body = $null
    try { $body = $bodyText | ConvertFrom-Json -ErrorAction Stop } catch { }
    [pscustomobject]@{ StatusCode = [int]$output[-1]; BodyText = $bodyText; Body = $body }
}

function Assert-DeploymentAcceptance {
    param([hashtable]$Responses)
    foreach ($gate in @(@('api', 'UP'), @('rag', 'ready'))) {
        $response = $Responses[$gate[0]]
        $statusProperty = if ($null -ne $response.Body) { $response.Body.PSObject.Properties['status'] } else { $null }
        if ($response.StatusCode -ne 200 -or $null -eq $statusProperty -or $statusProperty.Value -ne $gate[1]) {
            throw "Deployment gate failed: $($gate[0]) must return HTTP 200/status $($gate[1])."
        }
    }
    if ($Responses.frontend.StatusCode -ne 200 -or $Responses.frontend.BodyText -notmatch '(?i)<html(?:\s|>)') {
        throw 'Deployment gate failed: frontend must serve its HTML entry point with HTTP 200.'
    }
    $proxy = $Responses.proxy
    $codeProperty = if ($null -ne $proxy.Body) { $proxy.Body.PSObject.Properties['code'] } else { $null }
    if ($proxy.StatusCode -ne 401 -or $null -eq $codeProperty -or $codeProperty.Value -ne 'AUTHENTICATION_REQUIRED') {
        throw 'Deployment gate failed: frontend anonymous auth/me must reach API and return HTTP 401/AUTHENTICATION_REQUIRED.'
    }
    foreach ($boundary in @('actuator', 'worker')) {
        if ($Responses[$boundary].StatusCode -notin @(403, 404)) {
            throw "Deployment gate failed: frontend must deny private $boundary ingress (HTTP 403/404)."
        }
    }
}

function Wait-ForDeploymentAcceptance {
    param([string]$ComposeNetwork, [ValidateRange(0, 3600)][int]$TimeoutSeconds = 180)
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    $lastFailure = 'No acceptance probes completed.'
    do {
        try {
            $responses = @{}
            $urls = [ordered]@{
                api = 'http://auth-api:8080/actuator/health'
                rag = 'http://rag-ai:8000/readiness'
                frontend = 'http://frontend:80/'
                proxy = 'http://frontend:80/api/v1/auth/me'
                actuator = 'http://frontend:80/actuator/health'
                worker = 'http://frontend:80/api/v1/internal/documents/claim?limit=0'
            }
            foreach ($name in $urls.Keys) { $responses[$name] = Invoke-DeploymentProbe $ComposeNetwork $urls[$name] }
            Assert-DeploymentAcceptance $responses
            Write-Host 'Post-start acceptance PASS: API health, RAG operational readiness, frontend HTML/API proxy and private ingress denial.'
            return
        } catch { $lastFailure = $_.Exception.Message }
        if ([DateTime]::UtcNow -ge $deadline) { break }
        Start-Sleep -Seconds 5
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Post-start acceptance failed within $TimeoutSeconds seconds. $lastFailure Keep external traffic closed; investigate or use an approved rollback."
}

function Assert-ImmutableRollbackComposeConfig {
    [CmdletBinding()]
    param([Parameter(Mandatory)][string]$RenderedConfig)

    try {
        $config = $RenderedConfig | ConvertFrom-Json -ErrorAction Stop
    } catch {
        throw 'Rollback Compose config is not valid JSON.'
    }
    if ([string]$config.name -ne 'blueai-auth') {
        throw "Rollback bundle must target Compose project 'blueai-auth'."
    }

    foreach ($serviceName in @('auth-api', 'rag-ai', 'frontend')) {
        $serviceProperty = $config.services.PSObject.Properties[$serviceName]
        if ($null -eq $serviceProperty) {
            throw "Rollback bundle is missing application service '$serviceName'."
        }
        $service = $serviceProperty.Value
        if ($null -ne $service.PSObject.Properties['build']) {
            throw "Rollback service '$serviceName' still has a build context; rollback must use previously published immutable images."
        }
        $image = [string]$service.PSObject.Properties['image'].Value
        if ($image -notmatch '@sha256:[a-fA-F0-9]{64}$') {
            throw "Rollback service '$serviceName' must use an image pinned by sha256 digest."
        }
    }
}
