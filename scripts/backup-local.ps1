. (Join-Path $PSScriptRoot 'local-env.ps1')
& (Join-Path $pgBin 'pg_ctl.exe') -D $dbDirectory status *> $null
if ($LASTEXITCODE -eq 0) { throw 'Run stop.cmd before the backup. PostgreSQL must be stopped for a consistent physical backup.' }
$destination=Join-Path $localRoot ('backups\newsapp-'+(Get-Date -Format 'yyyyMMdd-HHmmss')+'.zip')
New-Item -ItemType Directory -Force -Path ([IO.Path]::GetDirectoryName($destination)) | Out-Null
$paths=@($dbDirectory,(Join-Path $localRoot 'config.json'),(Join-Path $localRoot 'accounts.txt'))
if (Test-Path -LiteralPath (Join-Path $localRoot 'media')) { $paths+=Join-Path $localRoot 'media' }
Compress-Archive -LiteralPath $paths -DestinationPath $destination
Write-Host "Local backup saved: $destination"
