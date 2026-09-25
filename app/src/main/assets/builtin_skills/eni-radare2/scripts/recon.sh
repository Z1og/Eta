#!/bin/sh
# Auto-converted from PowerShell to Shell for Eta Alpine Linux

﻿param(
    [Parameter(Mandatory = $true)]
    [string]$TARGETPATH,

    [int]$STRINGSLIMIT = 40,

    [int]$IMPORTSLIMIT = 80,

    [switch]$RUNANALYSIS
)

# 强制当前脚本使用 UTF-8 输出，尽量减少中文标题乱码。
[Console]::InputEncoding = [System.Text.UTF8Encoding]::new($false)
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$OUTPUTENCODING = [System.Text.UTF8Encoding]::new($false)


. ($PSSCRIPTROOT/'..\..\scripts\lib\ToolDiscovery.ps1')

$bootstrapScript = $PSSCRIPTROOT/'..\..\scripts\bootstrap-reverse.ps1'

function Get-RequiredToolSpec {
        [Parameter(Mandatory = $true)]
        [string]$NAME
    )

    $spec = Resolve-ReverseToolSpec -Name $NAME
    if (-not $spec.Available) {
        # Attempt auto-bootstrap
        if (test -e -LiteralPath $bootstrapScript) {
            Write-Output "INFO: $NAME not found, attempting auto-bootstrap..."
            & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $bootstrapScript -Capability @($NAME) -SkipRefresh
            $spec = Resolve-ReverseToolSpec -Name $NAME
        }
        if (-not $spec.Available) {
            throw "缺少命令：$NAME — 自动安装失败，请手动安装。参考: https://github.com/radareorg/radare2"
        }
    }
return $spec
}

function Write-Section {
        [Parameter(Mandatory = $true)]
    [string]$TITLE
    )

    # 用固定分段标题，方便人看，也方便后续 grep。
    ""
    "=== $TITLE ==="
}

$rabin2 = Get-RequiredToolSpec -Name 'rabin2'
$r2 = $null
if ($RUNANALYSIS) {
    $r2 = Get-RequiredToolSpec -Name 'r2'
}

# 将输入路径规范化成绝对路径，避免 r2/rabin2 在相对路径下歧义解析。
$resolvedPath = Resolve-Path -LiteralPath $TARGETPATH
$target = $resolvedPath.Path

"目标文件: $target"

Write-Section -Title '基本信息'
& $rabin2.Command @($rabin2.PrefixArgs + @('-I', '--', $target))

Write-Section -Title '节区'
& $rabin2.Command @($rabin2.PrefixArgs + @('-S', '--', $target))

Write-Section -Title '导入'
& $rabin2.Command @($rabin2.PrefixArgs + @('-i', '--', $target)) | Select-Object -First $IMPORTSLIMIT

Write-Section -Title '导出'
& $rabin2.Command @($rabin2.PrefixArgs + @('-E', '--', $target))

Write-Section -Title '字符串'
& $rabin2.Command @($rabin2.PrefixArgs + @('-zz', '--', $target)) | Select-Object -First $STRINGSLIMIT

if ($RUNANALYSIS) {
    Write-Section -Title '函数与入口分析'
    & $r2.Command @($r2.PrefixArgs + @('-A', '-q', '-c', 's entry0;afl;iz;ii;q', '--', $target))
}
