. (Join-Path $PSScriptRoot 'local-env.ps1')
$processFile=Join-Path $localRoot 'processes.json'
if (Test-Path -LiteralPath $processFile) {
    $entries=Get-Content -LiteralPath $processFile -Raw | ConvertFrom-Json
    foreach ($entry in $entries) {
        $process=Get-Process -Id $entry.id -ErrorAction SilentlyContinue
        if ($process -and $process.StartTime.ToUniversalTime() -eq ([datetime]$entry.started).ToUniversalTime() -and $process.Path -eq $entry.path) { Stop-Process -Id $entry.id; Wait-Process -Id $entry.id -Timeout 10 -ErrorAction SilentlyContinue }
    }
}
if (Test-Path -LiteralPath (Join-Path $dbDirectory 'PG_VERSION')) {
    & (Join-Path $pgBin 'pg_ctl.exe') -D $dbDirectory status *> $null
    if ($LASTEXITCODE -eq 0) { & (Join-Path $pgBin 'pg_ctl.exe') -D $dbDirectory -m fast -w stop }
}
Write-Host 'Local NewsApp processes stopped. Data is preserved in .local.'
