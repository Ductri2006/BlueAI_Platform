[CmdletBinding()]
param()
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
$repo=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$qaTempRoot=[IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd([IO.Path]::DirectorySeparatorChar)
# Run the existing regression unchanged, but validate exact absolute disposable
# targets before its cleanup calls. This function exists only in this process.
function Remove-Item {
  [CmdletBinding()]
  param([string]$LiteralPath,[switch]$Recurse,[switch]$Force)
  $resolved=[IO.Path]::GetFullPath($LiteralPath)
  $parent=[IO.Path]::GetDirectoryName($resolved)
  $leaf=[IO.Path]::GetFileName($resolved)
  if($parent -ne $qaTempRoot -or $leaf -notmatch '^blueai-(alert-receipt-[0-9a-f]{12}|alertmanager-webhook-[0-9a-f]{12}|alertmanager-compose-[0-9a-f]{12}\.env)$') {
    throw 'Refusing cleanup outside validated Alertmanager fixture targets.'
  }
  Microsoft.PowerShell.Management\Remove-Item @PSBoundParameters
}
$result=@{stage='real-alertmanager-delivery-to-local-stub';startedAt=[DateTime]::UtcNow.ToString('o');result='FAIL'}
try {
  & (Join-Path $repo 'BE/ops/test-alertmanager.ps1')
  $result.result='PASS'
} catch {$result.message=$_.Exception.Message;throw}
finally {
  $result.finishedAt=[DateTime]::UtcNow.ToString('o')
  [IO.File]::WriteAllText((Join-Path $PSScriptRoot 'qa-alert-delivery-results.json'),($result|ConvertTo-Json),[Text.UTF8Encoding]::new($false))
}
