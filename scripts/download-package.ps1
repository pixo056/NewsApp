# A pnpm child process can inherit PowerShell 7's module search path in Windows
# PowerShell 5.1. Load this engine's module explicitly instead of the first match.
Import-Module -Name (Join-Path $PSHOME 'Modules\Microsoft.PowerShell.Utility\Microsoft.PowerShell.Utility.psd1') -ErrorAction Stop

function Get-VerifiedPackage {
    param(
        [Parameter(Mandatory=$true)][string]$Url,
        [Parameter(Mandatory=$true)][string]$File,
        [Parameter(Mandatory=$true)][ValidateSet('SHA1','SHA256','SHA512')][string]$Algorithm,
        [string]$ChecksumUrl,
        [ValidateRange(1,5)][int]$MaxAttempts=3,
        [ValidateRange(0,30)][int]$RetryDelaySeconds=2,
        [scriptblock]$ReadChecksum={ param($Uri) (Invoke-WebRequest -Uri $Uri -UseBasicParsing -TimeoutSec 30).Content },
        [scriptblock]$Download={ param($Uri,$Target) Invoke-WebRequest -Uri $Uri -OutFile $Target -UseBasicParsing -TimeoutSec 300 }
    )
    $ErrorActionPreference='Stop'
    if (!$ChecksumUrl) { $ChecksumUrl=$Url+'.'+$Algorithm.ToLowerInvariant() }
    $hashLength=@{ SHA1=40; SHA256=64; SHA512=128 }[$Algorithm]
    $expected=$null
    for ($attempt=1;$attempt -le $MaxAttempts;$attempt++) {
        try {
            $checksumText=[string](& $ReadChecksum $ChecksumUrl)
            $expected=($checksumText.Trim() -split '\s+')[0]
            if ($expected -notmatch ('^[a-fA-F0-9]{'+$hashLength+'}$')) { throw 'Invalid checksum response' }
            break
        } catch {
            if ($attempt -eq $MaxAttempts) { throw "Could not read $Algorithm checksum for $Url after $MaxAttempts attempts. $($_.Exception.Message)" }
            Start-Sleep -Seconds $RetryDelaySeconds
        }
    }
    if ((Test-Path -LiteralPath $File -PathType Leaf) -and (Get-FileHash -LiteralPath $File -Algorithm $Algorithm).Hash -eq $expected) { return }

    # Only a fully downloaded, verified file becomes the reusable archive.
    # Keep any old archive until replacement is ready, including after a failed retry.
    $partial=$File+'.partial'
    New-Item -ItemType Directory -Force -Path ([IO.Path]::GetDirectoryName([IO.Path]::GetFullPath($File))) | Out-Null
    for ($attempt=1;$attempt -le $MaxAttempts;$attempt++) {
        try {
            if (Test-Path -LiteralPath $partial) { Remove-Item -LiteralPath $partial -Force }
            Write-Host "Downloading $Url (attempt $attempt/$MaxAttempts)"
            & $Download $Url $partial | Out-Null
            if (!(Test-Path -LiteralPath $partial -PathType Leaf) -or (Get-FileHash -LiteralPath $partial -Algorithm $Algorithm).Hash -ne $expected) { throw "$Algorithm checksum mismatch" }
            if (Test-Path -LiteralPath $File) { [IO.File]::Replace($partial,$File,[NullString]::Value) }
            else { [IO.File]::Move($partial,$File) }
            return
        } catch {
            if ($attempt -eq $MaxAttempts) { throw "Could not download a verified package from $Url after $MaxAttempts attempts. $($_.Exception.Message)" }
            Start-Sleep -Seconds $RetryDelaySeconds
        } finally {
            if (Test-Path -LiteralPath $partial) { Remove-Item -LiteralPath $partial -Force }
        }
    }
}

