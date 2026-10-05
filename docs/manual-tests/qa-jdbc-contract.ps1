$ErrorActionPreference = 'Stop'
$qaPrefix = 'blueai-utc-contract-' + [guid]::NewGuid().ToString('N').Substring(0, 10)
$qaNetwork = "$qaPrefix-net"; $qaDatabase = "$qaPrefix-db"
$qaLabel = "blueai.qa.run=$qaPrefix"
$qaCompose = Get-Content (Join-Path $PSScriptRoot '../../BE/compose.yaml') -Raw
$qaUrl = [regex]::Match($qaCompose, 'DB_URL:\s*(jdbc:mysql:[^\r\n]+)').Groups[1].Value
if (!$qaUrl.Contains('forceConnectionTimeZoneToSession=true')) { throw 'Missing UTC deployment setting' }
$qaUrl = $qaUrl.Replace('mysql:3306/blueai_auth', 'fixture-db:3306/blueai_timezone_probe')
$qaSource = (Resolve-Path (Join-Path $PSScriptRoot 'QaJdbcContractProbe.java')).Path
try {
    docker network create --label $qaLabel $qaNetwork | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Network creation failed' }
    docker run -d --name $qaDatabase --label $qaLabel --network $qaNetwork --network-alias fixture-db --tmpfs /var/lib/mysql -e MYSQL_DATABASE=blueai_timezone_probe -e MYSQL_ROOT_PASSWORD=qa-disposable-timezone-root mysql:8.4 --default-time-zone=+07:00 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Database creation failed' }
    $qaReady = $false
    for ($qaAttempt = 0; $qaAttempt -lt 60; $qaAttempt++) {
        docker exec $qaDatabase mysql -uroot -pqa-disposable-timezone-root -N -e 'SELECT 1' 2>$null | Out-Null
        if ($LASTEXITCODE -eq 0) { $qaReady = $true; break }
        Start-Sleep -Seconds 1
    }
    if (!$qaReady) { throw 'Database readiness timeout' }
    docker run --rm --network $qaNetwork -v blueai-preprod-m2-20261003:/root/.m2:ro -v "${qaSource}:/probe/QaJdbcContractProbe.java:ro" --entrypoint java maven:3.9.16-eclipse-temurin-21-alpine -cp /root/.m2/repository/com/mysql/mysql-connector-j/9.7.0/mysql-connector-j-9.7.0.jar /probe/QaJdbcContractProbe.java $qaUrl
    if ($LASTEXITCODE -ne 0) { throw 'Actual deployment JDBC contract failed' }
} finally {
    $qaContainer = docker inspect $qaDatabase 2>$null | ConvertFrom-Json
    if ($qaContainer) {
        if ($qaContainer[0].Config.Labels.'blueai.qa.run' -ne $qaPrefix) { throw 'Refusing unowned cleanup' }
        docker rm -f $qaDatabase | Out-Null
    }
    $qaNet = docker network inspect $qaNetwork 2>$null | ConvertFrom-Json
    if ($qaNet) {
        if ($qaNet[0].Labels.'blueai.qa.run' -ne $qaPrefix) { throw 'Refusing unowned network cleanup' }
        docker network rm $qaNetwork | Out-Null
    }
    Write-Output 'Disposable timezone database and network removed.'
}
