[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$qaRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$qaPrefix = 'blueai-qa-legacy-' + [guid]::NewGuid().ToString('N').Substring(0,10)
$qaDb = "$qaPrefix-db"
$qaNet = "$qaPrefix-net"
$qaLabel = "blueai.qa.run=$qaPrefix"
function Invoke-QaDocker([string[]]$Arguments) {
  & docker @Arguments
  if ($LASTEXITCODE -ne 0) { throw "Fixture Docker $($Arguments[0]) failed." }
}
try {
  Invoke-QaDocker @('network','create','--label',$qaLabel,$qaNet) | Out-Null
  Invoke-QaDocker @('run','-d','--name',$qaDb,'--label',$qaLabel,'--network',$qaNet,'--network-alias','legacy-db',
    '--tmpfs','/var/lib/mysql','-e','MYSQL_ROOT_PASSWORD=qa-legacy-disposable-only','mysql:8.4','--default-time-zone=+07:00') | Out-Null
  $qaReady=$false
  for ($qaAttempt=0; $qaAttempt -lt 90; $qaAttempt++) {
    & docker exec $qaDb mysql --protocol=TCP '--host=127.0.0.1' -uroot -pqa-legacy-disposable-only -N -e 'SELECT 1' 2>$null | Out-Null
    if ($LASTEXITCODE -eq 0) { $qaReady=$true; break }
    Start-Sleep -Seconds 1
  }
  if (-not $qaReady) {
    & docker logs --tail 20 $qaDb
    & docker exec $qaDb mysql --protocol=TCP '--host=127.0.0.1' -uroot -pqa-legacy-disposable-only -N -e 'SELECT 1'
    throw 'Final TCP MySQL server did not become ready.'
  }
  Invoke-QaDocker @('exec',$qaDb,'mysql','--protocol=TCP','-h127.0.0.1','-uroot','-pqa-legacy-disposable-only','-e',
    'CREATE DATABASE blueai_legacy_v1_test; CREATE DATABASE blueai_legacy_v3_test;')
  $qaJdbc='?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true'
  Invoke-QaDocker @('run','--rm','--name',"$qaPrefix-maven",'--label',$qaLabel,'--network',$qaNet,'--memory','1536m',
    '-v',"${qaRoot}/BE:/workspace",'-v','blueai-preprod-m2-20261003:/root/.m2','-w','/workspace',
    '-e',"MYSQL_LEGACY_V1_TEST_URL=jdbc:mysql://legacy-db:3306/blueai_legacy_v1_test$qaJdbc",
    '-e',"MYSQL_LEGACY_V3_TEST_URL=jdbc:mysql://legacy-db:3306/blueai_legacy_v3_test$qaJdbc",
    '-e','MYSQL_MIGRATION_TEST_USERNAME=root','-e','MYSQL_MIGRATION_TEST_PASSWORD=qa-legacy-disposable-only',
    'maven:3.9.16-eclipse-temurin-21-alpine','mvn','-B','-Dtest=MySqlLegacyMigrationIntegrationTest','test')
} finally {
  # Only exact fixtures carrying this run's label may be removed.
  foreach ($qaResource in @(@{kind='container';name=$qaDb},@{kind='network';name=$qaNet})) {
    $qaRaw=& docker $qaResource.kind inspect $qaResource.name 2>$null
    if ($LASTEXITCODE -eq 0) {
      $qaObject=($qaRaw -join "`n" | ConvertFrom-Json)[0]
      $qaLabels=if ($qaResource.kind -eq 'container') { $qaObject.Config.Labels } else { $qaObject.Labels }
      if ($qaLabels.'blueai.qa.run' -ne $qaPrefix) { throw 'Refusing to remove an unowned fixture.' }
      if ($qaResource.kind -eq 'container') { & docker rm -f $qaResource.name | Out-Null }
      else { & docker network rm $qaResource.name | Out-Null }
    }
  }
}
