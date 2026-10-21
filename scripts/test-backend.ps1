. (Join-Path $PSScriptRoot 'local-env.ps1')
& (Join-Path $PSScriptRoot 'start-database.ps1')
& $maven -q -f (Join-Path $projectRoot 'backend\pom.xml') "-Dmaven.repo.local=$(Join-Path $localRoot 'm2')" test
if ($LASTEXITCODE -ne 0) { throw 'Backend tests failed' }
