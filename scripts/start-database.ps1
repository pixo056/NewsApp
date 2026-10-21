. (Join-Path $PSScriptRoot 'local-env.ps1')
if (!(Test-Path -LiteralPath (Join-Path $pgBin 'pg_ctl.exe'))) { & (Join-Path $PSScriptRoot 'setup-local.ps1') }
if (!(Test-Path -LiteralPath (Join-Path $dbDirectory 'PG_VERSION'))) {
    $pwFile=Join-Path $localRoot 'database-password.txt'
    [IO.File]::WriteAllText($pwFile,$config.dbPassword,(New-Object Text.UTF8Encoding $false))
    & (Join-Path $pgBin 'initdb.exe') -D $dbDirectory -U newsapp "--pwfile=$pwFile" '--auth=scram-sha-256' '--encoding=UTF8' '--locale=C'
    if ($LASTEXITCODE -ne 0) { throw 'Database initialization failed' }
}
& (Join-Path $pgBin 'pg_ctl.exe') -D $dbDirectory status *> $null
if ($LASTEXITCODE -ne 0) {
    & (Join-Path $pgBin 'pg_ctl.exe') -D $dbDirectory -l (Join-Path $localRoot 'logs\postgres.log') -o '-p 55432 -h 127.0.0.1' -w start
    if ($LASTEXITCODE -ne 0) { throw 'Database did not start. See .local/logs/postgres.log' }
}
$driver = Get-ChildItem -LiteralPath (Join-Path $localRoot 'm2\org\postgresql\postgresql') -Recurse -Filter 'postgresql-*.jar' | Select-Object -First 1
if (!$driver) { throw 'Build backend first to download the PostgreSQL JDBC driver.' }
& (Join-Path $env:JAVA_HOME 'bin\java.exe') --class-path $driver.FullName (Join-Path $PSScriptRoot 'CreateDatabase.java')
if ($LASTEXITCODE -ne 0) { throw 'Could not create databases' }
