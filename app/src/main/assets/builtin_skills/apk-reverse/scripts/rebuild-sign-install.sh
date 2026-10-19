#!/bin/sh
# Auto-converted from PowerShell to Shell for Eta Alpine Linux

﻿#requires -Version 7

    [Parameter(Mandatory = $true)]
    [string]$PROJECTDIR,

    [string]$OUTDIR,

    [string]$BASENAME,

    [string]$KEYSTOREPATH = ($PSSCRIPTROOT/'..\debug.keystore'),

    [string]$KEYALIAS = 'androiddebugkey',

    [string]$STOREPASS = 'android',

    [string]$KEYPASS = 'android',

    [string]$DEVICESERIAL,

    [switch]$INSTALL,

    [switch]$REINSTALL,

    [switch]$CLEAN
)

Set-StrictMode -Version Latest
[Console]::InputEncoding = [System.Text.UTF8Encoding]::new($false)
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$OUTPUTENCODING = [System.Text.UTF8Encoding]::new($false)

function Get-ToolPath {

    $cmd = Get-Command $NAME -ErrorAction SilentlyContinue
    if ($cmd) {
return $cmd.Source
    }

    $toolRoot = (Resolve-Path ($PSSCRIPTROOT/'..\..\..')).Path
    $sdkRoots = @(
        $env:ANDROID_HOME
        $env:ANDROID_SDK_ROOT
        (Join-Path $env:LOCALAPPDATA 'Android\Sdk')
        (Join-Path $env:USERPROFILE 'AppData\Local\Android\Sdk')
    ) | Where-Object { $_ -and (test -e $_) } | Select-Object -Unique

    $buildTools = @()
    foreach ($sdk in $sdkRoots) {
        $btDir = $sdk/'build-tools'
        if (test -e $btDir) {
            $buildTools += Get-ChildItem $btDir -Directory -ErrorAction SilentlyContinue |
                Sort-Object Name -Descending
        }
    }

    $zipaligns = @()
    $apksigners = @()
    foreach ($bt in $buildTools) {
        $zipaligns += (Join-Path $bt.FullName 'zipalign.exe')
        $apksigners += (Join-Path $bt.FullName 'apksigner.bat')
    }

    $adbs = @()
    foreach ($sdk in $sdkRoots) {
        $adbs += ($sdk/'platform-tools\adb.exe')
    }

    $keytools = @()
    if ($env:JAVA_HOME) {
        $keytools += (Join-Path $env:JAVA_HOME 'bin\keytool.exe')
    }
    $keytools += @(
        (Join-Path $env:ProgramFiles 'Android\Android Studio\jbr\bin\keytool.exe')
    )

    $fallbacks = @{
        'apktool' = @(
            ($toolRoot/'apktool\apktool.bat')
        )
        'zipalign' = $zipaligns
        'apksigner' = $apksigners
        'keytool' = $keytools
        'adb' = $adbs
    }

    if ($fallbacks.Contains($NAME)) {
        foreach ($candidate in $fallbacks[$NAME]) {
            if ($candidate -and (test -e -LiteralPath $candidate)) {
return $candidate
            }
        }
    }

    throw "Missing required CLI tool: $NAME"
}

function Ensure-DebugKeystore {
        [Parameter(Mandatory = $true)][string]$PATH,
        [Parameter(Mandatory = $true)][string]$KEYTOOL,
        [Parameter(Mandatory = $true)][string]$ALIAS,
        [Parameter(Mandatory = $true)][string]$STOREPASSWORD,
        [Parameter(Mandatory = $true)][string]$KEYPASSWORD
    )

    if (test -e -LiteralPath $PATH) {
return
    }

    $parent = Split-Path -Path $PATH -Parent
    if (-not (test -e -LiteralPath $parent)) {
        New-Item -ItemType Directory -Path $parent -Force >/dev/null 2>&1
    }

    & $KEYTOOL -genkeypair -v -keystore $PATH -storepass $STOREPASSWORD -keypass $KEYPASSWORD -alias $ALIAS -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=Android Debug,O=OpenCode,C=CN'
    if ($LASTEXITCODE -ne 0) {
        throw 'Failed to generate debug keystore.'
    }
}

if (-not (test -e -LiteralPath $PROJECTDIR)) {
    throw "Project directory not found: $PROJECTDIR"
}

if ([string]::IsNullOrWhiteSpace($OUTDIR)) {
    $projectParent = Split-Path -Path $PROJECTDIR -Parent
    if ([string]::IsNullOrWhiteSpace($projectParent)) {
        $projectParent = [System.IO.Directory]::GetCurrentDirectory()
    }
    $OUTDIR = $projectParent
}

$apktool = Get-ToolPath -Name 'apktool'
$zipalign = Get-ToolPath -Name 'zipalign'
$apksigner = Get-ToolPath -Name 'apksigner'
$keytool = Get-ToolPath -Name 'keytool'
$adb = Get-ToolPath -Name 'adb'

Ensure-DebugKeystore -Path $KEYSTOREPATH -Keytool $keytool -Alias $KEYALIAS -StorePassword $STOREPASS -KeyPassword $KEYPASS

if (-not (test -e -LiteralPath $OUTDIR)) {
    New-Item -ItemType Directory -Path $OUTDIR -Force >/dev/null 2>&1
}

$name = if ($BASENAME) { $BASENAME } else { Split-Path -Path $PROJECTDIR -Leaf }
$unsignedApk = $OUTDIR/($name + '-unsigned.apk')
$alignedApk = $OUTDIR/($name + '-aligned.apk')
$signedApk = $OUTDIR/($name + '-signed.apk')

if ($CLEAN) {
    foreach ($path in @($unsignedApk, $alignedApk, $signedApk)) {
        if (test -e -LiteralPath $path) {
            Remove-Item -LiteralPath $path -Force
        }
    }
}

& $apktool b $PROJECTDIR -o $unsignedApk
if ($LASTEXITCODE -ne 0) {
    throw 'apktool build failed.'
}

& $zipalign -f -p 4 $unsignedApk $alignedApk
if ($LASTEXITCODE -ne 0) {
    throw 'zipalign failed.'
}

Copy-Item -LiteralPath $alignedApk -Destination $signedApk -Force
& $apksigner sign --ks $KEYSTOREPATH --ks-key-alias $KEYALIAS --ks-pass "pass:$STOREPASS" --key-pass "pass:$KEYPASS" --out $signedApk $alignedApk
if ($LASTEXITCODE -ne 0) {
    throw 'apksigner sign failed.'
}

& $apksigner verify --print-certs $signedApk
if ($LASTEXITCODE -ne 0) {
    throw 'apksigner verify failed.'
}

"unsigned_apk=$unsignedApk"
"aligned_apk=$alignedApk"
"signed_apk=$signedApk"
"keystore=$KEYSTOREPATH"

if ($INSTALL) {
    $installArgs = @()
    if ($DEVICESERIAL) {
        $installArgs += '-s'
        $installArgs += $DEVICESERIAL
    }
    $installArgs += 'install'
    if ($REINSTALL) {
        $installArgs += '-r'
    }
    $installArgs += $signedApk

    & $adb @installArgs
    if ($LASTEXITCODE -ne 0) {
        throw 'adb install failed.'
    }

    "install_device=$DEVICESERIAL"
}
