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
