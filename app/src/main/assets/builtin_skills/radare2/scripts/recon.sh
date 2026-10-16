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


function Test-Tool {
        [Parameter(Mandatory = $true)]
        [string]$NAME
    )

    # 先确认 radare2 相关命令可用，避免执行到中途才失败。
    if (-not (Get-Command -Name $NAME -ErrorAction SilentlyContinue)) {
        throw "缺少命令：$NAME"
    }
}

function Write-Section {
        [Parameter(Mandatory = $true)]
    [string]$TITLE
    )

    # 用固定分段标题，方便人看，也方便后续 grep。
    ""
    "=== $TITLE ==="
}

Test-Tool -Name 'rabin2'
if ($RUNANALYSIS) {
    Test-Tool -Name 'r2'
}

# 将输入路径规范化成绝对路径，避免 r2/rabin2 在相对路径下歧义解析。
$resolvedPath = Resolve-Path -LiteralPath $TARGETPATH
$target = $resolvedPath.Path

"目标文件: $target"

Write-Section -Title '基本信息'
& rabin2 -I -- "$target"

Write-Section -Title '节区'
& rabin2 -S -- "$target"

Write-Section -Title '导入'
& rabin2 -i -- "$target" | Select-Object -First $IMPORTSLIMIT

Write-Section -Title '导出'
& rabin2 -E -- "$target"

Write-Section -Title '字符串'
& rabin2 -zz -- "$target" | Select-Object -First $STRINGSLIMIT

if ($RUNANALYSIS) {
    Write-Section -Title '函数与入口分析'
    & r2 -A -q -c 's entry0;afl;iz;ii;q' -- "$target"
}
