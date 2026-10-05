[CmdletBinding()]
param()
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
$prefix='blueai-preprod-timezone-'+[guid]::NewGuid().ToString('N').Substring(0,10)
$database="$prefix-db"; $network="$prefix-net"; $label="blueai.qa.run=$prefix"
$result=@{stage='actual-compose-jdbc-timezone-on-nonutc-mysql';result='FAIL';startedAt=[DateTime]::UtcNow.ToString('o')}
try {
  & docker network create --label $label $network | Out-Null
  if($LASTEXITCODE -ne 0){throw 'Failed to create disposable network'}
  & docker run -d --name $database --label $label --network $network --network-alias fixture-db --tmpfs /var/lib/mysql -e MYSQL_DATABASE=blueai_timezone_probe -e MYSQL_ROOT_PASSWORD=qa-disposable-timezone-root mysql:8.4 --default-time-zone=+07:00 | Out-Null
  if($LASTEXITCODE -ne 0){throw 'Failed to start disposable non-UTC MySQL'}
  $ready=$false
  for($i=0;$i -lt 60;$i++){
    & docker exec $database mysql -uroot -pqa-disposable-timezone-root -N -e 'SELECT 1' 2>$null | Out-Null
    if($LASTEXITCODE -eq 0){$ready=$true;break}; Start-Sleep -Seconds 1
  }
  if(-not $ready){throw 'Disposable MySQL readiness timeout'}
  $probe=(Resolve-Path (Join-Path $PSScriptRoot 'QaJdbcTimezoneProbe.java')).Path
  $output=& docker run --rm --network $network -v blueai-preprod-m2-20261003:/root/.m2:ro -v "${probe}:/probe/QaJdbcTimezoneProbe.java:ro" --entrypoint java maven:3.9.16-eclipse-temurin-21-alpine -cp /root/.m2/repository/com/mysql/mysql-connector-j/9.7.0/mysql-connector-j-9.7.0.jar /probe/QaJdbcTimezoneProbe.java
  if($LASTEXITCODE -ne 0){throw 'JDBC probe failed'}
  $result.output=@($output)
  $output | Write-Output
  if(($output -join "`n") -notmatch 'serverTimezone=UTC \| session=\+07:00'){throw 'Expected non-UTC session not reproduced'}
  if(($output -join "`n") -notmatch 'forceConnectionTimeZoneToSession=true \| session=\+00:00'){throw 'Explicit UTC session probe did not work'}
  $result.result='REPRODUCED_CONFIGURATION_GAP'
} catch {$result.message=$_.Exception.Message;throw}
finally {
  $result.finishedAt=[DateTime]::UtcNow.ToString('o')
  [IO.File]::WriteAllText((Join-Path $PSScriptRoot 'qa-jdbc-timezone-results.json'),($result|ConvertTo-Json -Depth 4),[Text.UTF8Encoding]::new($false))
  $raw=& docker inspect $database 2>$null
  if($LASTEXITCODE -eq 0){$obj=($raw -join "`n"|ConvertFrom-Json)[0];if($obj.Config.Labels.'blueai.qa.run' -ne $prefix){throw 'Refusing to delete unowned database'};& docker rm -f $database | Out-Null}
  $raw=& docker network inspect $network 2>$null
  if($LASTEXITCODE -eq 0){$obj=($raw -join "`n"|ConvertFrom-Json)[0];if($obj.Labels.'blueai.qa.run' -ne $prefix){throw 'Refusing to delete unowned network'};& docker network rm $network | Out-Null}
}
