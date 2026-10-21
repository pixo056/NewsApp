$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'download-package.ps1')
. (Join-Path $PSScriptRoot 'frontend-ready.ps1')
$projectRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$testRoot=Join-Path $projectRoot ('.local\tests\launcher-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $testRoot | Out-Null
function Assert-True($Condition,[string]$Message) { if (!$Condition) { throw $Message } }
function Assert-Throws([scriptblock]$Action,[string]$Expected) {
    try { & $Action } catch { if ($_.Exception.Message -notlike ('*'+$Expected+'*')) { throw }; return }
    throw ('Expected failure: '+$Expected)
}
function Write-Package([string]$Target,[string]$Text='verified package') { [IO.File]::WriteAllText($Target,$Text,(New-Object Text.UTF8Encoding $false)) }
# Regression: pnpm must not make Windows PowerShell load PowerShell 7's Utility.
Assert-True ((Get-Command Get-FileHash).Module.Path.StartsWith((Join-Path $PSHOME 'Modules'),[StringComparison]::OrdinalIgnoreCase)) 'Utility must belong to the running PowerShell engine'
$fixture=Join-Path $testRoot 'fixture.bin'
$target=Join-Path $testRoot 'archive.zip'
Write-Package $fixture
$fixtureHash=(Get-FileHash -LiteralPath $fixture -Algorithm SHA256).Hash
$checksum={ param($Uri) $fixtureHash+'  archive.zip' }
$validDownload={ param($Uri,$Target) Write-Package $Target }
$options=@{ Url='https://example.invalid/archive.zip'; File=$target; Algorithm='SHA256'; ReadChecksum=$checksum; RetryDelaySeconds=0 }
try {
    Get-VerifiedPackage @options -Download $validDownload
    Assert-True ((Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash -eq $fixtureHash) 'Successful package was not verified'
    Assert-True (!(Test-Path -LiteralPath ($target+'.partial'))) 'Successful package left a partial file'
    Write-Host 'PASS verified download'

    $cachedTime=(Get-Item -LiteralPath $target).LastWriteTimeUtc
    Get-VerifiedPackage @options -Download { throw 'A valid cached archive must not be downloaded again' }
    Assert-True ((Get-Item -LiteralPath $target).LastWriteTimeUtc -eq $cachedTime) 'Valid cache was replaced'
    Write-Host 'PASS verified cache is reused'

    Write-Package $target 'old truncated archive'
    Get-VerifiedPackage @options -Download $validDownload
    Assert-True ((Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash -eq $fixtureHash) 'Invalid cached archive did not recover'
    Write-Host 'PASS invalid cache is replaced'

    Remove-Item -LiteralPath $target
    $state=@{ calls=0 }
    $interrupted={ param($Uri,$Target)
        $state.calls++
        if ($state.calls -eq 1) { Write-Package $Target 'incomplete'; throw 'connection interrupted' }
        Assert-True (!(Test-Path -LiteralPath $Target)) 'Failed partial was reused'
        Write-Package $Target
    }
    Get-VerifiedPackage @options -Download $interrupted
    Assert-True ($state.calls -eq 2) 'Interrupted download was not retried'
    Write-Host 'PASS interrupted download retries from a clean file'

    Write-Package $target 'old cached archive'
    $state=@{ calls=0 }
    $wrongHash={ param($Uri,$Target) $state.calls++; Write-Package $Target 'wrong contents' }
    Assert-Throws { Get-VerifiedPackage @options -Download $wrongHash } 'checksum mismatch'
    Assert-True ($state.calls -eq 3) 'Retry count is not bounded to three'
    Assert-True ([IO.File]::ReadAllText($target) -eq 'old cached archive') 'Failed download destroyed existing cache'
    Assert-True (!(Test-Path -LiteralPath ($target+'.partial'))) 'Failed download left partial contents'
    Write-Host 'PASS checksum mismatch is bounded and never replaces cache'

    Remove-Item -LiteralPath $target
    Assert-Throws { Get-VerifiedPackage @options -Download { param($Uri,$Target) Write-Package $Target 'partial'; throw 'offline' } } 'after 3 attempts'
    Assert-True (!(Test-Path -LiteralPath $target)) 'Interrupted contents became a reusable archive'
    Assert-True (!(Test-Path -LiteralPath ($target+'.partial'))) 'Network failure left a partial file'
    Write-Host 'PASS repeated network failures leave no archive'

    $malformed=@{}+$options
    $malformed.ReadChecksum={ 'an HTML error page' }
    Assert-Throws { Get-VerifiedPackage @malformed -Download { throw 'Download must not run without a valid checksum' } } 'Invalid checksum response'
    Write-Host 'PASS malformed checksum fails before download'

    $running=[pscustomobject]@{ HasExited=$false }
    $state=@{ calls=0 }
    Wait-NewsAppFrontend -Process $running -PollMilliseconds 0 -Probe {
        $state.calls++
        if ($state.calls -eq 1) { throw 'connection refused' }
        if ($state.calls -eq 2) { return [pscustomobject]@{StatusCode=200;Headers=@{}} }
        [pscustomobject]@{StatusCode=200;Headers=@{'X-NewsApp-Web'='1';'X-NewsApp-Mode'='local'}}
    }
    Assert-True ($state.calls -eq 3) 'Startup did not wait for NewsApp identity'
    Write-Host 'PASS readiness waits through refusal and unrelated server'

    $state=@{ calls=0 }
    Wait-NewsAppFrontend -Process $running -Mode wifi -WifiIp '192.0.2.10' -PollMilliseconds 0 -Probe {
        $state.calls++
        $ip=if ($state.calls -eq 1) { '192.0.2.99' } else { '192.0.2.10' }
        [pscustomobject]@{StatusCode=200;Headers=@{'X-NewsApp-Web'='1';'X-NewsApp-Mode'='wifi';'X-NewsApp-Wifi-Ip'=$ip}}
    }
    Assert-True ($state.calls -eq 2) 'Wi-Fi readiness did not verify current IP'
    Write-Host 'PASS Wi-Fi readiness validates mode and IP'

    Assert-Throws { Wait-NewsAppFrontend -Process ([pscustomobject]@{HasExited=$true}) -Probe { throw 'Must not probe a stopped process' } } 'stopped before'
    Assert-Throws { Wait-NewsAppFrontend -Process $running -TimeoutSeconds 1 -PollMilliseconds 10 -Probe { [pscustomobject]@{StatusCode=200;Headers=@{}} } } 'did not become ready'
    Write-Host 'PASS exited process and readiness timeout fail clearly'
    Write-Host 'All 10 launcher checks passed.'
} finally {
    # Nonrecursive cleanup of this test's unique directory only.
    $boundary=[IO.Path]::GetFullPath((Join-Path $projectRoot '.local\tests'))+[IO.Path]::DirectorySeparatorChar
    if (![IO.Path]::GetFullPath($testRoot).StartsWith($boundary,[StringComparison]::OrdinalIgnoreCase)) { throw 'Invalid test cleanup path' }
    Get-ChildItem -LiteralPath $testRoot -File | ForEach-Object { Remove-Item -LiteralPath $_.FullName -Force }
    Remove-Item -LiteralPath $testRoot -Force
}


