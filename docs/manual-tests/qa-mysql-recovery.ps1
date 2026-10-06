[CmdletBinding()]
param(
  [ValidatePattern('^(-[0-9]{4}-[0-9]{2}-[0-9]{2})?$')]
  [string]$ResultSuffix = ''
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$prefix = 'blueai-preprod-db-gate-' + [guid]::NewGuid().ToString('N').Substring(0, 10)
$database = "$prefix-db"
$network = "$prefix-net"
$carrier = "$prefix-artifact"
$volume = "$prefix-target"
$label = "blueai.qa.run=$prefix"
$evidencePath = Join-Path $PSScriptRoot "qa-mysql-recovery-results$ResultSuffix.json"
$results = [Collections.Generic.List[object]]::new()
function Docker-Checked {
  param([string[]]$Arguments)
  & docker @Arguments
  if ($LASTEXITCODE -ne 0) { throw "Disposable Docker operation '$($Arguments[0])' failed." }
}
function Report-Counts {
  param([string[]]$Classes)
  $sum = @{ tests=0; failures=0; errors=0; skipped=0; suites=0 }
  $files = @(Get-ChildItem -LiteralPath (Join-Path $repo 'BE/target/surefire-reports') -Filter 'TEST-*.xml')
  foreach ($file in $files) {
    [xml]$report = Get-Content -LiteralPath $file.FullName -Raw
    if ($Classes.Count -gt 0 -and ($Classes -notcontains ($report.testsuite.name -split '\.')[-1])) { continue }
    foreach ($key in @('tests','failures','errors','skipped')) { $sum[$key] += [int]$report.testsuite.$key }
    $sum.suites++
  }
  return $sum
}
function Run-MavenGate {
  param([string]$Stage,[string[]]$Classes,[string]$Phase='', [string]$Flyway='')
  $jdbc = '?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true'
  $argsList = @('run','--rm','--name',"$prefix-maven",'--network',$network,'--memory','1536m',
    '-v',"${repo}/BE:/workspace",'-v','blueai-preprod-m2-20261003:/root/.m2','-w','/workspace',
    '-e',"MYSQL_MIGRATION_TEST_URL=jdbc:mysql://fixture-db:3306/blueai_migration_test$jdbc",
    '-e','MYSQL_MIGRATION_TEST_USERNAME=root','-e','MYSQL_MIGRATION_TEST_PASSWORD=qa-disposable-gate-root',
    '-e',"MYSQL_AUTH_TEST_URL=jdbc:mysql://fixture-db:3306/blueai_auth_test$jdbc",
    '-e','MYSQL_AUTH_TEST_USERNAME=root','-e','MYSQL_AUTH_TEST_PASSWORD=qa-disposable-gate-root',
    '-e',"MYSQL_FLYWAY_UPGRADE_URL=jdbc:mysql://fixture-db:3306/blueai_flyway_upgrade_test$jdbc",
    '-e','MYSQL_FLYWAY_UPGRADE_USERNAME=root','-e','MYSQL_FLYWAY_UPGRADE_PASSWORD=qa-disposable-gate-root')
  if ($Phase) { $argsList += @('-e',"MYSQL_FLYWAY_UPGRADE_PHASE=$Phase") }
  $argsList += @('maven:3.9.16-eclipse-temurin-21-alpine','mvn','-B',"-Dtest=$($Classes -join ',')")
  if ($Flyway) { $argsList += "-Dflyway.version=$Flyway" }
  $argsList += 'test'
  & docker @argsList
  $code = $LASTEXITCODE
  $results.Add(@{stage=$Stage;exitCode=$code;counts=(Report-Counts $Classes)})
  if ($code -ne 0) { throw "$Stage failed with exit $code" }
}
try {
  # Preserve the fresh full-suite count before conditional suites overwrite XML.
  $results.Add(@{stage='full-backend-verify-before-mysql';counts=(Report-Counts @())})
  Docker-Checked @('network','create','--label',$label,$network) | Out-Null
  Docker-Checked @('run','-d','--name',$database,'--label',$label,'--network',$network,'--network-alias','fixture-db',
    '--tmpfs','/var/lib/mysql','-e','MYSQL_ROOT_PASSWORD=qa-disposable-gate-root','mysql:8.4','--default-time-zone=+07:00') | Out-Null
  $ready=$false
  for($i=0;$i -lt 80;$i++) {
    & docker exec $database mysql -uroot -pqa-disposable-gate-root -N -e 'SELECT 1' 2>$null | Out-Null
    if($LASTEXITCODE -eq 0){$ready=$true;break}
    Start-Sleep -Seconds 1
  }
  if(-not $ready){throw 'Disposable non-UTC MySQL did not start'}
  Docker-Checked @('exec',$database,'mysql','-uroot','-pqa-disposable-gate-root','-e',
    'CREATE DATABASE blueai_migration_test; CREATE DATABASE blueai_auth_test; CREATE DATABASE blueai_flyway_upgrade_test;')
  Run-MavenGate 'mysql84-migrations-nonutc' @('MySqlMigrationIntegrationTest')
  Run-MavenGate 'mysql84-auth-and-ai-concurrency-nonutc' @('AuthMySqlConcurrencyIntegrationTest','AuthAdminMutationMySqlIntegrationTest','AiTaskMySqlRecoveryIntegrationTest','AiTaskMySqlAuthorizationIntegrationTest')
  Run-MavenGate 'flyway1172-seed' @('MySqlFlywayUpgradeIntegrationTest') 'seed' '11.7.2'
  Run-MavenGate 'flyway11203-upgrade' @('MySqlFlywayUpgradeIntegrationTest') 'verify'
  Docker-Checked @('volume','create','--label',$label,$volume) | Out-Null
  Docker-Checked @('create','--name',$carrier,'--label',$label,'-v',"${volume}:/artifacts",'alpine:3.22') | Out-Null
  Docker-Checked @('cp',(Join-Path $repo 'BE/target/blueai-auth-api-0.1.0-SNAPSHOT.jar'),"${carrier}:/artifacts/blueai-auth-api-0.1.0-SNAPSHOT.jar")
  [Environment]::SetEnvironmentVariable('BLUEAI_TEST_ARTIFACT_VOLUME',$volume,'Process')
  Push-Location $repo
  try { & D:/node.exe BE/ops/test-ai-task-recovery.mjs; $crashCode=$LASTEXITCODE }
  finally { Pop-Location; [Environment]::SetEnvironmentVariable('BLUEAI_TEST_ARTIFACT_VOLUME',$null,'Process') }
  $results.Add(@{stage='real-backend-process-crash-recovery';exitCode=$crashCode})
  if($crashCode -ne 0){throw 'Real process crash recovery failed'}
} catch {
  $results.Add(@{stage='harness-error';result='FAIL';message=$_.Exception.Message})
  throw
} finally {
  [IO.File]::WriteAllText($evidencePath,($results | ConvertTo-Json -Depth 8),[Text.UTF8Encoding]::new($false))
  # Exact own names AND ownership label must match before any destructive operation.
  foreach($name in @($carrier,$database)) {
    $raw=& docker inspect $name 2>$null
    if($LASTEXITCODE -eq 0) {
      $obj=($raw -join "`n" | ConvertFrom-Json)[0]
      if($obj.Config.Labels.'blueai.qa.run' -ne $prefix){throw "Refusing to remove unowned fixture $name"}
      & docker rm -f $name | Out-Null
    }
  }
  foreach($resource in @(@{type='network';name=$network},@{type='volume';name=$volume})) {
    $raw=& docker $resource.type inspect $resource.name 2>$null
    if($LASTEXITCODE -eq 0) {
      $obj=($raw -join "`n" | ConvertFrom-Json)[0]
      if($obj.Labels.'blueai.qa.run' -ne $prefix){throw "Refusing to remove unowned fixture $($resource.name)"}
      & docker $resource.type rm $resource.name | Out-Null
    }
  }
}
