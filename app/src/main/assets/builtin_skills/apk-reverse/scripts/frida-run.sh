#!/bin/sh
# Auto-converted from PowerShell to Shell for Eta Alpine Linux

﻿#requires -Version 7

    [string]$PACKAGE,

    [string]$PROCESS,

    [string]$REMOTEHOST = '127.0.0.1:27042',

    [string]$SCRIPTPATH,

    [switch]$USB,

    [switch]$SPAWN,

    [switch]$PAUSE,

    [switch]$LISTDEVICES,

    [switch]$LISTPROCESSES
)

Set-StrictMode -Version Latest
[Console]::InputEncoding = [System.Text.UTF8Encoding]::new($false)
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$OUTPUTENCODING = [System.Text.UTF8Encoding]::new($false)

function Get-ToolPath {

    $cmd = Get-Command $NAME -ErrorAction SilentlyContinue
    if (-not $cmd) {
        # Prefer PATH / Python Scripts next to current interpreter
        $pyScripts = @()
            $py = (Get-Command python ).Source
            $pyScripts += (Join-Path (Split-Path $py -Parent) 'Scripts')
        } catch {}
        $pyScripts += @(
            (Join-Path $env:APPDATA 'Python\Python312\Scripts')
            (Join-Path $env:APPDATA 'Python\Python313\Scripts')
            (Join-Path $env:APPDATA 'Python\Python314\Scripts')
            (Join-Path $env:LOCALAPPDATA 'Programs\Python\Python312\Scripts')
            (Join-Path $env:LOCALAPPDATA 'Programs\Python\Python313\Scripts')
        )
        foreach ($dir in $pyScripts) {
            if (-not $dir) { continue }
            $candidate = $dir/"$NAME.exe"
            if (test -e -LiteralPath $candidate) {
return $candidate
            }
        }

        throw "Missing required CLI tool: $NAME (install frida-tools: pip install frida-tools)"
    }
return $cmd.Source
}

$fridaLsDevices = Get-ToolPath -Name 'frida-ls-devices'
$fridaPs = Get-ToolPath -Name 'frida-ps'
$frida = Get-ToolPath -Name 'frida'
$python = Get-Command python -ErrorAction SilentlyContinue

if (-not $python) {
    throw 'Missing required CLI tool: python'
}

$pythonExe = $python.Source

if ($LISTDEVICES) {
    & $pythonExe -c "import frida; [print(f'{d.id}`t{d.type}`t{d.name}') for d in frida.enumerate_devices()]"
exit $LASTEXITCODE
}

$target = if ($PACKAGE) { $PACKAGE } elseif ($PROCESS) { $PROCESS } else { '' }
if ([string]::IsNullOrWhiteSpace($target) -and -not $LISTPROCESSES) {
    throw 'Provide -Package or -Process, or use -ListProcesses.'
}

$deviceFlag = if ($USB) { '-U' } else { '-H' }

if ($LISTPROCESSES) {
    $escapedRemoteHost = $REMOTEHOST.Replace("'", "''")
    $pythonFlag = if ($USB) { 'usb' } else { 'remote-host' }
    & $pythonExe -c "import frida; manager = frida.get_device_manager(); device = frida.get_usb_device() if '$pythonFlag' == 'usb' else manager.add_remote_device('$escapedRemoteHost'); [print(f'{p.pid}`t{p.name}') for p in device.enumerate_processes()]"
exit $LASTEXITCODE
}

if (-not (test -e -LiteralPath $SCRIPTPATH)) {
    throw "Frida script not found: $SCRIPTPATH"
}

$args = @($deviceFlag)
if (-not $USB) {
    $args += $REMOTEHOST
}
if ($SPAWN) {
    $args += '-f'
} else {
    $args += '-n'
}
$args += $target
$args += '-l'
$args += $SCRIPTPATH
if (-not $PAUSE) {
    $args += '--no-pause'
}

& $frida @args
exit $LASTEXITCODE
