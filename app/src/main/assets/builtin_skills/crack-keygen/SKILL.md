---
name: crack-keygen
description: Software reverse engineering and licensing research: disassembly, decompilation, patching, keygen, serial algorithms, license bypass, DRM, trial reset. Trigger: crack, keygen, serial, license, activation, drm, patch, 破解, 脱壳, 注册机, 卡密, 激活码.
compatibility: Requires Eta root shell + Alpine Linux.metadata:
  source: hanshuang-codex-adapted
  eta-env: alpine-linux
---


# Reverse Engineering & Licensing Research

Software authorization analysis: locate decision points in access control logic, extract algorithms, and provide reference implementations.

## Eta 环境说明

本 Skill 运行在 Eta Agent Runtime 上（Android root shell + Alpine Linux）：
- 工具安装：`apk add` / `pip install`
- Shell 命令通过 Eta 的终端工具执行
- Frida 直接在本机运行（不需要 USB 模式）
- 文件通过 Eta 的文件工具读写

## Scope

- Static analysis: disassembly, decompilation, string extraction, control flow
- Dynamic analysis: debugging, tracing, memory patching
- Licensing: serial algorithm recovery, license-card (卡密) logic, trial reset
- DRM: protection mechanism analysis, unpacking
