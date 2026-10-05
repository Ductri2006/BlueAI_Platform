$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function docker {
    $nativeArgs = @($args)
    $global:BlueAiWorkerMock.Calls.Add($nativeArgs)
    $global:LASTEXITCODE = 0
    if (($nativeArgs -join ' ') -match [regex]::Escape($global:BlueAiWorkerMock.Key)) { throw 'Worker key leaked into command arguments.' }
    if ($nativeArgs -contains '--env-file') { throw 'Worker probe must not use a secret-bearing env file.' }
    if ($nativeArgs[0] -eq 'network') {
        if ($global:BlueAiWorkerMock.Mode -eq 'NetworkFailure') { $global:LASTEXITCODE = 9 }
        return
    }
    if ($nativeArgs[0] -ne 'run') { throw 'Unexpected worker probe command.' }
    $envIndex = [array]::IndexOf($nativeArgs, '--env')
    if ($envIndex -lt 0 -or $nativeArgs[$envIndex + 1] -ne 'WORKER_API_KEY') { throw 'Worker probe must pass the process variable by name only.' }
    if ([Environment]::GetEnvironmentVariable('WORKER_API_KEY', 'Process') -ne $global:BlueAiWorkerMock.Key) { throw 'Mock did not receive the fake worker key from process memory.' }
    if ($global:BlueAiWorkerMock.Mode -eq 'ProbeFailure') { $global:LASTEXITCODE = 9; return }
    $command = [string]$nativeArgs[-1]
    if ($command -notmatch 'http://auth-api:8080/api/v1/internal/' -or $command -match 'frontend|gemini|completions') { throw 'Worker probe escaped its private non-provider boundary.' }
    if ($command -match 'wrong-worker-key') { '{"code":"INVALID_WORKER_API_KEY"}'; '401' }
    elseif ($command -match '/result') { '{"code":"STALE_PROCESSING_ATTEMPT"}'; '409' }
    else { '{"code":"INVALID_CLAIM_LIMIT"}'; '400' }
}

# Run in the test process with fake values only; do not inspect any inherited
# application key. The worker must restore this fake baseline on every exit.
[Environment]::SetEnvironmentVariable('WORKER_API_KEY', 'fake-previous-key', 'Process')
try {
    foreach ($mode in @('Success', 'NetworkFailure', 'ProbeFailure')) {
        $global:BlueAiWorkerMock = @{ Calls = [Collections.Generic.List[object]]::new(); Mode = $mode; Key = 'fake-only-key-with-"quote-and-$dollar' }
        $failed = $false
        try {
            & (Join-Path $PSScriptRoot 'worker-private-e2e.ps1') -ComposeNetwork 'offline-fake-network' -WorkerApiKey $global:BlueAiWorkerMock.Key -WorkerDocumentId 'fake-document'
        } catch { $failed = $true }
        if ($failed -ne ($mode -ne 'Success')) { throw "Unexpected worker probe outcome: $mode" }
        if ([Environment]::GetEnvironmentVariable('WORKER_API_KEY', 'Process') -ne 'fake-previous-key') { throw 'Worker probe leaked the temporary environment key after exit.' }
        if ($mode -eq 'Success' -and @($global:BlueAiWorkerMock.Calls | Where-Object { $_[0] -eq 'run' }).Count -ne 3) { throw 'Worker success regression must exercise wrong, valid and stale-attempt probes.' }
        Write-Host "PASS: fake-only worker credential memory/cleanup regression $mode."
    }
    $source = Get-Content -Raw -LiteralPath (Join-Path $PSScriptRoot 'worker-private-e2e.ps1')
    if ($source -match 'Set-Content|Out-File|WriteAll|workerEnvFile|--env-file') { throw 'Worker probe must not write credentials to disk.' }
} finally {
    [Environment]::SetEnvironmentVariable('WORKER_API_KEY', $null, 'Process')
    Remove-Variable -Name BlueAiWorkerMock -Scope Global -ErrorAction SilentlyContinue
}
