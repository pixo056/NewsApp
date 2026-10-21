param([switch]$SkipMediaTools)
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$localRoot = Join-Path $projectRoot '.local'
$toolsRoot = Join-Path $localRoot 'tools'
$downloadsRoot = Join-Path $localRoot 'downloads'
New-Item -ItemType Directory -Force -Path $toolsRoot,$downloadsRoot,(Join-Path $localRoot 'logs') | Out-Null

function Get-Package([string]$Url,[string]$File) {
    if (!(Test-Path -LiteralPath $File)) { Write-Host "Downloading $Url"; Invoke-WebRequest -Uri $Url -OutFile $File -UseBasicParsing }
}
function Expand-WithoutRoot([string]$Archive,[string]$Destination) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [IO.Compression.ZipFile]::OpenRead($Archive)
    try {
        foreach ($entry in $zip.Entries) {
            $relative = ($entry.FullName -split '/',2)[-1]
            if (!$relative -or $entry.FullName.EndsWith('/')) { continue }
            $target = [IO.Path]::GetFullPath((Join-Path $Destination $relative))
            $boundary = [IO.Path]::GetFullPath($Destination) + [IO.Path]::DirectorySeparatorChar
            if (!$target.StartsWith($boundary,[StringComparison]::OrdinalIgnoreCase)) { throw 'Invalid archive path' }
            New-Item -ItemType Directory -Force -Path ([IO.Path]::GetDirectoryName($target)) | Out-Null
            [IO.Compression.ZipFileExtensions]::ExtractToFile($entry,$target,$true)
        }
    } finally { $zip.Dispose() }
}

$maven = Join-Path $toolsRoot 'apache-maven-3.9.11\bin\mvn.cmd'
if (!(Test-Path -LiteralPath $maven)) {
    $url = 'https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.11/apache-maven-3.9.11-bin.zip'
    $file = Join-Path $downloadsRoot 'maven.zip'; Get-Package $url $file
    $expected = ((Invoke-WebRequest -Uri "$url.sha512" -UseBasicParsing).Content.Trim() -split '\s+')[0]
    if ((Get-FileHash -LiteralPath $file -Algorithm SHA512).Hash -ne $expected) { throw 'Maven checksum mismatch' }
    Expand-Archive -LiteralPath $file -DestinationPath $toolsRoot -Force
}
$postgres = Join-Path $toolsRoot 'postgres\bin\postgres.exe'
if (!(Test-Path -LiteralPath $postgres)) {
    $url = 'https://repo.maven.apache.org/maven2/io/zonky/test/postgres/embedded-postgres-binaries-windows-amd64/17.6.0/embedded-postgres-binaries-windows-amd64-17.6.0.jar'
    $file = Join-Path $downloadsRoot 'postgres.jar'; Get-Package $url $file
    $expected = (Invoke-WebRequest -Uri "$url.sha1" -UseBasicParsing).Content.Trim()
    if ((Get-FileHash -LiteralPath $file -Algorithm SHA1).Hash -ne $expected) { throw 'PostgreSQL checksum mismatch' }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [IO.Compression.ZipFile]::OpenRead($file)
    try { $entry=$zip.GetEntry('postgres-windows-x86_64.txz'); [IO.Compression.ZipFileExtensions]::ExtractToFile($entry,(Join-Path $downloadsRoot 'postgres.txz'),$true) } finally { $zip.Dispose() }
    New-Item -ItemType Directory -Force -Path (Join-Path $toolsRoot 'postgres') | Out-Null
    & tar.exe -xf (Join-Path $downloadsRoot 'postgres.txz') -C (Join-Path $toolsRoot 'postgres')
    if ($LASTEXITCODE -ne 0) { throw 'Could not extract PostgreSQL' }
}
if (!$SkipMediaTools -and !(Test-Path -LiteralPath (Join-Path $toolsRoot 'ffmpeg\bin\ffmpeg.exe'))) {
    $url = 'https://www.gyan.dev/ffmpeg/builds/ffmpeg-release-essentials.zip'
    $file = Join-Path $downloadsRoot 'ffmpeg.zip'; Get-Package $url $file
    $expected = ((Invoke-WebRequest -Uri "$url.sha256" -UseBasicParsing).Content.Trim() -split '\s+')[0]
    if ((Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash -ne $expected) { throw 'FFmpeg checksum mismatch' }
    Expand-WithoutRoot $file (Join-Path $toolsRoot 'ffmpeg')
}
Write-Host 'Local tools are ready.'
