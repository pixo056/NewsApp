param([switch]$NoOpen,[switch]$Lan)
. (Join-Path $PSScriptRoot 'local-env.ps1')
Set-Location -LiteralPath $projectRoot
& (Join-Path $PSScriptRoot 'setup-local.ps1')
$jar=Join-Path $projectRoot 'backend\target\newsapp-1.0.0.jar'
$needsBuild=!(Test-Path -LiteralPath $jar)
if (!$needsBuild) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive=[IO.Compression.ZipFile]::OpenRead($jar)
    try { if (!$archive.GetEntry('BOOT-INF/classes/application.yml')) { $needsBuild=$true } } finally { $archive.Dispose() }
}
if (!$needsBuild) { $jarTime=(Get-Item -LiteralPath $jar).LastWriteTimeUtc; $needsBuild=!!(Get-ChildItem -LiteralPath (Join-Path $projectRoot 'backend\src') -Recurse -File | Where-Object { $_.LastWriteTimeUtc -gt $jarTime } | Select-Object -First 1); if ((Get-Item -LiteralPath (Join-Path $projectRoot 'backend\pom.xml')).LastWriteTimeUtc -gt $jarTime) { $needsBuild=$true } }
if ($needsBuild) {
    Write-Host 'Building Java 25 backend...'
    & $maven -q -f (Join-Path $projectRoot 'backend\pom.xml') "-Dmaven.repo.local=$(Join-Path $localRoot 'm2')" -DskipTests package
    if ($LASTEXITCODE -ne 0) { throw 'Backend build failed' }
}
& (Join-Path $PSScriptRoot 'start-database.ps1')
$nodeCommand=Get-Command node.exe -ErrorAction SilentlyContinue
$node=if ($nodeCommand) { $nodeCommand.Source } else { Join-Path $env:USERPROFILE '.cache\codex-runtimes\codex-primary-runtime\dependencies\node\bin\node.exe' }
if (!(Test-Path -LiteralPath $node)) { throw 'Install Node.js 24 to run the frontend.' }
$webHost='127.0.0.1'
Remove-Item Env:NEWSAPP_LAN_IP,Env:NEWSAPP_LAN_PREFIX -ErrorAction SilentlyContinue
if ($Lan) {
    . (Join-Path $PSScriptRoot 'wifi-network.ps1')
    & (Join-Path $PSScriptRoot 'enable-wifi.ps1') -NodePath $node
    $env:NEWSAPP_LAN_IP=$wifiNetwork.Ip
    $env:NEWSAPP_LAN_PREFIX=[string]$wifiNetwork.Prefix
    $webHost='0.0.0.0'
}
if (!(Test-Path -LiteralPath (Join-Path $projectRoot 'node_modules\vite\bin\vite.js'))) {
    $pnpm=Get-Command pnpm.cmd -ErrorAction SilentlyContinue
    if (!$pnpm) { throw 'Run pnpm install --frozen-lockfile before starting the frontend.' }
    & $pnpm.Source install --frozen-lockfile
    if ($LASTEXITCODE -ne 0) { throw 'Frontend dependency installation failed' }
}
function Test-Backend {
    try { $health=Invoke-RestMethod -Uri 'http://127.0.0.1:8080/api/health' -TimeoutSec 2; return $health.status -eq 'ok' -and $health.java -like '25.*' -and $health.application -eq 'newsapp' } catch { return $false }
}
$owned=@()
try {
    if (!(Test-Backend)) {
        $javaExe=Join-Path $env:JAVA_HOME 'bin\java.exe'
        $backendProcess=Start-Process -FilePath $javaExe -ArgumentList @('-jar',('"'+$jar+'"')) -WorkingDirectory $projectRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $localRoot 'logs\backend.log') -RedirectStandardError (Join-Path $localRoot 'logs\backend-error.log')
        $owned+=@{id=$backendProcess.Id;started=$backendProcess.StartTime.ToUniversalTime().ToString('o');path=$javaExe}
        $ready=$false
        for ($attempt=0;$attempt -lt 40;$attempt++) { if (Test-Backend) { $ready=$true;break }; if ($backendProcess.HasExited) { break }; Start-Sleep -Milliseconds 500 }
        if (!$ready) { throw 'Backend did not start. See .local/logs/backend.log and backend-error.log.' }
    }
    $frontendRunning=$false
    try { $response=Invoke-WebRequest -Uri 'http://127.0.0.1:5173/' -UseBasicParsing -TimeoutSec 2; $frontendRunning=$response.StatusCode -eq 200 -and $response.Headers['X-NewsApp-Web'] -eq '1' } catch {}
    if ($frontendRunning -and (($Lan -and $response.Headers['X-NewsApp-Mode'] -ne 'wifi') -or (!$Lan -and $response.Headers['X-NewsApp-Mode'] -ne 'local'))) {
        throw 'Another NewsApp mode is already running. Run stop.cmd, then start.cmd or start-wifi.cmd.'
    }
    if ($frontendRunning -and $Lan -and $response.Headers['X-NewsApp-Wifi-Ip'] -ne $wifiNetwork.Ip) { throw 'Wi-Fi address changed. Run stop.cmd, then start-wifi.cmd.' }
    if (!$frontendRunning) {
        $vite=Join-Path $projectRoot 'node_modules\vite\bin\vite.js'
        $frontend=Start-Process -FilePath $node -ArgumentList @(('"'+$vite+'"'),'--host',$webHost,'--port','5173','--strictPort') -WorkingDirectory $projectRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $localRoot 'logs\frontend.log') -RedirectStandardError (Join-Path $localRoot 'logs\frontend-error.log')
        $owned+=@{id=$frontend.Id;started=$frontend.StartTime.ToUniversalTime().ToString('o');path=$node}
        . (Join-Path $PSScriptRoot 'frontend-ready.ps1')
        $expectedMode=if ($Lan) { 'wifi' } else { 'local' }
        $expectedIp=if ($Lan) { $wifiNetwork.Ip } else { '' }
        Wait-NewsAppFrontend -Process $frontend -Mode $expectedMode -WifiIp $expectedIp
    }
    $registry=@($owned)
    $processFile=Join-Path $localRoot 'processes.json'
    if (Test-Path -LiteralPath $processFile) {
        foreach ($entry in @(Get-Content -LiteralPath $processFile -Raw | ConvertFrom-Json)) {
            $process=Get-Process -Id $entry.id -ErrorAction SilentlyContinue
            if ($process -and $process.StartTime.ToUniversalTime() -eq ([datetime]$entry.started).ToUniversalTime() -and $process.Path -eq $entry.path -and !($registry | Where-Object { $_.id -eq $entry.id })) { $registry+=@{id=$entry.id;started=([datetime]$entry.started).ToUniversalTime().ToString('o');path=$entry.path} }
        }
    }
    [IO.File]::WriteAllText($processFile,(ConvertTo-Json -InputObject @($registry)),(New-Object Text.UTF8Encoding $false))
    Write-Host '';Write-Host 'NewsApp: http://localhost:5173/';Write-Host 'Local account credentials: .local/accounts.txt';Write-Host 'Keep this window open. Ctrl+C stops the processes started by this window.'
    if ($Lan) {
        $phoneUrl='http://'+$wifiNetwork.Ip+':5173/'
        Write-Host ('PHONE (same Wi-Fi): '+$phoneUrl) -ForegroundColor Green
        Write-Host 'Test mailbox: laptop only, http://localhost:5173/dev/mailbox'
        [IO.File]::WriteAllText((Join-Path $localRoot 'wifi-url.txt'),$phoneUrl,(New-Object Text.UTF8Encoding $false))
    }
    if (!$NoOpen) { Start-Process 'http://localhost:5173/' }
    while ($true) { Start-Sleep -Seconds 2; if ($frontend -and $frontend.HasExited) { throw 'Frontend stopped. See .local/logs/frontend-error.log.' }; if ($backendProcess -and $backendProcess.HasExited) { throw 'Backend stopped. See .local/logs/backend-error.log.' } }
} finally {
    foreach ($entry in $owned) { $process=Get-Process -Id $entry.id -ErrorAction SilentlyContinue; if ($process -and $process.StartTime.ToUniversalTime() -eq ([datetime]$entry.started).ToUniversalTime()) { Stop-Process -Id $entry.id -ErrorAction SilentlyContinue; Wait-Process -Id $entry.id -Timeout 10 -ErrorAction SilentlyContinue } }
}
