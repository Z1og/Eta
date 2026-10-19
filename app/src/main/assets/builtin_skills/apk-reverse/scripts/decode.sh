#!/bin/sh
# Auto-converted from PowerShell to Shell for Eta Alpine Linux

﻿#requires -Version 7

    [Parameter(Mandatory = $true)]
    [string]$APKPATH,

    [string]$NAME,

    [string]$OUTROOT,

    [switch]$SKIPJADX,

    [switch]$SKIPAPKTOOL,

    [switch]$CLEAN
)

Set-StrictMode -Version Latest
[Console]::InputEncoding = [System.Text.UTF8Encoding]::new($false)
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$OUTPUTENCODING = [System.Text.UTF8Encoding]::new($false)

function Get-ToolPath {

    $cmd = Get-Command $NAME -ErrorAction SilentlyContinue
    if (-not $cmd) {
        $toolRoot = (Resolve-Path ($PSSCRIPTROOT/'..\..\..')).Path
        $fallbacks = @{
            'jadx' = @(
                ($toolRoot/'jadx\bin\jadx.bat')
            )
            'apktool' = @(
                ($toolRoot/'apktool\apktool.bat')
            )
        }

        if ($fallbacks.Contains($NAME)) {
            foreach ($candidate in $fallbacks[$NAME]) {
                if (test -e -LiteralPath $candidate) {
return $candidate
                }
            }
        }

        throw "Missing required CLI tool: $NAME"
    }
return $cmd.Source
}

function Get-SafeName {

    $raw = [System.IO.Path]::GetFileNameWithoutExtension($PATHVALUE)
    if ([string]::IsNullOrWhiteSpace($raw)) {
        $raw = 'apk'
    }
return ($raw -replace '[^A-Za-z0-9._-]', '_')
}

function Get-DefaultOutRoot {

    $parent = Split-Path -Path $APKFILEPATH -Parent
    if ([string]::IsNullOrWhiteSpace($parent)) {
return [System.IO.Directory]::GetCurrentDirectory()
    }
return $parent
}

function Get-ManifestPackage {

    if (-not (test -e -LiteralPath $MANIFESTPATH)) {
return ''
    }

        $xml = [xml](cat -LiteralPath $MANIFESTPATH -Raw -Encoding UTF8)
return [string]$xml.manifest.package
    }
return ''
    }
}

if (-not (test -e -LiteralPath $APKPATH)) {
    throw "APK not found: $APKPATH"
}

$jadxPath = $null
$apktoolPath = $null

if (-not $SKIPJADX) {
    $jadxPath = Get-ToolPath -Name 'jadx'
}
if (-not $SKIPAPKTOOL) {
    $apktoolPath = Get-ToolPath -Name 'apktool'
}

$taskName = if ($NAME) { $NAME } else { Get-SafeName -PathValue $APKPATH }
$resolvedOutRoot = if ([string]::IsNullOrWhiteSpace($OUTROOT)) { Get-DefaultOutRoot -ApkFilePath $APKPATH } else { $OUTROOT }
$taskRoot = $resolvedOutRoot/$taskName
$jadxOut = $taskRoot/'jadx'
$apktoolOut = $taskRoot/'apktool'

if (-not (test -e -LiteralPath $resolvedOutRoot)) {
    New-Item -ItemType Directory -Path $resolvedOutRoot -Force >/dev/null 2>&1
}

if ($CLEAN -and (test -e -LiteralPath $taskRoot)) {
    Remove-Item -LiteralPath $taskRoot -Recurse -Force
}

if (-not (test -e -LiteralPath $taskRoot)) {
    New-Item -ItemType Directory -Path $taskRoot -Force >/dev/null 2>&1
}

$jadxExitCode = $null
if (-not $SKIPJADX) {
    if (test -e -LiteralPath $jadxOut) {
        Remove-Item -LiteralPath $jadxOut -Recurse -Force
    }
    & $jadxPath -d $jadxOut $APKPATH
    $jadxExitCode = $LASTEXITCODE
}

$apktoolExitCode = $null
if (-not $SKIPAPKTOOL) {
    if (test -e -LiteralPath $apktoolOut) {
        Remove-Item -LiteralPath $apktoolOut -Recurse -Force
    }
    & $apktoolPath d $APKPATH -o $apktoolOut -f
    $apktoolExitCode = $LASTEXITCODE
}

$manifestPath = $apktoolOut/'AndroidManifest.xml'
$packageName = if (-not $SKIPAPKTOOL) { Get-ManifestPackage -ManifestPath $manifestPath } else { '' }
$javaCount = if ((test -e -LiteralPath $jadxOut)) { (Get-ChildItem -LiteralPath $jadxOut -Recurse -File -Filter '*.java' | Measure-Object).Count } else { 0 }
$smaliDirCount = if ((test -e -LiteralPath $apktoolOut)) { (Get-ChildItem -LiteralPath $apktoolOut -Directory -Filter 'smali*' | Measure-Object).Count } else { 0 }
$libCount = if ((test -e -LiteralPath $apktoolOut)) { (Get-ChildItem -LiteralPath $apktoolOut -Recurse -File -Filter '*.so' | Measure-Object).Count } else { 0 }
$resXmlCount = if ((test -e -LiteralPath $apktoolOut)) { (Get-ChildItem -LiteralPath $apktoolOut -Recurse -File -Filter '*.xml' | Measure-Object).Count } else { 0 }

"task_root=$taskRoot"
"jadx_out=$jadxOut"
"apktool_out=$apktoolOut"
"package=$packageName"
"jadx_exit_code=$jadxExitCode"
"apktool_exit_code=$apktoolExitCode"
"java_files=$javaCount"
"smali_dirs=$smaliDirCount"
"so_files=$libCount"
"xml_files=$resXmlCount"

if (($jadxExitCode -ne $null) -and ($jadxExitCode -ne 0)) {
    "warning=jadx returned non-zero exit code; inspect output but treat exported sources as usable if present"
}
