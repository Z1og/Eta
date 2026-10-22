# Build the independent opencode2api GPL-3.0-or-later child executable for Android ARM64.
# Source is pinned and shipped with the APK to satisfy redistribution/source requirements.
param(
    [string] $SourceDir = "build/opencode2api-upstream"
)
$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$source = (Resolve-Path (Join-Path $root $SourceDir)).Path
$revision = (& git -C $source rev-parse HEAD).Trim()
$expected = "0bb23c7a7f0b7cbe08c475e7148c56bc477abcf9"
if ($LASTEXITCODE -ne 0 -or $revision -ne $expected) {
    throw "Unexpected opencode2api checkout. Expected $expected, got $revision"
}
$binaryDirectory = Join-Path $root "app/src/main/jniLibs/arm64-v8a"
$assetDirectory = Join-Path $root "app/src/main/assets/third_party"
$licensesDirectory = Join-Path $root "app/src/main/assets/licenses"
New-Item -ItemType Directory -Force $binaryDirectory, $assetDirectory, $licensesDirectory | Out-Null

$oldOs = $env:GOOS
$oldArch = $env:GOARCH
$oldCgo = $env:CGO_ENABLED
try {
    $env:GOOS = "android"
    $env:GOARCH = "arm64"
    $env:CGO_ENABLED = "0"
    Push-Location $source
    try {
        & go build -trimpath -ldflags="-s -w" -o (Join-Path $binaryDirectory "libeta_opencode2api.so") ./cmd/opencode2api
        if ($LASTEXITCODE -ne 0) { throw "Go ARM64 Android cross build failed" }
    } finally {
        Pop-Location
    }
} finally {
    $env:GOOS = $oldOs
    $env:GOARCH = $oldArch
    $env:CGO_ENABLED = $oldCgo
}

$binary = Join-Path $binaryDirectory "libeta_opencode2api.so"
$stream = [System.IO.File]::OpenRead($binary)
try {
    $header = New-Object byte[] 20
    [void]$stream.Read($header, 0, $header.Length)
    if ([BitConverter]::ToString($header,0,4) -ne "7F-45-4C-46" -or
        [BitConverter]::ToUInt16($header,16) -ne 3 -or
        [BitConverter]::ToUInt16($header,18) -ne 183) {
        throw "Expected ARM64 ET_DYN ELF, got an incompatible binary"
    }
} finally {
    $stream.Dispose()
}

Copy-Item -LiteralPath (Join-Path $source "LICENSE") -Destination (Join-Path $licensesDirectory "opencode2api-GPL-3.0-or-later.txt") -Force

Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archivePath = Join-Path $assetDirectory "opencode2api-source.zip"
if (Test-Path $archivePath) { Remove-Item -LiteralPath $archivePath -Force }
$archive = [System.IO.Compression.ZipFile]::Open($archivePath, [System.IO.Compression.ZipArchiveMode]::Create)
try {
    Get-ChildItem -LiteralPath $source -Recurse -File | Where-Object {
        $_.FullName -notmatch '[\\/]\.git[\\/]'
    } | ForEach-Object {
        $relative = $_.FullName.Substring($source.Length).TrimStart([char]'\',[char]'/').Replace('\','/')
        [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
            $archive, $_.FullName, "opencode2api-$($revision.Substring(0,7))/$relative",
            [System.IO.Compression.CompressionLevel]::Optimal
        ) | Out-Null
    }
} finally {
    $archive.Dispose()
}
Write-Output ("Embedded ELF: " + (Get-Item $binary).Length + " bytes")
Write-Output ("Corresponding GPL source: " + (Get-Item $archivePath).Length + " bytes")
Write-Output ("Upstream commit: " + $revision)
